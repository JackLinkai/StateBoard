"""ASC MCP Server：把 ASC 的状态治理能力暴露为 MCP 工具，
任何支持 MCP 的 Agent 客户端（Claude Desktop / Trae / 自研）都能按需拉取状态。

依赖（MCP SDK 2.x）：
    pip install "mcp>=2"
    cd sdk/python && pip install .

启动（stdio 模式）：
    ASC_BASE_URL=http://localhost:8080 python mcp/asc_mcp_server.py

客户端配置（如 Trae / Claude Desktop 的 mcp.json）：
    {
      "mcpServers": {
        "asc": {
          "command": "python",
          "args": ["mcp/asc_mcp_server.py"],
          "env": { "ASC_BASE_URL": "http://localhost:8080" }
        }
      }
    }
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

# 允许从仓库根目录直接运行：把 sdk/python 加进 import 路径
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "sdk" / "python"))

from mcp.server.mcpserver import MCPServer  # noqa: E402

from asc import AscClient  # noqa: E402

mcp = MCPServer("asc")
client = AscClient(os.environ.get("ASC_BASE_URL", "http://localhost:8080"))


@mcp.tool()
def create_task(task_id: str, description: str = "") -> dict:
    """创建 ASC 任务。task_id 由调用方指定，后续操作都用它引用。"""
    return client.create_task(task_id, description or None)


@mcp.tool()
def add_constraint(task_id: str, text: str, level: str = "HARD") -> str:
    """写入硬约束/软约束（用户一开始定的规矩，之后每轮都要遵守）。
    level: HARD（不可覆盖）或 SOFT（尽量遵守）。"""
    client.add_constraint(task_id, text, level)
    return "constraint saved"


@mcp.tool()
def record_fact(task_id: str, key: str, value: str,
                expires_at: str = "", source: str = "tool") -> str:
    """写入工具返回的事实（同 key 自动 LATEST_WINS 冲突消解）。
    expires_at 为 ISO-8601 字符串，留空表示永不过期。"""
    client.record_fact(task_id, key, value,
                       expires_at or None, source)
    return "fact saved"


@mcp.tool()
def record_todo(task_id: str, text: str, source: str = "user") -> str:
    """写入待办事项（下一步依赖的未完成事项）。"""
    client.record_todo(task_id, text, source)
    return "todo saved"


@mcp.tool()
def update_todo(task_id: str, todo_id: str, status: str) -> str:
    """待办状态流转：把某条待办标记为 DONE（完成）或 CANCELLED（取消），
    也可改回 OPEN。完成后不再注入状态卡。todo_id 从状态卡的 openTodos 里取。"""
    client.update_todo(task_id, todo_id, status.upper())
    return "todo updated"


@mcp.tool()
def close_task(task_id: str) -> str:
    """关闭任务（幂等）：置为 CLOSED。已持久化的状态卡与快照保留，回放不受影响。
    任务结束后调用；如需跨会话继续，请先用 snapshot 再 restore_task。"""
    task = client.close_task(task_id)
    return f"task {task['taskId']} closed"


@mcp.tool()
def get_state_card(task_id: str, budget: str = "") -> dict:
    """获取当前任务状态卡：必须遵守的约束 / 已确认事实 / 待办 / 已失效信息。
    每轮决策前调用，基于状态卡行动。budget: small/medium/large，留空用默认。"""
    return client.get_state_card(task_id, budget or None)


@mcp.tool()
def snapshot(task_id: str) -> dict:
    """结束任务并生成快照（可回放、可恢复）。"""
    return client.snapshot(task_id)


@mcp.tool()
def restore_task(new_task_id: str, restore_from: str, description: str = "") -> dict:
    """按源任务的当前有效状态恢复出新任务（跨会话恢复）。"""
    return client.restore_task(new_task_id, restore_from, description or None)


@mcp.tool()
def replay(task_id: str, version: int = 0) -> dict:
    """版本回放：取历史上某一刻的状态卡。version 为 0 时取最新持久化的一版。"""
    return client.replay(task_id, version or None)


@mcp.tool()
def handoff(task_id: str, to_agent: str) -> dict:
    """多 Agent 交接：移交给 to_agent，返回交接时刻的全量状态卡。"""
    return client.handoff(task_id, to_agent)


if __name__ == "__main__":
    mcp.run()  # stdio
