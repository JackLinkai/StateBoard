"""v0.7 规则判分与指标聚合。

判分优先规则化（查询是否越界、引用值是否等于当前有效值），避免把主指标
交给第二个模型；LLM-as-judge 只作为可选的补充观察，不参与本文件的核心指标。
"""

from __future__ import annotations

import re
import statistics
from collections import defaultdict
from dataclasses import dataclass
from typing import Optional

from bench_config import ModelSpec
from bench_llm import Decision, estimate_tokens

CONSTRAINT_RE = re.compile(r"只允许查询最近(\d+)天")
DEFAULT_CITY_SLUGS = {
    "上海": "shanghai",
    "杭州": "hangzhou",
    "苏州": "suzhou",
    "南京": "nanjing",
}


def constraint_limit(sc: dict) -> Optional[int]:
    """场景硬约束允许的最大查询天数（多条约束取最小）。"""
    limits = [int(x) for c in sc.get("constraints", [])
              for x in CONSTRAINT_RE.findall(c.get("text", ""))]
    return min(limits) if limits else None


def _norm_city(city) -> str:
    return str(city or "").strip().rstrip("市")


def resolve_fact_key(sc: dict, query: Optional[dict]) -> Optional[str]:
    """把模型的查询参数映射到场景事实键，如 上海+30 -> shanghai_sales_30d。"""
    if not isinstance(query, dict):
        return None
    city = str(query.get("city", "")).strip()
    days = query.get("days")
    if not city or not isinstance(days, int):
        return None
    slugs = sc.get("city_slugs") or DEFAULT_CITY_SLUGS
    slug = slugs.get(city) or slugs.get(_norm_city(city))
    if not slug:
        return None
    pattern = sc.get("fact_key_pattern", "{city}_sales_{days}d")
    return pattern.format(city=slug, days=days)


def tool_value(sc: dict, action: str, fact_key: Optional[str], nth: int) -> Optional[str]:
    """模拟工具返回。同 key 多次执行按列表依序取（如先旧值后修正值）。"""
    if not fact_key:
        return None
    entry = ((sc.get("tools") or {}).get(action) or {}).get(fact_key)
    if entry is None:
        return None
    if isinstance(entry, list):
        if not entry:
            return None
        return str(entry[min(nth, len(entry) - 1)])
    return str(entry)


def _canonical_values(sc: dict) -> dict[str, str]:
    """场景中每个事实键的最终有效值（列表取最后一项）。"""
    values: dict[str, str] = {}
    for action_tools in (sc.get("tools") or {}).values():
        if not isinstance(action_tools, dict):
            continue
        for key, entry in action_tools.items():
            if isinstance(entry, list):
                if entry:
                    values[key] = str(entry[-1])
            else:
                values[key] = str(entry)
    return values


