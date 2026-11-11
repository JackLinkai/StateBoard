"""v0.7 评测模块离线单测（不访问 asc-server / 真实模型）。"""

from __future__ import annotations

import json
import os
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bench_config import (  # noqa: E402
    LlmSettings,
    ModelSpec,
    load_llm_settings,
    load_provider_registry,
)
from bench_llm import (  # noqa: E402
    Decision,
    LlmAgent,
    estimate_tokens,
    parse_decision,
    truncate_to_context,
)
from bench_scoring import (  # noqa: E402
    ScenarioMetrics,
    aggregate,
    resolve_fact_key,
    tool_value,
)

SCENARIO = {
    "scenario_id": "unit_scenario",
    "constraints": [{"text": "只允许查询最近30天数据"}],
    "tools": {"query_sales": {"shanghai_sales_30d": ["100", "120"]}},
    "required_facts": [{"key": "shanghai_sales_30d", "value": "120"}],
}
INTENT = {
    "action": "query_sales",
    "args": {"city": "上海", "days": 30},
    "fact_key": "shanghai_sales_30d",
}


def _record(metrics: ScenarioMetrics, turn: int, action: str,
            value: str | None = None, citation: dict | None = None,
            force: bool = False, prompt_tokens: int = 10,
            completion_tokens: int = 2) -> None:
    decision = Decision(
        action=action,
        query=dict(INTENT["args"]) if action == "EXECUTE" else None,
        citation=citation,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
    )
    intent = dict(INTENT, force=force)
    fact_key = "shanghai_sales_30d" if action == "EXECUTE" else None
    metrics.record_turn(turn, intent, decision, fact_key, value, "ctx")


class ParseDecisionTest(unittest.TestCase):
    def test_plain_execute(self) -> None:
        decision = parse_decision(json.dumps({
            "action": "EXECUTE", "query": {"city": "上海", "days": 30},
            "citation": None, "reason": "需要查询",
        }))
        self.assertEqual(decision.action, "EXECUTE")
        self.assertEqual(decision.query, {"city": "上海", "days": 30})

    def test_fenced_json(self) -> None:
        text = '```json\n{"action":"SKIP","citation":{"key":"k","value":"v"}}\n```'
        decision = parse_decision(text)
        self.assertEqual(decision.action, "SKIP")
        self.assertEqual(decision.citation, {"key": "k", "value": "v"})

    def test_reject_keeps_query(self) -> None:
        decision = parse_decision(
            '{"action":"REJECT","query":{"city":"上海","days":90}}')
        self.assertEqual(decision.action, "REJECT")
        self.assertEqual(decision.query, {"city": "上海", "days": 90})

    def test_invalid_action(self) -> None:
        with self.assertRaises(ValueError):
            parse_decision('{"action":"QUERY"}')

    def test_execute_requires_query(self) -> None:
        with self.assertRaises(ValueError):
            parse_decision('{"action":"EXECUTE"}')


class ToolResolutionTest(unittest.TestCase):
    def test_resolve_fact_key(self) -> None:
        self.assertEqual(
            resolve_fact_key(SCENARIO, {"city": "上海市", "days": 30}),
            "shanghai_sales_30d")
        self.assertIsNone(resolve_fact_key(SCENARIO, {"city": "广州", "days": 30}))

    def test_tool_value_sequence(self) -> None:
        self.assertEqual(tool_value(SCENARIO, "query_sales", "shanghai_sales_30d", 0), "100")
        self.assertEqual(tool_value(SCENARIO, "query_sales", "shanghai_sales_30d", 1), "120")
        self.assertEqual(tool_value(SCENARIO, "query_sales", "shanghai_sales_30d", 9), "120")
        self.assertIsNone(tool_value(SCENARIO, "query_sales", "missing", 0))


class ScoringTest(unittest.TestCase):
    def test_stale_citation_and_completion(self) -> None:
        spec = ModelSpec(name="test", input_per_mtok=1.0, output_per_mtok=2.0)
        metrics = ScenarioMetrics(SCENARIO, "asc", 1, spec)

        _record(metrics, 1, "EXECUTE", "100")
        _record(metrics, 2, "EXECUTE", "120", force=True)
        _record(metrics, 3, "SKIP", citation={"key": "shanghai_sales_30d", "value": "100"})
        metrics.finish()
        self.assertEqual(metrics.stale_citations, 1)
        self.assertFalse(metrics.completed)

        _record(metrics, 4, "SKIP", citation={"key": "shanghai_sales_30d", "value": "120"})
        metrics.finish()
        self.assertTrue(metrics.completed)
        self.assertEqual(metrics.duplicates, 0)

    def test_violation_and_wrong_reject(self) -> None:
        spec = ModelSpec(name="test")
        metrics = ScenarioMetrics(SCENARIO, "baseline", 1, spec)
        decision = Decision(action="EXECUTE", query={"city": "上海", "days": 90})
        metrics.record_turn(1, INTENT, decision, "shanghai_sales_90d", None, "ctx")
        self.assertEqual(metrics.violations, 1)

        wrong = Decision(action="REJECT", query={"city": "上海", "days": 30})
        metrics.record_turn(2, INTENT, wrong, None, None, "ctx")
        self.assertEqual(metrics.wrong_rejects, 1)

    def test_cost_and_aggregate(self) -> None:
        spec = ModelSpec(name="test", input_per_mtok=1.0, output_per_mtok=2.0)
        metrics = ScenarioMetrics(SCENARIO, "asc", 1, spec)
        _record(metrics, 1, "EXECUTE", "120",
                prompt_tokens=1_000_000, completion_tokens=500_000)
        metrics.finish()
        self.assertAlmostEqual(metrics.cost(), 2.0, places=6)

        row = metrics.row()
        summary = aggregate([row, dict(row, completed=False)])
        self.assertEqual(summary["runs"], 2)
        self.assertAlmostEqual(summary["completion_rate"], 0.5)
        self.assertAlmostEqual(summary["completion_std"], 0.5)


