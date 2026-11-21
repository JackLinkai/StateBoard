"""本地固定语料检索层：替掉上游的 Tavily，vendor 一行不改。

为什么不用 Tavily / ollama
    Tavily —— 免费档 1000 credits/月，一次 basic search 1 credit。这套实验
              18 任务 × 2 组 × 5 重复 = 180 次研究，每次 20+ 次搜索，全量
              4000~5000 credits，免费额度撑不住，也不该为验证花这个钱。
    ollama —— 它是模型不是检索。deep research 缺的是"能搜到真实网页"，
              换本地模型照样搜不到；而且本机 ~/.ollama/models 是空的，
              7B 级模型也跑不动这种多轮 function calling。

为什么固定语料反而更对
    A/B 对照最怕混杂因素。真实搜索每次返回的网页、排序、内容长度都在变，
    两组跑出来的差异分不清是 ASC 的功劳还是搜索结果抖动的结果。把语料钉死，
    唯一变量就只剩"supervisor 每轮能看到什么"——这正是要证明的东西。

语料的真实性
    语料不是编的：正文来自真实联网搜索结果的摘要，落盘成 jsonl，跑的时候
    本地按关键词检索。局限必须写进报告：不能声称"真实联网 deep research"，
    只能声称"固定语料下的对照实验"。

刻意埋的噪声
    每条语料可以带 kind 字段，用来模拟真实搜索里那些害人的东西：
      stale     —— 旧口径 / 旧年份的数，看着对但已过期
      forecast  —— 预测值被写成实际值
      scope     —— 局部数据被当成全局（某省当全国、某季度当全年）
      conflict  —— 与其他来源直接矛盾
    ASC 有没有用，就看这些条目会不会污染最终结论。

用法
    from search_patch import install_local_search
    install_local_search()          # 幂等，import 后调一次
"""

from __future__ import annotations

import json
import math
import re
from pathlib import Path
from typing import Annotated, List, Literal, Optional

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import InjectedToolArg, tool

HERE = Path(__file__).resolve().parent
CORPUS_DIR = HERE / "corpus"
CORPUS_PATH = CORPUS_DIR / "corpus.jsonl"

_CORPUS_KEY: dict = {}

# 至少命中这么多个不同的 query token 才算相关；只靠一个泛词命中的返回空
MIN_HIT_TOKENS = 2
# 单个匹配的最小 IDF 加权分，低于它的当噪声丢掉
MIN_SCORE = 4.0

SEARCH_TOOL_DESCRIPTION = (
    "A search engine optimized for comprehensive, accurate, and trusted results. "
    "Useful for when you need to answer questions about current events."
)

_CORPUS: Optional[list[dict]] = None
_CORPUS_STATS: dict = {}


# ------------------------------------------------------------------ 语料加载

def load_corpus(path: Optional[Path] = None, force: bool = False) -> list[dict]:
    """读语料。默认读 corpus/ 下所有 *.jsonl 并按文件名排序合并，便于分批追加。"""
    global _CORPUS, _CORPUS_STATS

    if path is not None:
        files = [Path(path)]
    else:
        files = sorted(CORPUS_DIR.glob("*.jsonl"))

    key = tuple(str(f) for f in files)
    if _CORPUS is not None and not force and _CORPUS_KEY.get("k") == key:
        return _CORPUS

    items: list[dict] = []
    for p in files:
        if not p.exists():
            continue
        for lineno, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{p.name} 第 {lineno} 行不是合法 JSON：{exc}") from exc
            if not obj.get("content"):
                continue
            obj.setdefault("title", "(untitled)")
            obj.setdefault("url", f"local://corpus/{len(items)}")
            obj.setdefault("tags", [])
            obj.setdefault("kind", "fact")
            items.append(obj)

    _CORPUS = items
    _CORPUS_KEY["k"] = key
    _DF_CACHE.clear()
    _CORPUS_STATS = {
        "total": len(items),
        "files": [p.name for p in files if p.exists()],
        "by_kind": {k: sum(1 for x in items if x["kind"] == k)
                    for k in sorted({x["kind"] for x in items})},
    }
    return items


