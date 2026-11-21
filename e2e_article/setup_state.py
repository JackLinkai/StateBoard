"""Tool Use 推文实验：把任务要求写进 ASC，导出三组冻结上下文包。

运行前确保 ASC 服务已用 .env 的真实 PG/Redis 启动（http://localhost:8080）。
A 组不读状态卡；B 组读 lean 状态卡；C 组读同等体积但去掉治理的降级卡。
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VENDOR = ROOT.parent / "e2e_file_edit" / "_vendor"
SDK = ROOT.parent / "sdk" / "python"
for p in (str(VENDOR), str(SDK)):
    if p not in sys.path:
        sys.path.insert(0, p)

from asc import AscClient, AscConflictError  # noqa: E402

TASK_ID = "tooluse-article-day2"
RUN_ID = "e2e-article-20261121-1200"
OUT = ROOT / "results" / RUN_ID
CONTEXT_DIR = OUT / "contexts"
ARTICLE_DIR = OUT / "articles"
SCORE_DIR = OUT / "scores"

TITLE = "Agent Day2：Tool Use 学习（下）——Tool Runtime、MCP、安全与评估"

SOURCE_FILES = [
    "<本地素材：索引>",
    "<本地素材：工具笔记>",
    "<本地素材：评测笔记>",
    "<本地素材：个人经历>",
    "<本地素材：选题大纲>",
]

USER_BRIEF = (
    "根据本地相关文件（路径见下），写一篇关于 Tool Use 的微信公众号推文。"
    "我的上一篇叫《Agent Day2：Tool Use 学习（上）——调用流程、幂等、错误处理与 Tool Registry 四域架构》，"
    "这一篇计划讲 Tool Runtime、MCP、安全与评估。"
    "文章要能吸引读者阅读。输出一个 md 文件，标题体现在文件名里，文件第一行不要写标题，"
    "第一行直接是正文：今日学习计划。讲述今日学习目标，涉及的单词用反引号括起来，重点内容加粗。"
)

HARD_CONSTRAINTS = [
    "成品只允许一个 md 文件，文件名即标题，文件内第一行不得出现任何标题标记或文章标题",
    "文件第一行必须是正文文字，且以「今日学习计划」开头；其后用一段文字讲今日学习目标",
    "正文中所有技术术语、协议名、工具名、字段名用反引号包住，重点结论用双星号加粗",
    "正文一级标题只保留四个，依次是 Tool Runtime、MCP、安全、评估；不得另写文章标题",
    "四个主题必须全部覆盖，且开头一段要自然衔接上一篇（上），不重复上一篇已讲的调用流程、幂等、错误处理、Tool Registry 四域架构",
    "全文不出现任何编程语言名，包括来源素材里的公众号名称；也不出现 QPS、可用性这类后端数字",
    "第一人称，允许写「我当时想的是」；每个数字必须带口径，不写「大幅」「显著」；禁止自我矮化",
    "字数控制在 2500 到 3500 字，小标题 4 到 6 个，结尾按选题大纲的固定句式收束",
]

VERIFIED_FACTS = [
    ("mcp_tool_count", "ASC 当前 MCP Server 对外 15 个工具：原版 11 个 + 端到端测试新增 4 个", "mcp/asc_e2e_server.py"),
    ("runtime_seven_steps", "Tool Runtime 七步：白名单、参数校验、鉴权、幂等、执行、结果裁剪、审计", "02_工具治理.md"),
    ("mcp_roles", "MCP 三角色 Host / Client / Server，三类能力 Tools / Resources / Prompts", "02_工具治理.md"),
    ("mcp_transport", "主流传输从 HTTP + SSE 迁移到 Streamable HTTP，本地用 stdio", "02_工具治理.md"),
    ("error_taxonomy", "错误五分类：可重试、不可重试、需模型修正、需人工、未知；不可一律重试三次", "02_工具治理.md"),
    ("idempotency_key", "幂等键为 taskId + toolName + canonicalJSON(args) 的哈希，Redis 存短期、DB 唯一索引兜底", "02_工具治理.md"),
    ("result_trim", "结果裁剪三种：列表截断、字段裁剪、小模型摘要；返回 200 条 JSON 会撑爆上下文", "02_工具治理.md"),
    ("eval_method", "对照实验守唯一变量，A/B/C 加消融，每场景重复 N≥5，确定性仿真隔离模型变量", "本地素材：评测笔记"),
    ("asc_numbers", "ASC 确定性仿真：注入 token 161 到 47（降 70.8%），约束违反 33.3% 到 0，重复调用 3 到 0，恢复步数 2 到 1", "本地素材：项目记录"),
    ("contextfs_numbers", "ContextFS 确定性下限验证：注入 token 128.7 对 1002.8（降 87.2%），recall@3 为 1.0", "本地素材：项目记录"),
    ("project_evidence", "标书 Agent 初稿 3 天到 4 小时、合规提前发现率约 70%；称重平台数小时到秒级、缓存命中 60%+", "本地素材：项目记录"),
    ("team_scale", "2024 年初转 AI，从 0 组建并带起 10 人左右的 AI 研发团队", "本地素材：个人经历"),
    ("writing_style", "公众号系列定位是不写教程，只写自己踩过的坑和测出来的数；结构为问题到我试过的错到最后的做法到边界", "选题大纲.md"),
]

# 这些是素材里真实存在的旧值/冲突值，带过期时间写入，lean 视图不注入，audit 视图可见
STALE_FACTS = [
    ("resume_version_stale", "指向旧版经历材料的引用", "本地素材：索引"),
    ("mcp_tool_count_stale", "你做的 11 个工具版", "02_工具治理.md"),
    ("public_account_name", "一个含编程语言名的公众号旧名", "本地素材：个人经历"),
]

TODOS = [
    "写今日学习计划段，讲清这一篇要解决什么",
    "写 Tool Runtime 一节，落到一个可观测可控制可追责的执行层",
    "写 MCP 一节，讲清组合爆炸与三个角色、三类能力、传输与治理点",
    "写安全一节，讲清鉴权、越权、返回体积和版本协商",
    "写评估一节，用 ASC 与 ContextFS 的确定性仿真数据，带口径",
    "写边界一节，说清这套东西在什么场景不成立",
    "结尾用选题大纲的固定句式收束并衔接下一篇",
]


def main() -> None:
    for d in (OUT, CONTEXT_DIR, ARTICLE_DIR, SCORE_DIR):
        d.mkdir(parents=True, exist_ok=True)

    asc = AscClient("http://localhost:8080", timeout=10.0)
    try:
        asc.create_task(TASK_ID, TITLE)
    except AscConflictError:
        pass

    for text in HARD_CONSTRAINTS:
        asc.add_constraint(TASK_ID, text, "HARD")
    asc.add_constraint(TASK_ID, "素材篇幅可以精简，但四个主题与格式硬约束一条都不能删", "HARD")

    for key, value, source in VERIFIED_FACTS:
        asc.record_fact(TASK_ID, key, value, source=source)

    past = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
    for key, value, source in STALE_FACTS:
        asc.record_fact(TASK_ID, key, value, expires_at=past, source=source)

    for text in TODOS:
        asc.record_todo(TASK_ID, text)

    lean = asc.get_state_card(TASK_ID, budget="large", view="lean")
    audit = asc.get_state_card(TASK_ID, budget="large", view="audit")

    (OUT / "asc_card_lean.json").write_text(
        json.dumps(lean, ensure_ascii=False, indent=2), encoding="utf-8")
    (OUT / "asc_card_audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2), encoding="utf-8")

    package_a = "\n".join([
        "# 任务", USER_BRIEF, "",
        "## 可用素材", *[f"- {p}" for p in SOURCE_FILES], "",
        "## 交付", f"把成品写到 {ARTICLE_DIR / 'A.md'}，只写正文，不写解释。", "",
    ])

    card_text = json.dumps(lean, ensure_ascii=False, indent=2)
    package_b = "\n".join([
        "# 任务", USER_BRIEF, "",
        "## 可用素材", *[f"- {p}" for p in SOURCE_FILES], "",
        "## WorkBuddy 本轮从 ASC 注入的当前有效状态卡",
        "下面这张卡由 ASC 在每次决策前确定性编译，代表此刻仍有效的事实与约束。",
        "与素材原文冲突时以状态卡为准；卡里标注为过期或被覆盖的内容不要写进正文。", "",
        "```json", card_text, "```", "",
        "## 交付", f"把成品写到 {ARTICLE_DIR / 'B.md'}，只写正文，不写解释。", "",
    ])

    degraded = {
        "taskId": TASK_ID,
        "version": lean["version"],
        "hardConstraints": [],
        "softConstraints": [
            {"text": t} for t in HARD_CONSTRAINTS
        ],
        "verifiedFacts": lean.get("verifiedFacts", [])[:4] + [
            {"key": k, "value": v, "source": s} for k, v, s in STALE_FACTS
        ],
        "openTodos": lean.get("openTodos", [])[:4],
        "note": "精简上下文，但所有约束降为软约束、旧值与新值并列注入",
    }
    package_c = "\n".join([
        "# 任务", USER_BRIEF, "",
        "## 可用素材", *[f"- {p}" for p in SOURCE_FILES], "",
        "## 本轮精简上下文（无有效性治理）",
        "下面是压缩后的上下文。所有要求都按普通建议处理，可按篇幅需要裁剪；",
        "同一字段的新旧取值并列给出，由你自己判断用哪个。", "",
        "```json", json.dumps(degraded, ensure_ascii=False, indent=2), "```", "",
        "## 交付", f"把成品写到 {ARTICLE_DIR / 'C.md'}，只写正文，不写解释。", "",
    ])

    for name, text in (("A", package_a), ("B", package_b), ("C", package_c)):
        p = CONTEXT_DIR / f"{name}.md"
        p.write_text(text, encoding="utf-8")
        print(f"{name}: {p} ({len(text)} chars)")

    manifest = {
        "runId": RUN_ID,
        "taskId": TASK_ID,
        "title": TITLE,
        "baseUrl": "http://localhost:8080",
        "datasource": "PostgreSQL + Redis (.env, 地址不入库)",
        "leanTokens": lean.get("estimatedTokens"),
        "auditTokens": audit.get("estimatedTokens"),
        "artifacts": {
            "A": str(CONTEXT_DIR / "A.md"),
            "B": str(CONTEXT_DIR / "B.md"),
            "C": str(CONTEXT_DIR / "C.md"),
        },
        "note": "三组素材路径与任务描述完全相同，唯一差异是注入的上下文包",
    }
    (OUT / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