class ContextTest(unittest.TestCase):
    def test_estimate_tokens_floor(self) -> None:
        self.assertEqual(estimate_tokens(""), 0)
        self.assertEqual(estimate_tokens("12345"), 2)

    def test_truncate_keeps_tail(self) -> None:
        text = "A" * 1000
        result = truncate_to_context(text, context_window=100, max_tokens=10,
                                     chars_per_token=1.0)
        self.assertEqual(len(result), 90)
        self.assertTrue(result.endswith("A"))


class ProviderConfigTest(unittest.TestCase):
    def test_registry_contains_ollama(self) -> None:
        registry = load_provider_registry()
        self.assertIn("ollama", registry.providers)
        self.assertFalse(registry.providers["ollama"].requires_api_key)
        self.assertEqual(registry.providers["ollama"].api_style, "openai_chat")

    def test_ollama_settings_no_key(self) -> None:
        with patch.dict(os.environ, {
            "ASC_BENCH_OLLAMA_BASE_URL": "",
            "ASC_BENCH_OLLAMA_MODEL": "",
        }, clear=False):
            settings = load_llm_settings(provider="ollama")
        self.assertFalse(settings.requires_api_key)
        self.assertEqual(settings.model, "qwen2.5:7b")
        self.assertEqual(
            settings.chat_url(), "http://localhost:11434/v1/chat/completions")

    def test_custom_uses_generic_env(self) -> None:
        with patch.dict(os.environ, {
            "ASC_BENCH_LLM_BASE_URL": "http://localhost:9999/v1",
            "ASC_BENCH_LLM_API_KEY": "custom-key",
            "ASC_BENCH_MODEL": "my-model",
        }, clear=False):
            settings = load_llm_settings(provider="custom")
        self.assertEqual(settings.base_url, "http://localhost:9999/v1")
        self.assertEqual(settings.model, "my-model")
        self.assertEqual(settings.api_key, "custom-key")

    def test_chat_url_override(self) -> None:
        settings = LlmSettings(
            model="x", base_url="http://host", api_key="",
            chat_url_override="http://host/custom/chat/completions")
        self.assertEqual(
            settings.chat_url(), "http://host/custom/chat/completions")


class _FakeHandler(BaseHTTPRequestHandler):
    requests_count = 0
    last_authorization = "unset"

    def do_POST(self) -> None:  # noqa: N802
        type(self).requests_count += 1
        type(self).last_authorization = self.headers.get("Authorization")
        length = int(self.headers.get("Content-Length", 0))
        payload = json.loads(self.rfile.read(length))
        if payload.get("response_format"):
            body = b'{"error":"response_format is not supported"}'
            self.send_response(400)
        else:
            content = json.dumps({
                "action": "EXECUTE",
                "query": {"city": "上海", "days": 30},
                "citation": None,
                "reason": "需要查询",
            }, ensure_ascii=False)
            body = json.dumps({
                "choices": [{"message": {"content": content}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 42, "completion_tokens": 7},
            }).encode("utf-8")
            self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args) -> None:
        pass


class LlmAgentTest(unittest.TestCase):
    def _serve(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), _FakeHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return server

    def test_json_mode_fallback_and_usage(self) -> None:
        _FakeHandler.requests_count = 0
        _FakeHandler.last_authorization = "unset"
        server = self._serve()
        try:
            settings = LlmSettings(
                model="fake-model",
                base_url=f"http://127.0.0.1:{server.server_address[1]}/v1",
                api_key="test-key", retries=0)
            agent = LlmAgent(settings, ModelSpec(name="fake-model"))
            decision = agent.decide("约束: 只允许查询最近30天数据", "查上海最近30天", INTENT)
            self.assertEqual(decision.action, "EXECUTE")
            self.assertEqual(decision.prompt_tokens, 42)
            self.assertEqual(decision.completion_tokens, 7)
            self.assertGreaterEqual(_FakeHandler.requests_count, 2)
            self.assertEqual(_FakeHandler.last_authorization, "Bearer test-key")
        finally:
            server.shutdown()
            server.server_close()

    def test_ollama_without_api_key(self) -> None:
        _FakeHandler.requests_count = 0
        _FakeHandler.last_authorization = "unset"
        server = self._serve()
        try:
            settings = LlmSettings(
                model="qwen2.5:7b",
                base_url=f"http://127.0.0.1:{server.server_address[1]}/v1",
                api_key="", retries=0, provider="ollama",
                requires_api_key=False)
            agent = LlmAgent(settings, ModelSpec(name="qwen2.5:7b"))
            decision = agent.decide("约束: 只允许查询最近30天数据", "查上海最近30天", INTENT)
            self.assertEqual(decision.action, "EXECUTE")
            self.assertIsNone(_FakeHandler.last_authorization)
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