def corpus_stats() -> dict:
    load_corpus()
    return dict(_CORPUS_STATS)


# ------------------------------------------------------------------ 检索打分

_EN_STOP = {
    "the", "a", "an", "and", "or", "of", "in", "on", "for", "to", "is", "are",
    "what", "how", "why", "which", "with", "vs", "by", "at", "as", "be",
}

_ZH_FILLER = {
    "的", "了", "和", "与", "是", "在", "有", "吗", "呢", "请", "帮我", "请问",
    "多少", "哪些", "什么", "分别", "以及", "还有", "一下", "现在", "目前",
    "情况", "调研", "对比", "说明", "解释", "来源", "差异",
}


def _tokens(text: str) -> list[str]:
    """英文/数字按词切，中文按 2-gram 切。没装 jieba，2-gram 对这种短查询够用。"""
    text = (text or "").lower()
    out: list[str] = []

    for w in re.findall(r"[a-z][a-z0-9+#.\-]{1,}|[0-9]{2,}", text):
        if w not in _EN_STOP and len(w) >= 2:
            out.append(w)

    for run in re.findall(r"[\u4e00-\u9fa5]+", text):
        if len(run) == 1:
            continue
        for i in range(len(run) - 1):
            bg = run[i:i + 2]
            if bg in _ZH_FILLER:
                continue
            out.append(bg)

    # 去重保序
    seen = set()
    uniq = []
    for t in out:
        if t not in seen:
            seen.add(t)
            uniq.append(t)
    return uniq


def _df_table(corpus: list[dict]) -> dict[str, int]:
    """文档频率缓存：某个 token 出现在多少条语料里。

    没它的话「电池」「2024」这种泛词会把所有条目都顶上来，研究跑出来全是
    勉强相关的噪声，两组都失败、实验没有区分度。IDF 让罕见词说了算。
    """
    cache = _DF_CACHE.get("table")
    if cache is not None and _DF_CACHE.get("size") == len(corpus):
        return cache
    table: dict[str, int] = {}
    for item in corpus:
        blob = " ".join([
            " ".join(item.get("tags") or []),
            item.get("title") or "",
            item.get("content") or "",
        ]).lower()
        for t in set(_all_tokens(blob)):
            table[t] = table.get(t, 0) + 1
    _DF_CACHE["table"] = table
    _DF_CACHE["size"] = len(corpus)
    return table


_DF_CACHE: dict = {}


def _all_tokens(blob: str) -> list[str]:
    """整篇文档的 token 集合用，不做停用词过滤（df 统计要全量）。"""
    out = re.findall(r"[a-z][a-z0-9+#.\-]{1,}|[0-9]{2,}", blob)
    for run in re.findall(r"[\u4e00-\u9fa5]+", blob):
        for i in range(len(run) - 1):
            out.append(run[i:i + 2])
    return out


def _score(query_tokens: list[str], item: dict, df: dict[str, int], n_docs: int) -> float:
    """IDF 加权：tags 命中 ×3、标题命中 ×2、正文命中 ×1，再乘该词的稀有度。"""
    tags = " ".join(item.get("tags") or []).lower()
    title = (item.get("title") or "").lower()
    content = (item.get("content") or "").lower()

    score = 0.0
    hit_tokens = 0
    for t in query_tokens:
        pos = 0.0
        if t in tags:
            pos = 3.0
        elif t in title:
            pos = 2.0
        elif t in content:
            pos = 1.0
        if pos == 0.0:
            continue
        hit_tokens += 1
        d = df.get(t, n_docs)
        idf = math.log(1.0 + n_docs / max(d, 1))
        score += pos * idf

    if hit_tokens == 0:
        return 0.0
    # 只靠一个泛词命中的，判为不相关：真实搜索里这种"勉强沾边"的结果
    # 恰恰是污染源，宁可返回空让 agent 换个角度搜。
    if hit_tokens < MIN_HIT_TOKENS:
        return 0.0
    return score


