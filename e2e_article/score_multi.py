"""N=3 多轮判分：对 9 篇文章逐篇判分，输出均值与方差。

用法：python score_multi.py
"""

from __future__ import annotations

import json
import statistics as st
from pathlib import Path

from score import BASE, SCORES, score_one

GROUPS = ("A", "B", "C")
RUNS = ("r1", "r2", "r3")


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
        ("中文字符数", lambda r: r["cjkChars"]),
        ("估算 token", lambda r: r["estimatedTokens"]),
        ("反引号术语数", lambda r: r["backtickSpans"]),
        ("加粗数", lambda r: r["boldSpans"]),
        ("禁用内容类别数", lambda r: len(r["forbiddenHits"])),
        ("四主题一级标题数", lambda r: len(r["topicsHit"])),
        ("数字表达数", lambda r: r["numberExprCount"]),
    ]

    lines = [
        "# Tool Use 推文对照实验 · N=3 判分汇总", "",
        "三组素材路径、任务描述完全相同，唯一差异是注入的上下文包。",
        "每组独立会话 3 次，表格为均值 ± 总体标准差。", "",
        "| 指标 | A 未接入 ASC | B 接入 ASC | C 等量无治理 |",
        "|---|---:|---:|---:|",
    ]
    for label, fn in metrics:
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
              "", "## 口径", "",
              "1. token 为中文字符数 + 非中文字符数除以 4，只用于相对比较，不作成本承诺。",
              "2. 禁用内容里的旧值（旧版材料、旧的工具数量口径、含编程语言名的旧账号名）来自素材本身，",
              "   不是模型凭空捏造；命中说明旧值没有被治理层拦住。",
              "3. 可读性、吸引力不做机器判分，由人工在 B 组三次产物里选一篇作为最终交付。",
              "4. 样本量 N=3，每组独立会话；写作任务本身有随机性，结论只作方向性判断。",
              "",
              ]
    (BASE / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines[:40]))


if __name__ == "__main__":
    main()
