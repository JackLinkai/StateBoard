"""ASC MCP Server（端到端测试扩展版）。

在原版 11 个工具之上增加 4 个「留痕 + 快照桥接」工具，供 WorkBuddy / Trae
这类 MCP 客户端做真实端到端测试：改文件、记 trace、固化快照、出分析报告。

启动（stdio）：
    ASC_BASE_URL=http://localhost:8080 python mcp/asc_e2e_server.py

客户端配置见 e2e_file_edit/mcp-workbuddy.example.json 与操作手册 README.md。
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from asc_mcp_server import mcp  # noqa: E402
from e2e_file_edit.asc_e2e_bridge import (  # noqa: E402
    init_run,
    record_step,
    snapshot_to_contextfs,
    summarize,
)


@mcp.tool()
def e2e_start(run_id: str, mode: str, description: str = "") -> dict:
    """初始化一次端到端测试。mode 建议取 baseline 或 asc。"""
    run_dir = init_run(run_id, mode, description)
    return {"runId": run_id, "mode": mode, "resultDir": str(run_dir)}


@mcp.tool()
def e2e_record_step(
    run_id: str,
    step: str,
    decision: str,
    mode: str = "asc",
    context: str = "",
    files: str = "",
    tokens: int = 0,
    violation: bool = False,
    stale_ref: bool = False,
    repeated: bool = False,
    resume_steps: int = 0,
    task_id: str = "",
    note: str = "",
) -> dict:
    """记录一步决策。改文件前后都调用，trace 才会留在 results/<runId>/raw.jsonl。

    violation=是否违反硬约束；stale_ref=是否引用了被覆盖/过期事实；
    repeated=是否重复执行了已完成编辑；resume_steps=跨会话恢复用了几步。
    """
    row = {
        "mode": mode or "asc",
        "step": step,
        "taskId": task_id,
        "decision": decision,
        "context": context,
        "files": files,
        "tokens": int(tokens or 0),
        "violation": bool(violation),
        "staleRef": bool(stale_ref),
        "repeated": bool(repeated),
        "resumeSteps": int(resume_steps or 0),
        "note": note,
    }
    record_step(run_id, row)
    return {"recorded": True, "runId": run_id, "step": step}


@mcp.tool()
def e2e_snapshot(
    task_id: str,
    run_id: str = "",
    owner: str = "asc",
    visibility: str = "private",
    agent_binding: str = "builder",
) -> dict:
    """固化 ASC 快照并写成 ContextFS 风格节点（含 owner/版本/可见性/Agent 绑定）。"""
    return snapshot_to_contextfs(
        task_id,
        owner=owner,
        visibility=visibility,
        agent_binding=agent_binding,
        run_id=run_id,
    )


@mcp.tool()
def e2e_report(run_id: str) -> dict:
    """汇总 trace 与最终文件状态，生成 summary.json 和 analysis.md。"""
    return summarize(run_id)


if __name__ == "__main__":
    mcp.run()
