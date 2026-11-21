"""推文三组对照的客观判分器。

判据全部来自用户明确要求与 ASC 状态卡里的硬约束，不做主观质量打分。
用法：python score.py
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RUN_ID = "e2e-article-20261121-1200"
BASE = ROOT / "results" / RUN_ID
ARTICLES = BASE / "articles"
SCORES = BASE / "scores"
SCORES.mkdir(parents=True, exist_ok=True)

# 硬约束：正文不得出现的词。编程语言名 + 对外口径禁词 + 素材里的旧值/冲突值
FORBIDDEN = {
    "编程语言名": ["Java", "java", "Python", "python", "JavaScript", "javascript"],
    "后端数字": ["QPS", "qps", "可用性"],
    "旧账号名": ["旧公众号名"],
    "旧材料版本": ["旧版经历材料"],
    "过期工具数": ["11 个工具版", "11个工具版", "11 个工具", "11个工具"],
    "自我矮化": ["我不太懂", "我也不是很懂", "说的不对", "水平有限", "抛砖引玉"],
    "空泛副词": ["大幅提升", "显著提升", "大幅度", "显著地"],
}

TOPICS = ["tool runtime", "mcp", "安全", "评估"]
H1_RE = re.compile(r"^#\s+(.+?)\s*$", re.M)


def count_cjk(text: str) -> int:
    return len(re.findall(r"[\u4e00-\u9fff]", text))


def estimate_tokens(text: str) -> int:
    cjk = count_cjk(text)
    other = len(text) - cjk
    return cjk + other // 4


def score_one(name: str) -> dict:
    path = ARTICLES / f"{name}.md"
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    lines = text.splitlines()
    first = lines[0].strip() if lines else ""

    h1s = [m.group(1).strip() for m in H1_RE.finditer(text)]
    h1_lower = [h.lower() for h in h1s]
    topics_hit = [t for t in TOPICS if any(t in h for h in h1_lower)]
    extra_h1 = [h for h in h1s if not any(t in h.lower() for t in TOPICS)]

    forbidden_hits = {}
    for category, words in FORBIDDEN.items():
        hits = [w for w in words if w in text]
        if hits:
            forbidden_hits[category] = hits

    backtick_spans = len(re.findall(r"`[^`\n]+`", text))
    bold_spans = len(re.findall(r"\*\*[^*\n]+\*\*", text))
    has_plan_first = first.startswith("今日学习计划")
    no_title_first = not first.startswith("#") and not text.startswith("# ")

    plan_paragraph = ""
    if has_plan_first:
        rest = "\n".join(lines[1:])
        m = re.search(r"\S+", rest)
        plan_paragraph = rest[:160] if m else ""

    has_bridge = bool(re.search(r"上一篇|（上）|Day\s*2|Day2|上篇", text))
    has_boundary = bool(re.search(r"边界|不成立|不适用|什么时候", text))
    has_fixed_ending = bool(re.search(r"欢迎说说你的场景|欢迎.*交流|你那儿", text))

    numbers = re.findall(r"\d+(?:\.\d+)?\s*%|\d+\s*到\s*\d+|\d+\s*→\s*\d+", text)

    checks = {
        "首行是今日学习计划": has_plan_first,
        "首行无标题标记": no_title_first,
        "四个一级标题齐全": len(topics_hit) == 4,
        "无多余一级标题": len(extra_h1) == 0,
        "衔接上一篇": has_bridge,
        "含边界段": has_boundary,
        "固定句式收尾": has_fixed_ending,
        "术语用反引号>=10": backtick_spans >= 10,
        "重点加粗>=5": bold_spans >= 5,
        "字数2500-3500": 2500 <= count_cjk(text) <= 3500,
        "无禁用内容": not forbidden_hits,
    }
    passed = sum(checks.values())
    return {
        "group": name,
        "file": str(path),
        "chars": len(text),
        "cjkChars": count_cjk(text),
        "estimatedTokens": estimate_tokens(text),
        "firstLine": first[:120],
        "h1s": h1s,
        "topicsHit": topics_hit,
        "extraH1": extra_h1,
        "backtickSpans": backtick_spans,
        "boldSpans": bold_spans,
        "forbiddenHits": forbidden_hits,
        "forbiddenCount": sum(len(v) for v in forbidden_hits.values()),
        "numberExprCount": len(numbers),
        "checks": checks,
        "passed": passed,
        "total": len(checks),
        "planParagraph": plan_paragraph,
    }


def main() -> None:
    results = {name: score_one(name) for name in ("A", "B", "C")}
    for name, r in results.items():
        (SCORES / f"{name}.json").write_text(
            json.dumps(r, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = ["# Tool Use 推文对照实验 · 客观判分", "",
             "判据来自任务书与 ASC 硬约束，不含主观质量分。", "",
             "| 指标 | A 未接入 | B 接入 ASC | C 无治理 |",
             "|---|---:|---:|---:|"]
    metric_rows = [
        ("通过项 / 11", lambda r: f"{r['passed']}/{r['total']}"),
        ("中文字符数", lambda r: r["cjkChars"]),
        ("估算 token", lambda r: r["estimatedTokens"]),
        ("反引号术语数", lambda r: r["backtickSpans"]),
        ("加粗数", lambda r: r["boldSpans"]),
        ("禁用内容类别数", lambda r: len(r["forbiddenHits"])),
        ("四主题一级标题", lambda r: f"{len(r['topicsHit'])}/4"),
        ("多余一级标题数", lambda r: len(r["extraH1"])),
        ("数字表达数", lambda r: r["numberExprCount"]),
    ]
    for label, fn in metric_rows:
        lines.append(f"| {label} | " + " | ".join(str(fn(results[n])) for n in "ABC") + " |")

    lines += ["", "## 逐项判定", ""]
    for name in "ABC":
        r = results[name]
        lines.append(f"### {name} 组")
        lines.append("")
        for k, v in r["checks"].items():
            lines.append(f"- {'通过' if v else '未过'}：{k}")
        if r["forbiddenHits"]:
            lines.append(f"- 禁用内容命中：{json.dumps(r['forbiddenHits'], ensure_ascii=False)}")
        if r["extraH1"]:
            lines.append(f"- 多余一级标题：{r['extraH1']}")
        lines.append(f"- 首行：`{r['firstLine']}`")
        lines.append("")

    lines += [
        "## 口径",
        "",
        "1. 三组素材路径、任务描述完全相同，唯一差异是注入的上下文包。",
        "2. token 为中文字符数 + 非中文字符数除以 4，仅用于相对比较。",
        "3. 禁用内容包括素材中真实存在的旧值（旧版材料、旧的工具数量口径、含编程语言名的旧账号名），",
        "   它们不是模型凭空编造，而是素材冲突是否被治理拦住的证据。",
        "4. 可读性、吸引力不做机器判分，由人工在 A/B/C 三稿里选一篇作为最终交付。",
        "",
    ]
    (BASE / "objective.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:20]))


if __name__ == "__main__":
    main()
