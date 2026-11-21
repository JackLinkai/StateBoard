"""重做 C 组降级上下文包：与 B 组等量事实，只把治理关掉。

差异只有三点：
1. 硬约束全部降为软约束（可被篇幅裁剪）
2. 过期旧值当作当前有效事实并列注入
3. 明确告知由模型自己判断新旧取值

不重新写入 ASC，避免状态卡被重复事实污染。
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASE = ROOT / "results" / "e2e-article-20261121-1200"

LEAN = json.loads((BASE / "asc_card_lean.json").read_text(encoding="utf-8"))
MANIFEST = json.loads((BASE / "manifest.json").read_text(encoding="utf-8"))
USER_BRIEF = (
    "根据本地相关文件（路径见下），写一篇关于 Tool Use 的微信公众号推文。"
    "我的上一篇叫《Agent Day2：Tool Use 学习（上）——调用流程、幂等、错误处理与 Tool Registry 四域架构》，"
    "这一篇计划讲 Tool Runtime、MCP、安全与评估。"
    "文章要能吸引读者阅读。输出一个 md 文件，标题体现在文件名里，文件第一行不要写标题，"
    "第一行直接是正文：今日学习计划。讲述今日学习目标，涉及的单词用反引号括起来，重点内容加粗。"
)

STALE_AS_CURRENT = [
    {"key": "resume_version_stale", "value": "指向旧版经历材料的引用", "source": "本地素材：索引"},
    {"key": "mcp_tool_count_stale", "value": "你做的 11 个工具版", "source": "02_工具治理.md"},
    {"key": "public_account_name", "value": "一个含编程语言名的公众号旧名", "source": "本地素材：个人经历"},
]

degraded = {
    "taskId": LEAN["taskId"],
    "version": LEAN["version"],
    "hardConstraints": [],
    "softConstraints": LEAN.get("hardConstraints", []),
    "verifiedFacts": LEAN.get("verifiedFacts", []) + STALE_AS_CURRENT,
    "openTodos": LEAN.get("openTodos", []),
    "invalidated": [],
    "compiledAt": LEAN.get("compiledAt"),
    "note": "事实数量与 B 组状态卡相同；取消硬约束优先级，旧值与新值并列注入，由模型自行裁决",
}

text = "\n".join([
    "# 任务", USER_BRIEF, "",
    "## 可用素材", *[f"- {p}" for p in MANIFEST.get("sourceFiles", [])], "",
    "## 本轮注入的压缩上下文（无有效性治理）",
    "下面是压缩后的上下文。所有要求按普通建议处理，可按篇幅需要裁剪；",
    "同一字段的新旧取值并列给出，由你自己判断用哪个。", "",
    "```json", json.dumps(degraded, ensure_ascii=False, indent=2), "```", "",
    "## 交付", "把成品写到 results/e2e-article-20261121-1200/articles/C.md，只写正文，不写解释。", "",
])

# 素材路径从 A 组包里取，保证三组完全一致
a_text = (BASE / "contexts" / "A.md").read_text(encoding="utf-8")
source_block = a_text.split("## 可用素材", 1)[1].split("## 交付", 1)[0].strip()
text = text.replace(
    "\n".join(MANIFEST.get("sourceFiles", [])), source_block.splitlines()[0].strip(" -")
) if MANIFEST.get("sourceFiles") else text
# 直接用 A 组的素材段重建，避免路径漂移
prefix, _, suffix = text.partition("## 可用素材")
suffix_tail = suffix.split("## 本轮注入", 1)[1]
text = prefix + "## 可用素材\n" + source_block + "\n\n## 本轮注入" + suffix_tail

p = BASE / "contexts" / "C.md"
p.write_text(text, encoding="utf-8")
print(f"C 组上下文包重建：{p} ({len(text)} chars)")
print(f"B 组字符数：{(BASE / 'contexts' / 'B.md').read_text(encoding='utf-8').__len__()}")
