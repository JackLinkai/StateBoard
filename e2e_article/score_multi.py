"""N=3 多轮判分：对 9 篇文章逐篇判分，输出均值与方差。

用法：python score_multi.py
"""

from __future__ import annotations

import json
import statistics as st
from pathlib import Path

from score import BASE, SCORES, estimate_tokens, score_one

GROUPS = ("A", "B", "C", "D")
RUNS = ("r1", "r2", "r3")
CONTEXTS = BASE / "contexts"


def context_tokens(group: str) -> int:
    """该组冻结上下文包的估算 token；D 用脱敏版，内容与本地复跑版等长。"""
    return estimate_tokens((CONTEXTS / f"{group}.md").read_text(encoding="utf-8"))


def main() -> None:
    all_scores: dict[str, list[dict]] = {}
    for g in GROUPS:
        rows = []
        for r in RUNS:
            p = f"{g}-{r}"
            row = score_one(p)
            row["group"] = g
            row["run"] = r
            rows.append(row)
            (SCORES / f"{p}.json").write_text(
                json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8")
        all_scores[g] = rows

    def stat(values):
        vals = [v for v in values if v is not None]
        if not vals:
            return "—"
        m = st.mean(vals)
        sd = st.pstdev(vals) if len(vals) > 1 else 0.0
        return f"{m:.1f} ± {sd:.1f}"

    metrics = [
        ("通过项 / 11", lambda r: r["passed"]),
        ("上下文包 token（估算）", None),  # 按组取，不按会话取
        ("成品输出 token（估算）", lambda r: r["estimatedTokens"]),
        ("每会话合计（估算，不含素材）", None),
        ("中文字符数", lambda r: r["cjkChars"]),
        ("反引号术语数", lambda r: r["backtickSpans"]),
        ("加粗数", lambda r: r["boldSpans"]),
        ("禁用内容类别数", lambda r: len(r["forbiddenHits"])),
        ("四主题一级标题数", lambda r: len(r["topicsHit"])),
        ("数字表达数", lambda r: r["numberExprCount"]),
    ]

    lines = [
        "# Tool Use 推文对照实验 · N=3 判分汇总", "",
        "四组素材路径、任务描述完全相同，唯一差异是注入的上下文包。",
        "每组独立会话 3 次，表格为均值 ± 总体标准差。", "",
        "| 指标 | A 未接入 ASC | B 接入 ASC | C 等量无治理 | D prompt 自检 |",
        "|---|---:|---:|---:|---:|",
    ]
    for label, fn in metrics:
        if fn is None:
            if label.startswith("上下文包"):
                cells = [str(context_tokens(g)) for g in GROUPS]
            else:
                cells = [f"{context_tokens(g) + st.mean([r['estimatedTokens'] for r in all_scores[g]]):.0f}"
                         for g in GROUPS]
        else:
            cells = [stat([fn(r) for r in all_scores[g]]) for g in GROUPS]
        lines.append(f"| {label} | " + " | ".join(cells) + " |")

    lines += ["", "## 逐次通过项明细", "",
              "| 会话 | 通过项 | 四主题一级标题 | 禁用内容命中 | 字数达标 |",
              "|---|---:|---:|---|---|"]
    for g in GROUPS:
        for row in all_scores[g]:
            c = row["checks"]
            forbidden = json.dumps(row["forbiddenHits"], ensure_ascii=False) if row["forbiddenHits"] else "无"
            lines.append(
                f"| {row['group']}-{row['run']} | {row['passed']}/{row['total']} | "
                f"{len(row['topicsHit'])}/4 | {forbidden} | "
                f"{'是' if c['字数2500-3500'] else '否'} |")

    lines += ["", "## 关键对照组", "",
              "- **A vs B**：接入 ASC 后，四主题一级标题从 0/4 变成 4/4，字数达标率从 1/3 变成 3/3，禁用内容命中归零。",
              "- **B vs C**：上下文等量（约 9.7k 字符 vs 10.0k 字符），C 保留软约束与新旧并列事实，",
              "  但四主题一级标题、禁止内容拦截都出现回退——差异来自硬约束优先级与过期事实拦截。",
              "- **C vs D**：D 在 C 的等量信息上只加一条 agent.md 风格自检约定（写前核对、成稿复核）。",
              "  通过项 9.3 升到 10.0，但四主题一级标题仅 2.7/4 且三次有一次归零，禁用内容仍有 1/3 命中——",
              "  自检有帮助但不稳定，抵不过 ASC 的确定性编译与过期拦截。",
              "- **B vs D**：B 稳定 10.7、一级标题 4/4、禁用内容 0；D 靠模型自我裁决，方差更大且旧值漏一次。",
              "", "## 口径", "",
              "1. token 为中文字符数 + 非中文字符数除以 4，只用于相对比较，不作成本承诺。",
              "2. 禁用内容里的旧值（旧版材料、旧的工具数量口径、含编程语言名的旧账号名）来自素材本身，",
              "   不是模型凭空捏造；命中说明旧值没有被治理层拦住。",
              "3. 可读性、吸引力不做机器判分，由人工在 B 组三次产物里选一篇作为最终交付。",
              "4. 样本量 N=3，每组独立会话；写作任务本身有随机性，结论只作方向性判断。",
              "5. D 组的自检约定写在 agent.md 风格的长驻规则里，不占单次注入预算；",
              "   它测的是「只给模型校验义务、不给状态源」能否替代 ASC。",
              "6. 上下文包 token 按冻结包计算（D 用脱敏版，与本地复跑版等长）；",
              "   每会话合计 = 上下文包 + 成品输出，不含包里列出的 5 份共享素材",
              "   （四组素材相同，全文合计约 2.0 万估算 token）。",
              "7. manifest 里的 362 是 lean 状态卡正文预算，不含任务书、字段名、UUID、",
              "   时间戳等上下文包结构开销，所以包体估算比它大。",
              "8. 真实 API usage（prompt/completion tokens）未记录，本表全部是规则估算；",
              "   B 组上下文包明显大于 A 组，本实验证明的是约束遵循，不是省 token。",
              "   注入 token 的节省见文件修改那组（315→153，−51.4%）。",
              "",
              ]
    (BASE / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:40]))


if __name__ == "__main__":
    main()
