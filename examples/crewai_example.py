"""CrewAI 接入 ASC 示例：把 ASC 做成 CrewAI 工具，Agent 用工具读写状态。

依赖：
    pip install crewai requests
    cd sdk/python && pip install .

核心模式：
    把 get_state_card / record_fact 封装成 @tool，Agent 需要时自行调用；
    硬约束则在任务启动前直接写入 ASC，不由 Agent 决定。

运行（需先启动 ASC）：
    python examples/crewai_example.py
"""

from __future__ import annotations

from crewai.tools import tool

from asc import AscClient

ASC_URL = "http://localhost:8080"
TASK_ID = "crewai-demo-001"

asc = AscClient(ASC_URL)


@tool("get_state_card")
def get_state_card() -> str:
    """获取当前任务的状态卡：必须遵守的约束、已确认事实、待办、已失效信息。
    每次决策前先调用它，基于状态卡行动，不要依赖记忆里的旧数据。"""
    return str(asc.get_state_card(TASK_ID, budget="small"))


@tool("record_fact")
def record_fact(key: str, value: str) -> str:
    """工具查询返回结果后，把结论写入 ASC（key=value）。
    例：record_fact(key="shanghai_sales", value="120")"""
    asc.record_fact(TASK_ID, key, value, source="tool")
    return f"已记录 {key}={value}"


def bootstrap() -> None:
    """任务初始化：创建任务 + 写入用户硬约束（这一步在 Crew 启动前做）。"""
    try:
        asc.create_task(TASK_ID, "CrewAI 接入演示")
    except Exception:
        pass  # 已存在
    try:
        asc.add_constraint(TASK_ID, "只允许查询最近30天数据", level="HARD")
    except Exception:
        pass


def main() -> None:
    bootstrap()

    # 真实场景：agent = Agent(role="数据分析师", tools=[get_state_card, record_fact], ...)
    # 这里演示两个工具的直接调用链
    print(get_state_card.run())
    print(record_fact.run(key="shanghai_sales", value="120"))
    print(get_state_card.run())

    # 任务收尾：快照可回放；下个会话用 restore_task 恢复
    card = asc.snapshot(TASK_ID)
    print("快照版本:", card["version"])


if __name__ == "__main__":
    main()
