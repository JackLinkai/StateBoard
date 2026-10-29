#!/usr/bin/env python
"""ASC 评测 runner：Baseline（原始历史全量堆砌）vs ASC（每轮注入状态卡）对比。

评测思路（README「评测指标」章节的可执行实现）：

- 两个组跑同一份场景脚本，场景每轮给定用户意图（要查什么）和工具模拟返回；
- 差异只在"Agent 每轮能看到什么"：
    * Baseline：原始历史（用户输入 + 工具日志 + 干扰文本）按时间全量拼接，
      **输入 token 按全量计**（上下文膨胀是真实成本）；
      同时模拟有限注意力窗口（--window 字符，默认 600）：
      窗口外的早期约束 / 已完成进度"看不见"——对应长上下文的信息稀释与截断；
    * ASC：每轮拉取编译后的状态卡渲染注入。卡远小于窗口，永不截断：
      硬约束永远在最显眼的位置、已完成待办不再出现、失效事实被明确标出；
      输入 token 按卡文本计。
- MockAgent 是确定性规则解析器（无 LLM），从可见文本里提取约束 / 事实做决策，
  两组的行为差异完全由"注入内容的质量"驱动——这正是 ASC 要治理的东西。

指标（与 README 对齐）：
    1. 关键约束违反率：执行了与硬约束冲突的查询的次数 / 场景数
    2. 任务完成率：所需事实全部拿到且值正确的场景比例
    3. 输入 token 消耗：每轮注入文本的估算 token（按中文 ~2 字符/token 折算）
    4. 重复工具调用次数：同一目标事实被执行查询多于一次的次数
    5. 恢复任务重放步数：跨会话恢复后，为回到原进度执行的查询次数

运行（需先启动 asc-server，默认 http://localhost:8080）：
    python benchmarks/runner.py                     # 全部场景
    python benchmarks/runner.py --dataset constraint_erosion
    python benchmarks/runner.py --window 300 --run-id demo --json-out results.json

依赖：asc SDK（pip install -e sdk/python）与可访问的 asc-server。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "sdk" / "python"))

from asc import AscClient  # noqa: E402

# ---------- 渲染与解析（两组共用同一套格式，决策差异只来自内容本身） ----------

FACT_RE = re.compile(r"FACT (\S+) = (.+)")
CONSTRAINT_RE = re.compile(r"只允许查询最近(\d+)天")

FACT_LINE = "FACT {key} = {value}"
CONSTRAINT_LINE = "约束: {text}"


def render_card(card: dict) -> str:
    """把状态卡 dict 渲染成注入文本（结构对齐四格语义）。"""
    out = ["【任务状态卡】"]
    for c in card.get("hardConstraints", []):
        out.append(CONSTRAINT_LINE.format(text=c["text"]))
    for c in card.get("softConstraints", []):
        out.append(f"软约束: {c['text']}")
    facts = card.get("verifiedFacts", [])
    if facts:
        out.append("【已确认事实】")
        out += [FACT_LINE.format(key=f["key"], value=f["value"]) for f in facts]
    todos = card.get("openTodos", [])
    if todos:
        out.append("【待办】")
        out += [f"TODO {t['text']}" for t in todos]
    inv = card.get("invalidated", [])
    if inv:
        out.append("【已失效，不要再引用】")
        out += [
            f"INVALID {i['id']} {i['reason']}"
            + (f" supersededBy={i['supersededBy']}" if i.get("supersededBy") else "")
            for i in inv
        ]
    return "\n".join(out)


def visible_window(text: str, window: int) -> str:
    """模拟有限注意力：只保留最后 window 个字符（长上下文的信息稀释）。"""
    return text if len(text) <= window else text[-window:]


def tool_value(sc: dict, action: str, fact_key: str, nth: int) -> str:
    """工具模拟返回值。同 key 多次执行按列表依序取（如先旧值后修正值），超出取最后一个。"""
    entry = sc["tools"][action][fact_key]
    if isinstance(entry, list):
        return entry[min(nth, len(entry) - 1)]
    return entry


def decide(view: str, intent: dict) -> tuple[str, str | None]:
    """MockAgent 决策：从可见文本提取约束与事实，返回 (动作, 记忆中的值)。

    动作：
        REJECT_CONFLICT —— 可见约束禁止本次查询（正确拒绝）
        SKIP_DONE       —— 目标事实已确认，无需重查（正确跳过）
        EXECUTE         —— 执行查询
    intent.force 为 true（用户明确要求重查 / 重新核对）时不做 SKIP 判定。
    """
    days = intent.get("args", {}).get("days")
    m = CONSTRAINT_RE.search(view)
    if m and days and days > int(m.group(1)):
        return "REJECT_CONFLICT", None
    if not intent.get("force"):
        vals = [v for k, v in FACT_RE.findall(view) if k == intent["fact_key"]]
        if vals:
            return "SKIP_DONE", vals[-1]
    return "EXECUTE", None


# ---------- 场景运行器 ----------


class BaselineRunner:
    """Baseline：原始历史全量堆砌，Agent 只有 window 大小的注意力窗口。"""

    def __init__(self, sc: dict, window: int):
        self.sc = sc
        self.window = window
        self.history: list[str] = []

    def run(self) -> "Metrics":
        m = Metrics(self.sc)
        resume_after = self.sc.get("restore_after_turn") or 10 ** 9
        for turn_no, turn in enumerate(self.sc["turns"], 1):
            intent = turn["intent"]
            self.history.append(f"用户: {turn.get('user_input', intent.get('todo_text', ''))}")
            if len(m.timeline) == 0:  # 约束只在开场声明一次
                self.history += [CONSTRAINT_LINE.format(text=c["text"])
                                 for c in self.sc.get("constraints", [])]

            full_view = "\n".join(self.history)
            # Baseline 的"恢复"：新会话只拿历史文本继续，窗口截断生效
            action, believed = decide(visible_window(full_view, self.window), intent)
            value = tool_value(self.sc, intent["action"], intent["fact_key"],
                               sum(1 for t in m.timeline if t["fact_key"] == intent["fact_key"]))
            m.record_turn(full_view, action, intent, believed, value,
                          in_resume=turn_no > resume_after)
            if action == "EXECUTE":
                self.history.append(FACT_LINE.format(key=intent["fact_key"], value=value))
                self.history += turn.get("distractions", [])
        m.finish()
        return m


class AscRunner:
    """ASC：每轮决策前拉状态卡注入，工具返回后写回（同 key 自动 LATEST_WINS）。"""

    def __init__(self, sc: dict, client: AscClient, run_id: str):
        self.sc = sc
        self.client = client
        self.task_id = f"bench-{run_id}-{sc['scenario_id']}-asc"

    def run(self) -> "Metrics":
        m = Metrics(self.sc)
        self._ensure_task()
        for c in self.sc.get("constraints", []):
            self.client.add_constraint(self.task_id, c["text"], "HARD")

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
            view = render_card(self.client.get_state_card(self.task_id))
            action, believed = decide(view, intent)
            value = tool_value(self.sc, intent["action"], intent["fact_key"],
                               sum(1 for t in m.timeline if t["fact_key"] == intent["fact_key"]))
            m.record_turn(view, action, intent, believed, value,
                          in_resume=turn_no > resume_after)

            if action == "EXECUTE":
                self.client.record_fact(self.task_id, intent["fact_key"], value,
                                        source=f"tool:{intent['action']}")
                todo_text = intent.get("todo_text")
                if todo_text and todo_text not in done_todos:
                    # 待办：完成后立即流转 DONE，之后的状态卡里不再出现
                    done_todos.add(todo_text)
                    self.client.record_todo(self.task_id, todo_text, "user")
                    card = self.client.get_state_card(self.task_id)
                    for t in card["openTodos"]:
                        if t["text"] == todo_text:
                            self.client.update_todo(self.task_id, t["id"], "DONE")
        m.finish()
        return m

    def _ensure_task(self) -> None:
        try:
            self.client.create_task(self.task_id, f"评测场景 {self.sc['scenario_id']}")
        except Exception as e:
            if "TASK_ALREADY_EXISTS" not in str(e):
                raise


# ---------- 指标 ----------


class Metrics:
    def __init__(self, sc: dict):
        self.sc = sc
        self.scenario_id = sc["scenario_id"]
        self.violations = 0
        self.duplicates = 0
        self.resume_steps = 0
        self.token_total = 0
        self.turns = 0
        self.believed: dict[str, str] = {}   # Agent 认为的当前值（按 key）
        self.executed: set[str] = set()
        self.timeline: list[dict] = []

    def record_turn(self, full_view: str, action: str, intent: dict,
                    believed: str | None, value: str, in_resume: bool = False) -> None:
        """记录一轮：token 按全量注入文本计，决策按可见窗口计。"""
        self.turns += 1
        self.token_total += len(full_view) // 2  # 中文约 2 字符 1 token（估算，供对比）

        days = intent.get("args", {}).get("days")
        limit = self._constraint_limit()
        violated = action == "EXECUTE" and days and limit and days > limit
        if violated:
            self.violations += 1

        if action == "SKIP_DONE":
            if believed is not None:
                self.believed[intent["fact_key"]] = believed
        elif action == "EXECUTE":
            if intent["fact_key"] in self.executed and not intent.get("force"):
                self.duplicates += 1  # force（用户明确要求重查）不计浪费
            self.executed.add(intent["fact_key"])
            if in_resume:
                self.resume_steps += 1
            self.believed[intent["fact_key"]] = value   # 执行后引用返回值
        # REJECT_CONFLICT：正确拒绝，无副作用

        self.timeline.append({"fact_key": intent["fact_key"], "action": action})

    def _constraint_limit(self) -> int | None:
        limits = [int(x) for c in self.sc.get("constraints", [])
                  for x in CONSTRAINT_RE.findall(c["text"])]
        return min(limits) if limits else None

    def finish(self) -> None:
        req = self.sc.get("required_facts", [])
        self.completed = bool(req) and all(
            self.believed.get(f["key"]) == f["value"] for f in req)

    def row(self) -> dict:
        return {
            "scenario": self.scenario_id,
            "completed": self.completed,
            "violations": self.violations,
            "duplicates": self.duplicates,
            "avg_tokens": round(self.token_total / max(1, self.turns)),
            "resume_steps": self.resume_steps,
        }


# ---------- 汇总输出 ----------

def summarize(rows: list[dict]) -> dict:
    n = max(1, len(rows))
    return {
        "completion_rate": sum(1 for r in rows if r["completed"]) / n,
        "violation_rate": sum(r["violations"] for r in rows) / n,
        "duplicates_total": sum(r["duplicates"] for r in rows),
        "avg_tokens": round(sum(r["avg_tokens"] for r in rows) / n),
        "resume_steps_total": sum(r["resume_steps"] for r in rows),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="ASC 评测：Baseline vs ASC")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--dataset", default=None, help="场景名过滤，如 constraint_erosion")
    parser.add_argument("--window", type=int, default=350,
                        help="Baseline 注意力窗口（字符），模拟长上下文信息稀释")
    parser.add_argument("--run-id", default=uuid.uuid4().hex[:6], help="任务 ID 隔离前缀")
    parser.add_argument("--json-out", default=None, help="结果另存 JSON 文件")
    args = parser.parse_args()

    datasets = sorted((Path(__file__).parent / "datasets").glob("*.jsonl"))
    scenarios = []
    for path in datasets:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                scenarios.append(json.loads(line))
    if args.dataset:
        scenarios = [s for s in scenarios if args.dataset in s["scenario_id"]]
    if not scenarios:
        print("没有找到匹配的场景", file=sys.stderr)
        sys.exit(1)

    client = AscClient(args.base_url)
    rows = {"baseline": [], "asc": []}

    print(f"{'场景':40s} | {'组':8s} | 完成 | 违反 | 重复 | 平均token | 恢复步数")
    print("-" * 88)
    for sc in scenarios:
        for group, runner in [("baseline", BaselineRunner(sc, args.window)),
                              ("asc", AscRunner(sc, client, args.run_id))]:
            r = runner.run().row()
            rows[group].append(r)
            print(f"{r['scenario']:40s} | {group:8s} | "
                  f"{'是' if r['completed'] else '否':4s} | {r['violations']:4d} | "
                  f"{r['duplicates']:4d} | {r['avg_tokens']:9d} | {r['resume_steps']:6d}")

    s_b, s_a = summarize(rows["baseline"]), summarize(rows["asc"])
    print()
    print(f"汇总（{len(scenarios)} 个场景，窗口 {args.window} 字符）")
    print(f"{'指标':22s} | {'Baseline':>10s} | {'ASC':>10s}")
    print("-" * 52)
    for key, label in [("completion_rate", "任务完成率"),
                       ("violation_rate", "约束违反率/场景"),
                       ("duplicates_total", "重复工具调用总数"),
                       ("avg_tokens", "平均每轮输入 token"),
                       ("resume_steps_total", "恢复重放步数总数")]:
        print(f"{label:22s} | {s_b[key]:>10} | {s_a[key]:>10}")

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(
            {"run_id": args.run_id, "window": args.window,
             "rows": rows, "summary": {"baseline": s_b, "asc": s_a}},
            ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n结果已写入 {args.json_out}")


if __name__ == "__main__":
    main()
