"""ASC × open_deep_research 接入层。

做法：不改 vendor 里的任何一行，只覆盖两个节点后重建图。

    原生 supervisor      → asc_supervisor        每轮决策前注入编译后的状态卡
    原生 supervisor_tools → asc_supervisor_tools  子研究结论写回 ASC（同 topic LATEST_WINS）

其余节点（clarify / write_brief / researcher 子图 / compress / final_report）
全部直接复用上游实现，保证 A/B 对照的唯一变量是「supervisor 每轮能看到什么」。

设计上的一个刻意取舍：
    final_report_generation 不做 ASC 替换。研究报告是累积关系（新结论补充旧结论），
    而 ASC 的事实语义是覆盖关系（新值取代旧值）。用状态卡替代 findings 会丢信息，
    那是滥用，不是优化。ASC 只治「决策状态」，不治「素材全集」。
"""

from __future__ import annotations

import asyncio
import os
import re
from typing import Any, Literal, Optional

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command

from open_deep_research.configuration import Configuration
from open_deep_research.deep_researcher import (
    clarify_with_user,
    configurable_model,
    final_report_generation,
    researcher_subgraph,
    write_research_brief,
)
from open_deep_research.state import (
    AgentInputState,
    AgentState,
    ConductResearch,
    ResearchComplete,
    SupervisorState,
)
from open_deep_research.utils import (
    get_api_key_for_model,
    get_notes_from_tool_calls,
    think_tool,
)

from asc import AscClient

FACT_VALUE_LIMIT = 800
TOPIC_KEY_LIMIT = 60


# ---------------------------------------------------------------- ASC 基础设施

def _asc_from_config(config: RunnableConfig) -> tuple[Optional[AscClient], Optional[str]]:
    """从 config 取 ASC 客户端与任务 ID。未配置则返回 (None, None)，整条链路降级为原生行为。"""
    cfg = (config or {}).get("configurable") or {}
    if cfg.get("asc_enabled") is False:
        return None, None
    task_id = cfg.get("asc_task_id")
    if not task_id:
        return None, None
    base_url = cfg.get("asc_base_url") or os.getenv("ASC_BASE_URL", "http://localhost:8080")
    try:
        return AscClient(base_url, timeout=5.0), task_id
    except Exception:
        return None, None


def _ensure_task(client: AscClient, task_id: str, brief: str) -> None:
    try:
        client.create_task(task_id, (brief or "")[:500] or None)
    except Exception:
        pass  # 已存在（TASK_ALREADY_EXISTS）或服务不可用，都不阻断主流程


def render_card(card: dict) -> str:
    """把状态卡渲染成注入文本。四格语义对齐 ASC：约束 / 事实 / 待办 / 失效。"""
    out = [f"【任务状态卡 · v{card.get('version', '?')}】"]
    hard = card.get("hardConstraints") or []
    if hard:
        out.append("【硬约束 · 任何情况下不得违反】")
        out += [f"  - {c['text']}" for c in hard]
    facts = card.get("verifiedFacts") or []
    if facts:
        out.append("【已确认事实 · 直接引用，不要重复检索】")
        out += [f"  - {f['key']}: {f['value']}" for f in facts]
    todos = card.get("openTodos") or []
    if todos:
        out.append("【待办 · 尚未完成】")
        out += [f"  - {t['text']}" for t in todos]
    invalid = card.get("invalidated") or []
    if invalid:
        out.append("【已失效 · 不要再引用】")
        out += [f"  - {i['id']} {i['reason']}" for i in invalid]
    if len(out) == 1:
        return ""
    out.append(f"（状态卡约 {card.get('estimatedTokens', '?')} token，替代历史全文）")
    return "\n".join(out)


def _inject_card(messages: list, card_text: str) -> list:
    """把状态卡拼进第一条 system 消息末尾。

    不追加独立 system 消息：部分模型（含 DeepSeek）不接受连续 system。
    只在本地副本上改，不回写 state，所以不会随轮次累积。
    """
    msgs = list(messages)
    if msgs and isinstance(msgs[0], SystemMessage):
        msgs[0] = SystemMessage(content=f"{msgs[0].content}\n\n{card_text}")
    else:
        msgs.insert(0, SystemMessage(content=card_text))
    return msgs


def _topic_key(topic: str) -> str:
    """把研究主题折成稳定的 ASC fact key：同主题重复研究会被 LATEST_WINS 覆盖。"""
    key = re.sub(r"\s+", " ", (topic or "").strip())
    return f"studied/{key[:TOPIC_KEY_LIMIT]}" + ("…" if len(key) > TOPIC_KEY_LIMIT else "")


def _trim(text: str, limit: int = FACT_VALUE_LIMIT) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit] + f"…（原文 {len(text)} 字，已截断）"


# ---------------------------------------------------------------- 覆盖的节点