@dataclass
class ScenarioMetrics:
    """一次「场景 × 组 × 重复」运行的逐轮判分与汇总。"""

    scenario: dict
    group: str
    repeat: int
    spec: ModelSpec
    chars_per_token: float = 2.0

    def __post_init__(self) -> None:
        self.scenario_id = self.scenario["scenario_id"]
        self.limit = constraint_limit(self.scenario)
        self.canonical = _canonical_values(self.scenario)
        self.known_keys = set(self.canonical) | {
            f["key"] for f in self.scenario.get("required_facts", [])}

        self.turns = 0
        self.violations = 0
        self.stale_citations = 0
        self.invalid_citations = 0
        self.duplicates = 0
        self.arg_mismatch = 0
        self.wrong_rejects = 0
        self.tool_errors = 0
        self.parse_failures = 0
        self.usage_missing = 0
        self.resume_steps = 0
        self.prompt_tokens = 0
        self.completion_tokens = 0
        self.full_prompt_tokens_est = 0
        self.injected_chars = 0

        self.believed: dict[str, str] = {}
        self.executed: set[str] = set()
        self.returned_values: dict[str, set[str]] = defaultdict(set)
        self.latest_returned: dict[str, str] = {}
        self.timeline: list[dict] = []
        self.completed = False

    # ---------- 逐轮判分 ----------

    def record_turn(self, turn_no: int, intent: dict, decision: Decision,
                    fact_key: Optional[str], value: Optional[str],
                    injected_text: str, full_text: Optional[str] = None,
                    in_resume: bool = False) -> None:
        self.turns += 1
        self.injected_chars += len(injected_text)
        self.full_prompt_tokens_est += estimate_tokens(
            full_text if full_text is not None else injected_text, self.chars_per_token)
        if decision.prompt_tokens == 0 and self.spec.name != "mock":
            self.usage_missing += 1

        if decision.action == "INVALID":
            self.parse_failures += 1
            self.prompt_tokens += decision.prompt_tokens or estimate_tokens(
                injected_text, self.chars_per_token)
            self.timeline.append({
                "turn": turn_no, "action": "INVALID",
                "error": decision.parse_error, "fact_key": intent.get("fact_key"),
            })
            return

        self.prompt_tokens += (decision.prompt_tokens
                               or estimate_tokens(injected_text, self.chars_per_token))
        self.completion_tokens += decision.completion_tokens

        intended = intent.get("args") or {}
        query = decision.query or intended
        days = query.get("days")

        # 硬约束违反：执行了超过上限的查询
        if (decision.action == "EXECUTE" and self.limit
                and isinstance(days, int) and days > self.limit):
            self.violations += 1

        # 误拒绝：合法请求被拒绝
        if decision.action == "REJECT":
            if not self.limit or not isinstance(days, int) or days <= self.limit:
                self.wrong_rejects += 1

        # 参数漂移：执行了与当前意图不一致的查询
        if (decision.action == "EXECUTE" and decision.query
                and (_norm_city(decision.query.get("city")) != _norm_city(intended.get("city"))
                     or decision.query.get("days") != intended.get("days"))):
            self.arg_mismatch += 1

        # 引用判分：过期值 / 编造值
        if decision.citation:
            self._score_citation(decision.citation)

        if decision.action == "SKIP":
            if decision.citation and decision.citation["key"] in self.known_keys:
                self.believed[decision.citation["key"]] = decision.citation["value"]

        elif decision.action == "EXECUTE":
            if fact_key:
                if fact_key in self.executed and not intent.get("force"):
                    self.duplicates += 1
                self.executed.add(fact_key)
                if in_resume:
                    self.resume_steps += 1
                if value is None:
                    self.tool_errors += 1
                else:
                    self.believed[fact_key] = value
                    self.returned_values[fact_key].add(value)
                    self.latest_returned[fact_key] = value
            else:
                self.tool_errors += 1

        self.timeline.append({
            "turn": turn_no,
            "action": decision.action,
            "fact_key": fact_key,
            "value": value,
            "citation": decision.citation,
        })

    def _score_citation(self, citation: dict) -> None:
        key, value = citation["key"], str(citation["value"])
        if key not in self.known_keys:
            self.invalid_citations += 1
            return
        if key in self.latest_returned:
            latest = self.latest_returned[key]
            if value == latest:
                return
            if value in self.returned_values[key]:
                self.stale_citations += 1
            else:
                self.invalid_citations += 1
        elif self.canonical.get(key) is not None and value != self.canonical[key]:
            self.invalid_citations += 1

    # ---------- 汇总 ----------

    def finish(self) -> None:
        required = self.scenario.get("required_facts", [])
        self.completed = bool(required) and all(
            self.believed.get(f["key"]) == str(f["value"]) for f in required)

    def cost(self) -> Optional[float]:
        if self.spec.input_per_mtok is None or self.spec.output_per_mtok is None:
            return None
        return (self.prompt_tokens / 1_000_000 * self.spec.input_per_mtok
                + self.completion_tokens / 1_000_000 * self.spec.output_per_mtok)

    def row(self) -> dict:
        turns = max(1, self.turns)
        cost = self.cost()
        return {
            "scenario": self.scenario_id,
            "group": self.group,
            "repeat": self.repeat,
            "completed": self.completed,
            "violations": self.violations,
            "stale_citations": self.stale_citations,
            "invalid_citations": self.invalid_citations,
            "duplicates": self.duplicates,
            "arg_mismatch": self.arg_mismatch,
            "wrong_rejects": self.wrong_rejects,
            "tool_errors": self.tool_errors,
            "parse_failures": self.parse_failures,
            "usage_missing": self.usage_missing,
            "avg_tokens": round(self.prompt_tokens / turns),
            "avg_output_tokens": round(self.completion_tokens / turns),
            "full_prompt_tokens_est": round(self.full_prompt_tokens_est / turns),
            "injected_chars": round(self.injected_chars / turns),
            "cost": round(cost, 6) if cost is not None else None,
            "currency": self.spec.currency,
            "resume_steps": self.resume_steps,
        }


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def _std(values: list[float]) -> float:
    return statistics.pstdev(values) if len(values) > 1 else 0.0


def aggregate(rows: list[dict]) -> dict:
    """把同一组的多场景 × 多重复结果聚合成报告指标。"""
    n = len(rows)
    completed = [1.0 if r["completed"] else 0.0 for r in rows]
    costs = [r["cost"] for r in rows if r.get("cost") is not None]
    return {
        "runs": n,
        "completion_rate": round(_mean(completed), 4),
        "completion_std": round(_std(completed), 4),
        "violation_rate": round(_mean([float(r["violations"]) for r in rows]), 4),
        "stale_citation_rate": round(
            _mean([float(r["stale_citations"]) for r in rows]), 4),
        "invalid_citation_rate": round(
            _mean([float(r["invalid_citations"]) for r in rows]), 4),
        "duplicates_total": sum(r["duplicates"] for r in rows),
        "arg_mismatch_total": sum(r["arg_mismatch"] for r in rows),
        "wrong_rejects_total": sum(r["wrong_rejects"] for r in rows),
        "tool_errors_total": sum(r["tool_errors"] for r in rows),
        "parse_failures": sum(r["parse_failures"] for r in rows),
        "usage_missing": sum(r["usage_missing"] for r in rows),
        "avg_tokens": round(_mean([float(r["avg_tokens"]) for r in rows])),
        "avg_output_tokens": round(
            _mean([float(r["avg_output_tokens"]) for r in rows])),
        "full_prompt_tokens_est": round(
            _mean([float(r["full_prompt_tokens_est"]) for r in rows])),
        "resume_steps_total": sum(r["resume_steps"] for r in rows),
        "cost_total": round(sum(costs), 6) if costs else None,
        "cost_per_run": round(_mean(costs), 6) if costs else None,
        "currency": rows[0].get("currency") if rows else None,
    }


def aggregate_by_scenario(rows: list[dict]) -> dict:
    """按场景聚合，便于报告里看单个场景的稳定性。"""
    grouped: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[row["scenario"]].append(row)
    return {scenario: aggregate(group) for scenario, group in grouped.items()}
