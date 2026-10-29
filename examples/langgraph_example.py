"""LangGraph 接入 ASC 示例：每轮决策前注入状态卡，工具返回后写回事件。

依赖：
    pip install langgraph requests
    cd sdk/python && pip install .

核心模式只有两句话：
    LLM 调用前  —— state_card = asc.get_state_card(task_id) → 拼进 prompt
    工具返回后  —— asc.record_fact(task_id, key, value, source="tool:xxx")

运行（需先启动 ASC：docker compose up -d 或 ./mvnw spring-boot:run）：
    python examples/langgraph_example.py
"""

from __future__ import annotations

from typing import Annotated, TypedDict

from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages

from asc import AscClient

ASC_URL = "http://localhost:8080"
TASK_ID = "langgraph-demo-001"

asc = AscClient(ASC_URL)


class AgentState(TypedDict):
    messages: add_messages
    user_input: str


def inject_state(state: AgentState) -> dict:
    """节点 1：拉状态卡注入上下文。约束、事实、待办、失效项全在这里。"""
    try:
        card = asc.get_state_card(TASK_ID, budget="small")
    except Exception:
        card = None  # ASC 不可用时不阻断主流程，按需降级
    prompt = str(card) + "\n\n用户输入：" + state["user_input"] if card else state["user_input"]
    return {"messages": [{"role": "user", "content": prompt}]}


def call_model(state: AgentState) -> dict:
    """节点 2：调 LLM。这里用 echo 占位，换成任意 LangGraph chat model。"""
    user_prompt = state["messages"][-1]["content"]
    return {"messages": [{"role": "assistant", "content": f"echo: {user_prompt}"}]}


def record_result(state: AgentState) -> dict:
    """节点 3：把本轮结论写回 ASC（示例写成事实，真实场景按工具返回写）。"""
    try:
        asc.record_fact(TASK_ID, "last_answer", state["messages"][-1]["content"],
                        source="model")
    except Exception:
        pass
    return {}


def main() -> None:
    # 初始化任务 + 写入一条用户硬约束（真实场景从对话里抽取）
    try:
        asc.create_task(TASK_ID, "LangGraph 接入演示")
    except Exception:
        pass  # 任务已存在
    try:
        asc.add_constraint(TASK_ID, "只允许查询最近30天数据", level="HARD")
    except Exception:
        pass

    graph = StateGraph(AgentState)
    graph.add_node("inject_state", inject_state)
    graph.add_node("call_model", call_model)
    graph.add_node("record_result", record_result)
    graph.add_edge(START, "inject_state")
    graph.add_edge("inject_state", "call_model")
    graph.add_edge("call_model", "record_result")
    graph.add_edge("record_result", END)

    app = graph.compile()
    result = app.invoke({"user_input": "查一下上海上周销量", "messages": []})
    print(result["messages"][-1]["content"])

    # 交接给另一个 Agent：拿卡即接管
    card = asc.handoff(TASK_ID, "agent-b")
    print("交接卡版本:", card["version"])


if __name__ == "__main__":
    main()