def search_corpus(query: str, max_results: int = 5,
                  corpus: Optional[list[dict]] = None) -> list[dict]:
    items = corpus if corpus is not None else load_corpus()
    qa = _tokens(query)
    if not qa:
        return []

    df = _df_table(items)
    scored = []
    for idx, item in enumerate(items):
        s = _score(qa, item, df, len(items))
        if s >= MIN_SCORE:
            scored.append((s, idx, item))

    # 分数降序，同分按语料顺序，保证可复现
    scored.sort(key=lambda x: (-x[0], x[1]))
    return [it for _, _, it in scored[:max_results]]


# ------------------------------------------------------------------ 工具本体

def _format(rows: list[dict], query: str) -> str:
    if not rows:
        return (
            f"No search results found for query: {query}\n"
            "The corpus has no matching page. Try a different angle or a "
            "narrower keyword."
        )
    out = []
    for i, r in enumerate(rows, 1):
        out.append(f"\n\n--- SOURCE {i}: {r['title']} ---\n")
        out.append(f"URL: {r['url']}\n")
        out.append(f"\nSUMMARY:\n{r['content']}\n")
        out.append("\n" + "-" * 80 + "\n")
    return "".join(out)


@tool("web_search", description=SEARCH_TOOL_DESCRIPTION)
async def local_web_search(
    queries: List[str],
    max_results: Annotated[int, InjectedToolArg] = 5,
    topic: Annotated[Literal["general", "news", "finance"], InjectedToolArg] = "general",
    config: RunnableConfig = None,
) -> str:
    """Search the fixed local corpus. Same shape as the upstream Tavily tool."""
    corpus = load_corpus()
    blocks = []
    for q in queries or []:
        rows = search_corpus(q, max_results=max_results, corpus=corpus)
        blocks.append(f"QUERY: {q}" + _format(rows, q))
    return "\n".join(blocks)


# 上游按 tool.metadata 里的 type/name 识别搜索工具，必须对齐
local_web_search.metadata = {
    **(local_web_search.metadata or {}),
    "type": "search",
    "name": "web_search",
}


# ------------------------------------------------------------------ 安装 patch

_PATCHED = False


def install_local_search() -> None:
    """把 deep_researcher 模块级的 get_all_tools 换成"上游工具集 - Tavily + 本地检索"。

    为什么打在这一层：researcher 和 researcher_tools 两个节点都在函数体里调
    模块全局的 get_all_tools，patch 它一次，两处同时生效，别的逻辑一行不动。
    """
    global _PATCHED
    if _PATCHED:
        return

    import open_deep_research.deep_researcher as dr

    orig = dr.get_all_tools

    async def get_all_tools_with_local_search(config: RunnableConfig):
        tools = await orig(config)
        kept = []
        for t in tools:
            md = getattr(t, "metadata", None) or {}
            # 丢弃上游注入的搜索工具（Tavily / anthropic / openai 原生检索）
            if md.get("type") == "search" or md.get("name") == "web_search":
                continue
            kept.append(t)
        kept.append(local_web_search)
        return kept

    dr.get_all_tools = get_all_tools_with_local_search
    _PATCHED = True


def uninstall_local_search() -> None:
    """还原，供单元测试或对照用。"""
    global _PATCHED
    if not _PATCHED:
        return
    import open_deep_research.deep_researcher as dr

    del dr.get_all_tools  # 恢复被遮蔽的模块属性
    _PATCHED = False


if __name__ == "__main__":
    stats = corpus_stats()
    print("语料:", stats)
    for q in ["2024 年动力电池装机量 宁德时代", "储能招标规模 2023 2024", "LangGraph CrewAI AutoGen 对比"]:
        rows = search_corpus(q, max_results=3)
        print(f"\n[{q}] -> {len(rows)} 条")
        for r in rows:
            print("   -", r["kind"], "|", r["title"][:60])