async def asc_supervisor(
    state: SupervisorState, config: RunnableConfig
) -> Command[Literal["supervisor_tools"]]:
    """替换原生 supervisor：模型决策前先注入 ASC 状态卡。"""
    configurable = Configuration.from_runnable_config(config)
    research_model_config = {
        "model": configurable.research_model,
        "max_tokens": configurable.research_model_max_tokens,
        "api_key": get_api_key_for_model(configurable.research_model, config),
        "tags": ["langsmith:nostream"],
    }
    lead_researcher_tools = [ConductResearch, ResearchComplete, think_tool]
    research_model = (
        configurable_model.bind_tools(lead_researcher_tools)
        .with_retry(stop_after_attempt=configurable.max_structured_output_retries)
        .with_config(research_model_config)
    )

    messages = state.get("supervisor_messages", [])
    client, task_id = _asc_from_config(config)
    if client and task_id:
        _ensure_task(client, task_id, state.get("research_brief", ""))
        try:
            card = client.get_state_card(task_id, budget="medium", view="full")
            card_text = render_card(card)
            if card_text:
                messages = _inject_card(messages, card_text)
        except Exception:
            pass  # ASC 不可用 → 退回原生上下文，不阻断研究

    response = await research_model.ainvoke(messages)
    return Command(
        goto="supervisor_tools",
        update={
            "supervisor_messages": [response],
            "research_iterations": state.get("research_iterations", 0) + 1,
        },
    )


async def asc_supervisor_tools(
    state: SupervisorState, config: RunnableConfig
) -> Command[Literal["supervisor", "__end__"]]:
    """替换原生 supervisor_tools：执行逻辑照搬上游，额外把每份子研究结论写回 ASC。"""
    configurable = Configuration.from_runnable_config(config)
    supervisor_messages = state.get("supervisor_messages", [])
    research_iterations = state.get("research_iterations", 0)
    most_recent_message = supervisor_messages[-1]

    exceeded = research_iterations > configurable.max_researcher_iterations
    no_tool_calls = not most_recent_message.tool_calls
    is_complete = any(tc["name"] == "ResearchComplete" for tc in most_recent_message.tool_calls)
    if exceeded or no_tool_calls or is_complete:
        return Command(
            goto=END,
            update={
                "notes": get_notes_from_tool_calls(supervisor_messages),
                "research_brief": state.get("research_brief", ""),
            },
        )

    all_tool_messages: list = []
    update_payload: dict[str, Any] = {"supervisor_messages": []}

    for tool_call in [tc for tc in most_recent_message.tool_calls if tc["name"] == "think_tool"]:
        all_tool_messages.append(ToolMessage(
            content=f"Reflection recorded: {tool_call['args']['reflection']}",
            name="think_tool",
            tool_call_id=tool_call["id"],
        ))

    conduct_calls = [tc for tc in most_recent_message.tool_calls if tc["name"] == "ConductResearch"]

    if conduct_calls:
        try:
            allowed = conduct_calls[: configurable.max_concurrent_research_units]
            overflow = conduct_calls[configurable.max_concurrent_research_units:]
            tasks = [
                researcher_subgraph.ainvoke({
                    "researcher_messages": [HumanMessage(content=tc["args"]["research_topic"])],
                    "research_topic": tc["args"]["research_topic"],
                }, config)
                for tc in allowed
            ]
            results = await asyncio.gather(*tasks)

            client, task_id = _asc_from_config(config)
            for tc, observation in zip(allowed, results):
                compressed = observation.get("compressed_research", "")
                all_tool_messages.append(ToolMessage(
                    content=compressed or "Error synthesizing research report: Maximum retries exceeded",
                    name=tc["name"],
                    tool_call_id=tc["id"],
                ))
                if client and task_id and compressed:
                    try:
                        client.record_fact(
                            task_id,
                            _topic_key(tc["args"].get("research_topic", "")),
                            _trim(compressed),
                            source="deep_research",
                        )
                    except Exception:
                        pass

            for tc in overflow:
                all_tool_messages.append(ToolMessage(
                    content=(
                        "Error: Did not run this research as you have already exceeded the "
                        f"maximum number of concurrent research units. Please try again with "
                        f"{configurable.max_concurrent_research_units} or fewer research units."
                    ),
                    name="ConductResearch",
                    tool_call_id=tc["id"],
                ))

            raw_notes_concat = "\n".join(
                "\n".join(o.get("raw_notes", [])) for o in results
            )
            if raw_notes_concat:
                update_payload["raw_notes"] = [raw_notes_concat]
        except Exception:
            return Command(
                goto=END,
                update={
                    "notes": get_notes_from_tool_calls(supervisor_messages),
                    "research_brief": state.get("research_brief", ""),
                },
            )

    update_payload["supervisor_messages"] = all_tool_messages
    return Command(goto="supervisor", update=update_payload)


# ---------------------------------------------------------------- 重建图

def build_asc_graph():
    """用覆盖后的 supervisor 重建整张图，其余节点复用上游。"""
    supervisor_builder = StateGraph(SupervisorState, config_schema=Configuration)
    supervisor_builder.add_node("supervisor", asc_supervisor)
    supervisor_builder.add_node("supervisor_tools", asc_supervisor_tools)
    supervisor_builder.add_edge(START, "supervisor")
    supervisor_subgraph = supervisor_builder.compile()

    builder = StateGraph(AgentState, input=AgentInputState, config_schema=Configuration)
    builder.add_node("clarify_with_user", clarify_with_user)
    builder.add_node("write_research_brief", write_research_brief)
    builder.add_node("research_supervisor", supervisor_subgraph)
    builder.add_node("final_report_generation", final_report_generation)
    builder.add_edge(START, "clarify_with_user")
    builder.add_edge("research_supervisor", "final_report_generation")
    builder.add_edge("final_report_generation", END)
    return builder.compile()
