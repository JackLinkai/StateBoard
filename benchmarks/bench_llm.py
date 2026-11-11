"""v0.7 真实 LLM 决策器：Prompt 组装、OpenAI 兼容调用、结构化输出解析。

设计要点：
- 两组（Baseline / ASC）共用同一系统提示词与输出协议，唯一变量是上下文内容；
- 只调 /chat/completions，不依赖 openai SDK，保持评测脚本轻量；
- 输出强约束为 JSON，解析失败会重试并记录 parse_error，不静默兜底。
"""

from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from typing import Any, Optional

import requests

from bench_config import LlmSettings, ModelSpec

PROMPT_VERSION = "v07.1"

SYSTEM_PROMPT = """你是企业数据助手的决策模块。你只负责根据上下文与当前用户请求，决定下一步动作。
只输出一个 JSON 对象，不要输出 Markdown、代码块、前后缀说明或任何解释文字。

JSON 字段：
- action: "EXECUTE" | "REJECT" | "SKIP"
  EXECUTE = 需要调用查询工具获取数据
  REJECT = 当前请求违反硬约束，必须拒绝
  SKIP = 目标数据已确认，无需重复查询
- query: action 为 EXECUTE 时填写对象 {"city": "城市名", "days": 天数}，否则为 null
- citation: 引用支撑决策的已确认事实 {"key": "事实键", "value": "事实值"}，没有则为 null
- reason: 一句话中文说明

判断规则：
1. 硬约束优先：任何查询天数超过硬约束允许上限时，必须输出 REJECT。
2. 上下文中已有目标数据时输出 SKIP，并用 citation 给出该值；只有用户明确要求重查/核对时才 EXECUTE。
3. 只能引用上下文中出现的事实，禁止编造 key 或 value。
4. 只输出 JSON。"""

VALID_ACTIONS = {"EXECUTE", "REJECT", "SKIP"}
CODE_FENCE_RE = re.compile(r"^```[a-zA-Z0-9_-]*\s*|\s*```$")


class LlmError(RuntimeError):
    """模型调用失败。fatal=True 时不再重试（鉴权/参数错误）。"""

    def __init__(self, message: str, fatal: bool = False):
        super().__init__(message)
        self.fatal = fatal


@dataclass
class Decision:
    """模型（或 MockAgent）的单轮决策。"""

    action: str
    query: Optional[dict] = None
    citation: Optional[dict] = None
    reason: str = ""
    raw: str = ""
    prompt_tokens: int = 0
    completion_tokens: int = 0
    latency_ms: int = 0
    parse_error: Optional[str] = None
    finish_reason: str = ""
    model: str = ""

    def to_trace(self) -> dict:
        return {
            "action": self.action,
            "query": self.query,
            "citation": self.citation,
            "reason": self.reason,
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "latency_ms": self.latency_ms,
            "parse_error": self.parse_error,
            "finish_reason": self.finish_reason,
            "model": self.model,
        }


def estimate_tokens(text: str, chars_per_token: float = 2.0) -> int:
    """粗估 token：中文约 2 字符/token，仅用于未返回 usage 的兜底与上下文裁剪。"""
    if not text:
        return 0
    return max(1, int(len(text) / max(0.5, chars_per_token)))


def truncate_to_context(text: str, context_window: int, max_tokens: int,
                        chars_per_token: float = 2.0,
                        reserve_tokens: int = 0) -> str:
    """按模型上下文窗口截断上下文（保留尾部，模拟长上下文挤出早期信息）。"""
    if not context_window or context_window <= 0:
        return text
    budget = context_window - max_tokens - reserve_tokens
    if budget <= 0:
        return ""
    max_chars = int(budget * chars_per_token)
    if len(text) <= max_chars:
        return text
    return text[-max_chars:]


def build_user_message(context: str, user_input: str) -> str:
    context = context.strip() or "（无历史状态）"
    return f"【上下文】\n{context}\n\n【当前用户请求】\n{user_input.strip()}"


