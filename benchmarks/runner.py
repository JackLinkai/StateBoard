#!/usr/bin/env python
"""ASC 评测 runner：Baseline（原始历史全量堆砌）vs ASC（每轮注入状态卡）对比。

v0.7 新增真实 LLM 决策器：

- `--agent mock`：确定性规则解析器（v0.4 行为，默认），用于回归与离线验证；
- `--agent llm`：真实模型走 OpenAI Chat Completions 兼容接口，输出结构化决策
  （EXECUTE / REJECT / SKIP + query + citation + reason），由 runner 统一执行工具
  与判分，保证两组唯一变量仍是「Agent 每轮能看到什么」。

两组上下文：
    * Baseline：原始历史（用户输入 + 工具日志 + 干扰文本）按时间拼接，
      可按 `--window` 字符裁剪模拟注意力稀释，或按模型上下文窗口截断；
      输入 token 以模型返回的 usage 为准；
    * ASC：每轮拉取编译后的状态卡渲染注入，卡远小于窗口，硬约束始终可见、
      已完成待办不再出现、失效事实被明确标出。

指标：
    1. 关键约束违反率
    2. 任务完成率（多次重复报告均值 ± 标准差）
    3. 输入 / 输出 token 消耗
    4. 重复工具调用次数
    5. 跨会话恢复重放步数
    6. 过期事实引用率（引用已被覆盖的旧值）
    7. 真实计费成本（按 models.json 单价折算）
    8. 解析失败 / 参数漂移 / 误拒绝等审计项

运行（需先启动 asc-server，默认 http://localhost:8080）：
    python benchmarks/runner.py --agent mock
    python benchmarks/runner.py --agent llm --repeats 5 \
        --trace-dir benchmarks/results --json-out benchmarks/results/v07-l1.json

真实 LLM 的服务商在 benchmarks/providers.json 注册，切换只改配置或命令行：
    python benchmarks/runner.py --list-providers
    python benchmarks/runner.py --agent llm --provider ollama --model qwen2.5:7b

项目根目录 .env 中的常用配置（密钥不入库）：
    ASC_BENCH_PROVIDER=deepseek
    ASC_BENCH_LLM_API_KEY=...                       # DeepSeek / OpenAI 等
    ASC_BENCH_OLLAMA_BASE_URL=http://localhost:11434/v1   # 可选覆盖
    ASC_BENCH_DEEPSEEK_MODEL=deepseek-chat          # 可选覆盖

依赖：asc SDK（pip install -e sdk/python）、requests 与可访问的 asc-server。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "sdk" / "python"))

from asc import AscClient  # noqa: E402

from bench_config import (  # noqa: E402
    ModelSpec,
    load_dotenv,
    load_llm_settings,
    load_model_specs,
    load_provider_registry,
    resolve_model_spec,
)
from bench_llm import (  # noqa: E402
    SYSTEM_PROMPT,
    Decision,
    LlmAgent,
    LlmError,
    build_user_message,
    estimate_tokens,
    truncate_to_context,
)
from bench_scoring import (  # noqa: E402
    ScenarioMetrics,
    aggregate,
    aggregate_by_scenario,
    resolve_fact_key,
    tool_value,
)

BENCH_DIR = Path(__file__).resolve().parent

FACT_RE = re.compile(r"FACT (\S+) = (.+)")
CONSTRAINT_RE = re.compile(r"只允许查询最近(\d+)天")

FACT_LINE = "FACT {key} = {value}"
CONSTRAINT_LINE = "约束: {text}"


# ---------- 上下文渲染 ----------

def render_card(card: dict) -> str:
    """把状态卡 dict 渲染成注入文本（结构对齐四格语义）。"""
    out = ["【任务状态卡】"]
    constraints = card.get("hardConstraints", [])
    if constraints:
        out += [CONSTRAINT_LINE.format(text=c["text"]) for c in constraints]
    soft = card.get("softConstraints", [])
    if soft:
        out.append("【软约束】")
        out += [f"软约束: {c['text']}" for c in soft]
    facts = card.get("verifiedFacts", [])
    if facts:
        out.append("【已确认事实】")
        out += [FACT_LINE.format(key=f["key"], value=f["value"]) for f in facts]
    todos = card.get("openTodos", [])
    if todos:
        out.append("【待办】")
        out += [f"TODO {t['text']}" for t in todos]
    invalidated = card.get("invalidated", [])
    if invalidated:
        out.append("【已失效，不要再引用】")
        out += [
            f"INVALID {i['id']} {i['reason']}"
            + (f" supersededBy={i['supersededBy']}" if i.get("supersededBy") else "")
            for i in invalidated
        ]
    return "\n".join(out)


def visible_window(text: str, window: int) -> str:
    """模拟有限注意力：只保留最后 window 个字符（长上下文的信息稀释）。"""
    return text if len(text) <= window else text[-window:]


# ---------- Agent ----------

class MockAgent:
    """确定性规则解析器：从可见文本提取约束与事实，不调用模型。"""

    name = "mock"
    prompt_version = "mock"

    def decide(self, context: str, user_input: str, intent: dict) -> Decision:
        days = (intent.get("args") or {}).get("days")
        match = CONSTRAINT_RE.search(context)
        if match and days and days > int(match.group(1)):
            return Decision(action="REJECT", query=dict(intent.get("args") or {}))
        if not intent.get("force"):
            values = [v for k, v in FACT_RE.findall(context) if k == intent["fact_key"]]
            if values:
                return Decision(
                    action="SKIP",
                    citation={"key": intent["fact_key"], "value": values[-1]},
                )
        return Decision(action="EXECUTE", query=dict(intent.get("args") or {}))


# ---------- Trace ----------

class TraceWriter:
    """逐轮 trace 落盘：prompt 上下文、决策、usage、工具返回值。"""

    def __init__(self, trace_dir: str, run_id: str):
        self.path = Path(trace_dir) / run_id / "raw.jsonl"
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text("", encoding="utf-8")

    def write(self, record: dict) -> None:
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")


# ---------- 场景运行器 ----------

class BaselineRunner:
    """Baseline：原始历史全量堆砌，Agent 只有窗口 / 模型上下文限制。"""

    def __init__(self, sc: dict, agent, spec: ModelSpec, repeat: int,
                 window: int, context_window: int, max_tokens: int,
                 chars_per_token: float, trace: Optional[TraceWriter] = None):
        self.sc = sc
        self.agent = agent
        self.window = window
        self.context_window = context_window
        self.max_tokens = max_tokens
        self.chars_per_token = chars_per_token
        self.trace = trace
        self.group = "baseline"
        self.repeat = repeat
        self.metrics = ScenarioMetrics(sc, self.group, repeat, spec, chars_per_token)
        self.agent_name = getattr(agent, "name", "mock")
        self.history: list[str] = [
            CONSTRAINT_LINE.format(text=c["text"]) for c in sc.get("constraints", [])]
        self.call_counts: dict[str, int] = defaultdict(int)

    def run(self) -> ScenarioMetrics:
        resume_after = self.sc.get("restore_after_turn") or 10 ** 9
        for turn_no, turn in enumerate(self.sc["turns"], 1):
            intent = turn["intent"]
            user_input = turn.get("user_input") or intent.get("todo_text", "")
            user_line = f"用户: {user_input}"
            full_view = "\n".join(self.history + [user_line])
            prior_text = "\n".join(self.history)

            if self.agent_name == "mock":
                # 保持 v0.4 行为：MockAgent 的可见文本包含当前用户输入
                context = visible_window(full_view, self.window) if self.window > 0 else full_view
            elif self.window and self.window > 0:
                context = visible_window(prior_text, self.window)
            else:
                reserve = (estimate_tokens(SYSTEM_PROMPT, self.chars_per_token)
                           + estimate_tokens(user_input, self.chars_per_token))
                context = truncate_to_context(
                    prior_text, self.context_window, self.max_tokens,
                    self.chars_per_token, reserve_tokens=reserve)

            decision = self.agent.decide(context, user_input, intent)
            fact_key, value = self._execute(decision, intent)
            in_resume = turn_no > resume_after

            if self.agent_name == "mock":
                injected_text, full_text = full_view, full_view
            else:
                injected_text = build_user_message(context, user_input)
                full_text = SYSTEM_PROMPT + "\n" + build_user_message(
                    "\n".join(self.history), user_input)

            self.metrics.record_turn(
                turn_no, intent, decision, fact_key, value,
                injected_text, full_text, in_resume)
            self._trace(turn_no, user_input, context, decision, fact_key, value, in_resume)

            self.history.append(user_line)
            if decision.action == "EXECUTE":
                if value is not None:
                    self.history.append(FACT_LINE.format(key=fact_key, value=value))
                else:
                    self.history.append(f"[工具错误] {fact_key or '查询'} 无返回")
                self.history += turn.get("distractions", [])

        self.metrics.finish()
        return self.metrics

    def _execute(self, decision: Decision, intent: dict) -> tuple[Optional[str], Optional[str]]:
        if decision.action != "EXECUTE":
            return None, None
        fact_key = intent.get("fact_key")
        action = intent.get("action", "query_sales")
        nth = self.call_counts[fact_key]
        value = tool_value(self.sc, action, fact_key, nth)
        self.call_counts[fact_key] += 1
        return fact_key, value

    def _trace(self, turn_no: int, user_input: str, context: str,
               decision: Decision, fact_key: Optional[str],
               value: Optional[str], in_resume: bool) -> None:
        if not self.trace:
            return
        self.trace.write({
            "scenario": self.sc["scenario_id"], "group": self.group,
            "repeat": self.repeat, "turn": turn_no,
            "user_input": user_input, "context": context,
            "decision": decision.to_trace(),
            "tool_key": fact_key, "tool_value": value, "in_resume": in_resume,
        })


class AscRunner:
    """ASC：每轮决策前拉状态卡注入，工具返回后写回（同 key 自动 LATEST_WINS）。"""

    def __init__(self, sc: dict, client: AscClient, agent, spec: ModelSpec,
                 run_id: str, view: str, repeat: int, trace: Optional[TraceWriter] = None):
        self.sc = sc
        self.client = client
        self.agent = agent
        self.view = view
        self.trace = trace
        self.group = "asc"
        self.repeat = repeat
        self.metrics = ScenarioMetrics(sc, self.group, repeat, spec)
        self.agent_name = getattr(agent, "name", "mock")
        self.task_id = f"bench-{run_id}-{sc['scenario_id']}-asc-r{repeat}"
        self.call_counts: dict[str, int] = defaultdict(int)

    def run(self) -> ScenarioMetrics:
        self._ensure_task()
        for constraint in self.sc.get("constraints", []):
            self.client.add_constraint(self.task_id, constraint["text"], "HARD")

        resume_after = self.sc.get("restore_after_turn") or 10 ** 9
        done_todos: set[str] = set()
        for turn_no, turn in enumerate(self.sc["turns"], 1):
            if turn_no == resume_after + 1:
                # 跨会话恢复：快照旧任务，从其有效状态恢复出新任务继续
                self.client.snapshot(self.task_id)
                new_id = f"{self.task_id}-r2"
                self.client.restore_task(new_id, self.task_id)
                self.task_id = new_id

            intent = turn["intent"]
            user_input = turn.get("user_input") or intent.get("todo_text", "")
            card = self.client.get_state_card(self.task_id, view=self.view)
            context = render_card(card)

            decision = self.agent.decide(context, user_input, intent)
            fact_key, value = self._execute(decision, intent)
            in_resume = turn_no > resume_after

            if self.agent_name == "mock":
                injected_text, full_text = context, context
            else:
                injected_text = build_user_message(context, user_input)
                full_text = SYSTEM_PROMPT + "\n" + injected_text

            self.metrics.record_turn(
                turn_no, intent, decision, fact_key, value,
                injected_text, full_text, in_resume)
            self._trace(turn_no, user_input, context, decision, fact_key, value, in_resume)

            if decision.action == "EXECUTE" and value is not None:
                self.client.record_fact(
                    self.task_id, fact_key, value,
                    source=f"tool:{intent.get('action', 'query_sales')}")
                todo_text = intent.get("todo_text")
                if todo_text and todo_text not in done_todos:
                    # 待办：完成后立即流转 DONE，之后的状态卡里不再出现
                    done_todos.add(todo_text)
                    self.client.record_todo(self.task_id, todo_text, "user")
                    card = self.client.get_state_card(self.task_id)
                    for todo in card["openTodos"]:
                        if todo["text"] == todo_text:
                            self.client.update_todo(self.task_id, todo["id"], "DONE")

        self.metrics.finish()
        return self.metrics

    def _execute(self, decision: Decision, intent: dict) -> tuple[Optional[str], Optional[str]]:
        if decision.action != "EXECUTE":
            return None, None
        if self.agent_name == "mock":
            fact_key = intent.get("fact_key")
            action = intent.get("action", "query_sales")
        else:
            fact_key = resolve_fact_key(self.sc, decision.query)
            action = self.sc.get("default_tool", "query_sales")
        nth = self.call_counts[fact_key]
        value = tool_value(self.sc, action, fact_key, nth)
        if fact_key:
            self.call_counts[fact_key] += 1
        return fact_key, value

    def _ensure_task(self) -> None:
        try:
            self.client.create_task(self.task_id, f"评测场景 {self.sc['scenario_id']}")
        except Exception as exc:
            if "TASK_ALREADY_EXISTS" not in str(exc):
                raise

    def _trace(self, turn_no: int, user_input: str, context: str,
               decision: Decision, fact_key: Optional[str],
               value: Optional[str], in_resume: bool) -> None:
        if not self.trace:
            return
        self.trace.write({
            "scenario": self.sc["scenario_id"], "group": self.group,
            "repeat": self.repeat, "turn": turn_no,
            "user_input": user_input, "context": context,
            "decision": decision.to_trace(),
            "tool_key": fact_key, "tool_value": value, "in_resume": in_resume,
        })


# ---------- 场景加载与命令行 ----------

def load_scenarios(dataset_filter: Optional[str] = None) -> list[dict]:
    paths = sorted((BENCH_DIR / "datasets").glob("**/*.jsonl"))
    scenarios = []
    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                scenarios.append(json.loads(line))
    if dataset_filter:
        scenarios = [s for s in scenarios if dataset_filter in s["scenario_id"]]
    return scenarios


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="ASC 评测：Baseline vs ASC")
    parser.add_argument("--base-url", default="http://localhost:8080",
                        help="asc-server 地址")
    parser.add_argument("--dataset", default=None,
                        help="场景名过滤，如 constraint_erosion")
    parser.add_argument("--agent", choices=["mock", "llm"], default="mock",
                        help="决策器：mock（默认，确定性）或 llm（真实模型）")
    parser.add_argument("--provider", default=None,
                        help="模型服务商：deepseek / ollama / openai / qwen / custom，"
                             "默认取 ASC_BENCH_PROVIDER 或 providers.json")
    parser.add_argument("--list-providers", action="store_true",
                        help="列出 providers.json 中已注册的服务商后退出")
    parser.add_argument("--repeats", type=int, default=None,
                        help="每组重复次数；mock 默认 1，llm 默认 5")
    parser.add_argument("--window", type=int, default=None,
                        help="Baseline 字符窗口（模拟注意力稀释）；"
                             "mock 默认 350，llm 默认 0（只按模型上下文截断）")
    parser.add_argument("--context-window", type=int, default=None,
                        help="覆盖模型上下文窗口（token）")
    parser.add_argument("--chars-per-token", type=float, default=2.0,
                        help="中文 token 粗估比例，默认 2 字符/token")
    parser.add_argument("--view", choices=["full", "lean", "audit"], default="full",
                        help="ASC 状态卡权限视图")
    parser.add_argument("--model", default=None,
                        help="被测模型名，默认取 provider 注册表 / ASC_BENCH_<PROVIDER>_MODEL")
    parser.add_argument("--llm-base-url", default=None,
                        help="模型接口地址，默认取 provider 注册表 / ASC_BENCH_<PROVIDER>_BASE_URL")
    parser.add_argument("--temperature", type=float, default=0.0)
    parser.add_argument("--max-tokens", type=int, default=256)
    parser.add_argument("--llm-timeout", type=float, default=60.0)
    parser.add_argument("--retries", type=int, default=2,
                        help="模型调用 / JSON 解析失败重试次数")
    parser.add_argument("--run-id", default=uuid.uuid4().hex[:6],
                        help="任务 ID 隔离前缀")
    parser.add_argument("--trace-dir", default=None,
                        help="逐轮 trace 输出目录，写入 <dir>/<run_id>/raw.jsonl")
    parser.add_argument("--json-out", default=None, help="结果另存 JSON 文件")
    return parser.parse_args()


def _fmt_cost(summary: dict) -> str:
    if summary.get("cost_per_run") is None:
        return "n/a"
    return f"{summary['cost_per_run']:.6f} {summary.get('currency') or ''}".strip()


def main() -> None:
    args = parse_args()
    load_dotenv()

    if args.list_providers:
        registry = load_provider_registry()
        print(f"默认 provider：{registry.default_provider}")
        for name, spec in sorted(registry.providers.items()):
            key_note = "需要密钥" if spec.requires_api_key else "无需密钥"
            print(f"  {name:10s} {spec.api_style:14s} {spec.default_model:20s} "
                  f"{key_note}  {spec.base_url}")
        return

    scenarios = load_scenarios(args.dataset)
    if not scenarios:
        print("没有找到匹配的场景", file=sys.stderr)
        sys.exit(1)

    repeats = args.repeats if args.repeats is not None else (
        1 if args.agent == "mock" else 5)
    window = args.window if args.window is not None else (
        350 if args.agent == "mock" else 0)

    settings = None
    if args.agent == "llm":
        settings = load_llm_settings(
            provider=args.provider, model=args.model, base_url=args.llm_base_url,
            temperature=args.temperature, max_tokens=args.max_tokens,
            timeout=args.llm_timeout, retries=args.retries)
        if settings.requires_api_key and not settings.api_key:
            print(f"provider={settings.provider} 缺少模型密钥：请在 .env 中设置 "
                  f"ASC_BENCH_{settings.provider.upper().replace('-', '_')}_API_KEY"
                  "（或 ASC_BENCH_LLM_API_KEY）", file=sys.stderr)
            sys.exit(2)
        if not settings.base_url:
            print(f"provider={settings.provider} 缺少接口地址：请设置 "
                  f"ASC_BENCH_{settings.provider.upper().replace('-', '_')}_BASE_URL、"
                  "providers.json 的 base_url 或 --llm-base-url", file=sys.stderr)
            sys.exit(2)
        spec = resolve_model_spec(settings, load_model_specs(), args.context_window)
        try:
            agent = LlmAgent(settings, spec)
        except LlmError as exc:
            print(f"模型配置错误：{exc}", file=sys.stderr)
            sys.exit(2)
    else:
        spec = ModelSpec(name="mock", input_per_mtok=None, output_per_mtok=None)
        agent = MockAgent()

    context_window = args.context_window or spec.context_window
    trace = TraceWriter(args.trace_dir, args.run_id) if args.trace_dir else None

    provider_note = f", provider={settings.provider}" if settings else ""
    print(f"评测配置：agent={agent.name}{provider_note}, model={spec.name}, "
          f"prompt={agent.prompt_version}, repeats={repeats}, "
          f"window={window}, context={context_window}, view={args.view}")

    client = AscClient(args.base_url)
    rows: dict[str, list[dict]] = {"baseline": [], "asc": []}

    print(f"{'场景':40s} | {'组':8s} | {'次':3s} | 完成 | 违反 | 过期 | 重复 | "
          f"{'输入tok':8s} | 成本")
    print("-" * 108)
    for scenario in scenarios:
        for group in ("baseline", "asc"):
            for repeat in range(1, repeats + 1):
                if group == "baseline":
                    runner = BaselineRunner(
                        scenario, agent, spec, repeat, window,
                        context_window, args.max_tokens,
                        args.chars_per_token, trace)
                else:
                    runner = AscRunner(
                        scenario, client, agent, spec, args.run_id,
                        args.view, repeat, trace)
                row = runner.run().row()
                rows[group].append(row)
                cost = "n/a" if row["cost"] is None else f"{row['cost']:.6f}"
                print(f"{row['scenario']:40s} | {group:8s} | {repeat:3d} | "
                      f"{'是' if row['completed'] else '否':4s} | "
                      f"{row['violations']:4d} | {row['stale_citations']:4d} | "
                      f"{row['duplicates']:4d} | {row['avg_tokens']:8d} | {cost}")

    summary_b = aggregate(rows["baseline"])
    summary_a = aggregate(rows["asc"])
    print()
    print(f"汇总（{len(scenarios)} 个场景 × {repeats} 次，agent={agent.name}，"
          f"模型={spec.name}，窗口 {window} 字符）")
    print(f"{'指标':24s} | {'Baseline':>16s} | {'ASC':>16s}")
    print("-" * 62)
    metrics = [
        ("任务完成率", f"{summary_b['completion_rate']:.3f} ± "
                    f"{summary_b['completion_std']:.3f}",
         f"{summary_a['completion_rate']:.3f} ± "
         f"{summary_a['completion_std']:.3f}"),
        ("约束违反/次", summary_b["violation_rate"], summary_a["violation_rate"]),
        ("过期事实引用/次", summary_b["stale_citation_rate"],
         summary_a["stale_citation_rate"]),
        ("重复工具调用总数", summary_b["duplicates_total"],
         summary_a["duplicates_total"]),
        ("平均每轮输入 token", summary_b["avg_tokens"], summary_a["avg_tokens"]),
        ("平均每轮输出 token", summary_b["avg_output_tokens"],
         summary_a["avg_output_tokens"]),
        ("恢复重放步数总数", summary_b["resume_steps_total"],
         summary_a["resume_steps_total"]),
        ("解析失败", summary_b["parse_failures"], summary_a["parse_failures"]),
    ]
    for label, left, right in metrics:
        print(f"{label:24s} | {left:>16} | {right:>16}")
    if summary_b["cost_per_run"] is not None or summary_a["cost_per_run"] is not None:
        print(f"{'单次运行成本':24s} | {_fmt_cost(summary_b):>16} | "
              f"{_fmt_cost(summary_a):>16}")

    if args.json_out:
        payload = {
            "run_id": args.run_id,
            "agent": agent.name,
            "provider": settings.provider if settings else None,
            "api_style": settings.api_style if settings else None,
            "model": spec.name,
            "prompt_version": agent.prompt_version,
            "temperature": args.temperature if args.agent == "llm" else None,
            "repeats": repeats,
            "window": window,
            "context_window": context_window,
            "view": args.view,
            "chars_per_token": args.chars_per_token,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "rows": rows,
            "by_scenario": {
                "baseline": aggregate_by_scenario(rows["baseline"]),
                "asc": aggregate_by_scenario(rows["asc"]),
            },
            "summary": {"baseline": summary_b, "asc": summary_a},
        }
        out_path = Path(args.json_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n结果已写入 {out_path}")


if __name__ == "__main__":
    main()
