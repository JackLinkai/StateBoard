"""ASC × open_deep_research 对照实验。

两组唯一的差别是 supervisor 每轮能看到什么：
    native —— 上游原样，supervisor_messages 里累积全部研究结论
    asc    —— 每轮决策前注入 ASC 编译的状态卡，结论按 topic 写回

指标：
    1. 约束通过率（逐条判，任务级再算"全通过"）
    2. 输入 / 输出 token
    3. 研究单元数（ConductResearch 调用次数）
    4. 端到端耗时

用法：
    python examples/deep_research/run_compare.py --tasks examples/deep_research/tasks/tasks.jsonl --repeats 1 --limit 2
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "sdk" / "python"))
sys.path.insert(0, str(ROOT / "vendor" / "open_deep_research" / "src"))
sys.path.insert(0, str(HERE))

from langchain_core.callbacks import AsyncCallbackHandler  # noqa: E402
from langchain_core.messages import HumanMessage  # noqa: E402

import compat  # noqa: E402


class UsageTracker(AsyncCallbackHandler):
    """统计 token 与工具调用次数；挂在 config.callbacks 上，不侵入任何节点。"""

    def __init__(self) -> None:
        self.input_tokens = 0
        self.output_tokens = 0
        self.llm_calls = 0
        self.research_units = 0
        self.llm_errors = 0

    async def on_llm_end(self, response, **kwargs: Any) -> None:
        self.llm_calls += 1
        usage = (response.llm_output or {}).get("token_usage") or {}
        if usage:
            self.input_tokens += usage.get("prompt_tokens", 0) or 0
            self.output_tokens += usage.get("completion_tokens", 0) or 0
            return
        for gens in response.generations:
            for gen in gens:
                meta = getattr(getattr(gen, "message", None), "usage_metadata", None) or {}
                self.input_tokens += meta.get("input_tokens", 0) or 0
                self.output_tokens += meta.get("output_tokens", 0) or 0

    async def on_llm_error(self, error: BaseException, **kwargs: Any) -> None:
        self.llm_errors += 1

    async def on_tool_start(self, serialized: dict, input_str: str, **kwargs: Any) -> None:
        if (serialized or {}).get("name") == "ConductResearch":
            self.research_units += 1


def judge_constraint(report: str, rule: dict) -> bool:
    kind = rule.get("type", "all")
    values = rule.get("values") or ([rule["value"]] if rule.get("value") else [])
    if kind == "all":
        return all(v in report for v in values)
    if kind == "any":
        return any(v in report for v in values)
    if kind == "none":
        return all(v not in report for v in values)
    if kind == "regex":
        return all(re.search(v, report) is not None for v in values)
    raise ValueError(f"未知判定类型: {kind}")


def judge(report: str, task: dict) -> dict:
    rules = task.get("constraints") or []
    hits = [judge_constraint(report, r["check"]) for r in rules]
    return {
        "passed": sum(hits),
        "total": len(hits),
        "all_passed": all(hits) if hits else True,
        "detail": [
            {"text": r["text"], "ok": ok} for r, ok in zip(rules, hits)
        ],
    }


def load_tasks(path: Path, limit: Optional[int] = None) -> list[dict]:
    tasks = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.strip().startswith("#"):
            tasks.append(json.loads(line))
    return tasks[:limit] if limit else tasks


def prepare_asc_task(client, task_id: str, task: dict) -> None:
    try:
        client.create_task(task_id, task["query"][:400])
    except Exception:
        pass
    for rule in task.get("constraints") or []:
        try:
            client.add_constraint(task_id, rule["text"], "HARD")
        except Exception:
            pass


async def run_case(graph, task: dict, cfg: dict, timeout: float) -> dict:
    from asc import AscClient

    tracker = UsageTracker()
    run_cfg = {"configurable": cfg, "callbacks": [tracker]}
    started = time.perf_counter()
    error = None
    report = ""
    try:
        result = await asyncio.wait_for(
            graph.ainvoke({"messages": [HumanMessage(content=task["query"])]}, config=run_cfg),
            timeout=timeout,
        )
        report = result.get("final_report") or ""
    except asyncio.TimeoutError:
        error = f"timeout>{timeout}s"
    except Exception as exc:  # 单次失败不中断整轮实验
        error = f"{type(exc).__name__}: {exc}"[:400]
    elapsed = round(time.perf_counter() - started, 1)

    scored = judge(report, task) if report else {"passed": 0, "total": len(task.get("constraints") or []),
                                                 "all_passed": False, "detail": []}
    return {
        "task_id": task["task_id"],
        "error": error,
        "elapsed_s": elapsed,
        "input_tokens": tracker.input_tokens,
        "output_tokens": tracker.output_tokens,
        "llm_calls": tracker.llm_calls,
        "research_units": tracker.research_units,
        "report_chars": len(report),
        "report": report[:4000],
        **scored,
    }


def summarize(rows: list[dict]) -> dict:
    ok = [r for r in rows if not r["error"]]
    if not ok:
        return {"runs": len(rows), "errors": len(rows)}
    n = len(ok)
    return {
        "runs": len(rows),
        "errors": len(rows) - n,
        "constraint_pass_rate": round(sum(r["passed"] for r in ok) / max(1, sum(r["total"] for r in ok)), 3),
        "task_all_passed_rate": round(sum(1 for r in ok if r["all_passed"]) / n, 3),
        "avg_input_tokens": round(sum(r["input_tokens"] for r in ok) / n, 1),
        "avg_output_tokens": round(sum(r["output_tokens"] for r in ok) / n, 1),
        "avg_research_units": round(sum(r["research_units"] for r in ok) / n, 2),
        "avg_elapsed_s": round(sum(r["elapsed_s"] for r in ok) / n, 1),
    }


async def main_async(args: argparse.Namespace) -> None:
    model = compat.bootstrap(args.provider, args.model, args.api_key, args.base_url)

    # 搜索源：local 走固定语料（免费、可复现、A/B 唯一变量只剩 ASC）；
    # tavily 走真实检索（要 key，且每次返回内容不同，会引入额外方差）。
    if args.search == "local":
        from search_patch import corpus_stats, install_local_search
        install_local_search()
        stats = corpus_stats()
        print(f"检索：本地固定语料 {stats['total']} 条 {stats['by_kind']}")
        args.search_api = "none"   # 上游不构造 tavily 工具，patch 会补上 web_search
    else:
        compat.check_search_key()

    from open_deep_research.deep_researcher import deep_researcher  # 上游原生图
    from asc_nodes import build_asc_graph

    asc_graph = build_asc_graph()
    tasks = load_tasks(Path(args.tasks), args.limit)
    run_id = uuid.uuid4().hex[:6]
    groups = args.groups.split(",")

    rows: dict[str, list[dict]] = {g: [] for g in groups}
    print(f"模型 {model} ｜ 任务 {len(tasks)} ｜ 重复 {args.repeats} ｜ 组 {groups} ｜ run {run_id}")
    print(f"{'任务':10s} | {'组':7s} | {'耗时':>7s} | {'入tok':>7s} | {'出tok':>7s} | "
          f"{'研究单元':>8s} | 约束 | 错误")
    print("-" * 96)

    for task in tasks:
        for group in groups:
            for rep in range(1, args.repeats + 1):
                asc_task_id = f"dr-{run_id}-{task['task_id']}-r{rep}"
                if group == "asc":
                    cfg = compat.build_configurable(
                        model, asc_task_id=asc_task_id, asc_base_url=args.asc_url,
                        max_concurrent_research_units=args.max_units,
                        max_researcher_iterations=args.max_iterations,
                        max_react_tool_calls=args.max_react_calls,
                        search_api=args.search_api)
                    from asc import AscClient
                    prepare_asc_task(AscClient(args.asc_url, timeout=5.0), asc_task_id, task)
                    graph = asc_graph
                else:
                    cfg = compat.build_configurable(
                        model,
                        max_concurrent_research_units=args.max_units,
                        max_researcher_iterations=args.max_iterations,
                        max_react_tool_calls=args.max_react_calls,
                        search_api=args.search_api)
                    graph = deep_researcher

                row = await run_case(graph, task, cfg, args.timeout)
                rows[group].append(row)
                print(f"{task['task_id']:10s} | {group:7s} | {row['elapsed_s']:7.1f} | "
                      f"{row['input_tokens']:7d} | {row['output_tokens']:7d} | "
                      f"{row['research_units']:8d} | {row['passed']}/{row['total']} | "
                      f"{(row['error'] or '')[:34]}")

    print()
    print(f"{'指标':26s} | {'native':>14s} | {'asc':>14s}")
    print("-" * 62)
    sums = {g: summarize(rows[g]) for g in groups}
    labels = [
        ("约束通过率", "constraint_pass_rate"),
        ("任务全约束通过率", "task_all_passed_rate"),
        ("平均输入 token", "avg_input_tokens"),
        ("平均输出 token", "avg_output_tokens"),
        ("平均研究单元数", "avg_research_units"),
        ("平均耗时(s)", "avg_elapsed_s"),
    ]
    for label, key in labels:
        print(f"{label:26s} | {str(sums.get('native', {}).get(key, '-')):>14s} | "
              f"{str(sums.get('asc', {}).get(key, '-')):>14s}")
    if any(v.get("errors") for v in sums.values()):
        print(f"{'错误数':26s} | {str(sums.get('native', {}).get('errors', '-')):>14s} | "
              f"{str(sums.get('asc', {}).get('errors', '-')):>14s}")

    if args.out:
        payload = {
            "run_id": run_id,
            "model": model,
            "provider": args.provider,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "config": {
                "repeats": args.repeats, "search_api": args.search_api,
                "max_units": args.max_units, "max_iterations": args.max_iterations,
                "max_react_calls": args.max_react_calls,
            },
            "summary": sums,
            "rows": rows,
        }
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n结果已写入 {out}")


def main() -> None:
    p = argparse.ArgumentParser(description="ASC × open_deep_research 对照实验")
    p.add_argument("--tasks", default=str(HERE / "tasks" / "tasks.jsonl"))
    p.add_argument("--repeats", type=int, default=1)
    p.add_argument("--limit", type=int, default=None)
    p.add_argument("--groups", default="native,asc")
    p.add_argument("--provider", default=os.getenv("ASC_DR_PROVIDER", "deepseek"))
    p.add_argument("--model", default=None)
    p.add_argument("--api-key", default=None)
    p.add_argument("--base-url", default=None)
    p.add_argument("--asc-url", default=os.getenv("ASC_BASE_URL", "http://localhost:8080"))
    p.add_argument("--search", choices=["local", "tavily"], default="local",
                   help="local=固定语料检索（默认，免费可复现）；tavily=真实联网检索")
    p.add_argument("--search-api", default="tavily",
                   help="仅 --search tavily 时生效")
    p.add_argument("--max-units", type=int, default=2)
    p.add_argument("--max-iterations", type=int, default=3)
    p.add_argument("--max-react-calls", type=int, default=3)
    p.add_argument("--timeout", type=float, default=900.0)
    p.add_argument("--out", default=str(HERE / "results" / "compare.json"))
    asyncio.run(main_async(p.parse_args()))


if __name__ == "__main__":
    main()