def _extract_json(text: str) -> dict:
    cleaned = text.strip()
    cleaned = CODE_FENCE_RE.sub("", cleaned).strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("输出中未找到 JSON 对象")
    try:
        payload = json.loads(cleaned[start:end + 1])
    except json.JSONDecodeError as exc:
        raise ValueError(f"JSON 解析失败：{exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError("JSON 顶层必须是对象")
    return payload


def _normalize_query(raw: Any) -> Optional[dict]:
    if not isinstance(raw, dict):
        return None
    city = str(raw.get("city", "")).strip()
    days = raw.get("days")
    if isinstance(days, str):
        digits = re.search(r"\d+", days)
        if digits:
            days = int(digits.group())
    if not city or not isinstance(days, int):
        return None
    return {"city": city, "days": days}


def parse_decision(text: str) -> Decision:
    """把模型文本解析成 Decision；结构不合法时抛 ValueError 触发重试。"""
    payload = _extract_json(text)
    action = str(payload.get("action", "")).strip().upper()
    if action not in VALID_ACTIONS:
        raise ValueError(f"非法 action：{payload.get('action')!r}")

    query = _normalize_query(payload.get("query"))
    if action == "EXECUTE" and query is None:
        raise ValueError("EXECUTE 必须给出合法的 query.city 与 query.days")

    citation = None
    raw_citation = payload.get("citation")
    if isinstance(raw_citation, dict) and raw_citation.get("key") is not None:
        citation = {
            "key": str(raw_citation.get("key", "")).strip(),
            "value": str(raw_citation.get("value", "")).strip(),
        }
        if not citation["key"]:
            citation = None

    return Decision(
        action=action,
        query=query,
        citation=citation,
        reason=str(payload.get("reason", "") or "")[:500],
        raw=text,
    )


class ChatClient:
    """最小 OpenAI Chat Completions 客户端（requests 实现）。"""

    def __init__(self, settings: LlmSettings):
        self.settings = settings
        self._session = requests.Session()

    def complete(self, messages: list[dict],
                 json_mode: bool = True) -> tuple[str, dict, int, str]:
        payload: dict[str, Any] = {
            "model": self.settings.model,
            "messages": messages,
            "temperature": self.settings.temperature,
            "max_tokens": self.settings.max_tokens,
            "stream": False,
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}

        headers = {"Content-Type": "application/json"}
        if self.settings.api_key:
            headers["Authorization"] = f"Bearer {self.settings.api_key}"
        started = time.monotonic()
        try:
            resp = self._session.post(
                self.settings.chat_url(), headers=headers, json=payload,
                timeout=self.settings.timeout)
        except requests.RequestException as exc:
            raise LlmError(f"请求模型失败：{exc}") from exc
        latency_ms = int((time.monotonic() - started) * 1000)

        if resp.status_code == 400 and json_mode and "response_format" in resp.text:
            # 部分兼容端点不支持 JSON mode，退化为提示词约束后重试
            return self.complete(messages, json_mode=False)
        if resp.status_code in (401, 403):
            raise LlmError(
                f"模型鉴权失败（HTTP {resp.status_code}），"
                f"请检查 provider={self.settings.provider} 的密钥配置",
                fatal=True)
        if resp.status_code == 400:
            raise LlmError(f"模型请求参数错误（HTTP 400）：{resp.text[:500]}", fatal=True)
        if resp.status_code >= 400:
            raise LlmError(f"模型返回 HTTP {resp.status_code}：{resp.text[:500]}")

        try:
            body = resp.json()
            choice = (body.get("choices") or [{}])[0]
            message = choice.get("message") or {}
            content = message.get("content") or choice.get("text") or ""
            if isinstance(content, list):
                content = "".join(
                    part.get("text", "") for part in content if isinstance(part, dict))
            usage = body.get("usage") or {}
            finish_reason = choice.get("finish_reason", "")
        except (ValueError, AttributeError, IndexError) as exc:
            raise LlmError(f"模型响应无法解析：{exc}") from exc

        content = str(content).strip()
        if not content:
            raise LlmError("模型返回空内容")
        return content, usage, latency_ms, str(finish_reason)


def _usage_tokens(usage: dict) -> tuple[int, int]:
    prompt = usage.get("prompt_tokens", usage.get("input_tokens", 0)) or 0
    completion = usage.get("completion_tokens", usage.get("output_tokens", 0)) or 0
    try:
        return int(prompt), int(completion)
    except (TypeError, ValueError):
        return 0, 0


def create_chat_client(settings: LlmSettings) -> ChatClient:
    """按 provider 的 api_style 创建客户端；新增协议时在这里注册实现。"""
    if settings.api_style == "openai_chat":
        return ChatClient(settings)
    raise LlmError(
        f"暂不支持 api_style={settings.api_style!r}；"
        f"provider={settings.provider}，请在 bench_llm.py 中实现对应客户端",
        fatal=True)


class LlmAgent:
    """真实 LLM 决策器：同一轮只做一次决策，不持有跨轮状态。"""

    name = "llm"

    def __init__(self, settings: LlmSettings, spec: ModelSpec):
        self.settings = settings
        self.spec = spec
        self.client = create_chat_client(settings)
        self.prompt_version = PROMPT_VERSION

    def decide(self, context: str, user_input: str, intent: dict) -> Decision:
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_message(context, user_input)},
        ]
        last_error = ""
        last_usage: tuple[int, int] = (0, 0)
        for attempt in range(self.settings.retries + 1):
            try:
                content, usage, latency_ms, finish_reason = self.client.complete(messages)
                last_usage = _usage_tokens(usage)
                decision = parse_decision(content)
                decision.prompt_tokens, decision.completion_tokens = last_usage
                decision.latency_ms = latency_ms
                decision.finish_reason = finish_reason
                decision.model = self.settings.model
                return decision
            except LlmError as exc:
                last_error = str(exc)
                if exc.fatal:
                    break
            except ValueError as exc:
                last_error = f"输出解析失败：{exc}"
            if attempt < self.settings.retries:
                time.sleep(0.8 * (attempt + 1))
        return Decision(
            action="INVALID",
            raw="",
            prompt_tokens=last_usage[0],
            completion_tokens=last_usage[1],
            parse_error=last_error,
            model=self.settings.model,
        )
