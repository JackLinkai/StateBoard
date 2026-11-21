"""ASC 端到端测试桥接层。

把 ASC 快照写成 ContextFS 风格的节点（本地文件实现；配置了
CONTEXTFS_BASE_URL 时同时 POST 到真实 ContextFS REST API），
并把每一步决策留成可分析的 JSONL trace。

设计边界：
- 本地 contextfs/ 目录是「ContextFS 兼容存储」，不是 ContextFS 服务端；
  它的价值是让端到端测试在没有 Milvus / PostgreSQL / Redis 时也能跑。
- 生产环境请启动真实 ContextFS，并设置 CONTEXTFS_BASE_URL。
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
SDK_DIR = ROOT.parent / "sdk" / "python"
VENDOR_DIR = ROOT / "_vendor"
try:
    import requests  # noqa: F401
except ImportError:
    if str(VENDOR_DIR) not in sys.path:
        sys.path.insert(0, str(VENDOR_DIR))
if str(SDK_DIR) not in sys.path:
    sys.path.insert(0, str(SDK_DIR))

from asc import AscClient  # noqa: E402

RESULTS_DIR = ROOT / "results"
CONTEXTFS_DIR = ROOT / "contextfs"
WORKSPACE_DIR = ROOT / "workspace"


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def get_client() -> AscClient:
    base_url = os.environ.get("ASC_BASE_URL", "http://localhost:8080")
    tenant = os.environ.get("ASC_TENANT") or None
    return AscClient(base_url, tenant=tenant)


def estimate_tokens(text: str) -> int:
    """与 ASC 对齐的粗估：约 4 字符 1 token，CJK 按 1 字符 1 token。"""
    tokens = sum(1.0 if ord(ch) > 127 else 0.25 for ch in text or "")
    return max(1, int(tokens + 0.999))


def init_run(run_id: str, mode: str, description: str = "") -> Path:
    run_dir = RESULTS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    meta = {
        "runId": run_id,
        "mode": mode,
        "description": description,
        "startedAt": now_iso(),
        "ascBaseUrl": os.environ.get("ASC_BASE_URL", "http://localhost:8080"),
        "contextFsBaseUrl": os.environ.get("CONTEXTFS_BASE_URL", ""),
    }
    (run_dir / "meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return run_dir


def record_step(run_id: str, step: dict[str, Any]) -> dict[str, Any]:
    run_dir = RESULTS_DIR / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    line = {"ts": now_iso(), **step}
    with (run_dir / "raw.jsonl").open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(line, ensure_ascii=False) + "\n")
    return line


def snapshot_to_contextfs(
    task_id: str,
    *,
    owner: str = "asc",
    visibility: str = "private",
    agent_binding: str = "builder",
    run_id: str = "",
) -> dict[str, Any]:
    """固化 ASC 快照，并写成 ContextFS 风格节点。"""
    card = get_client().snapshot(task_id)
    version = int(card.get("version") or 1)
    uri = f"ctxfs://task/{task_id}/snapshots/v{version}.md"
    local_path = CONTEXTFS_DIR / "task" / task_id / "snapshots" / f"v{version}.json"
    local_path.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "uri": uri,
        "owner": owner,
        "version": version,
        "visibility": visibility,
        "agent_binding": agent_binding,
        "created_at": now_iso(),
        "source_task": task_id,
        "run_id": run_id or None,
        "card": card,
        "lineage": {
            "asc_replay": f"/v1/tasks/{task_id}/replay?version={version}",
            "asc_snapshot": f"/v1/tasks/{task_id}/snapshot",
        },
    }
    local_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    remote: Any = "not_configured"
    base_url = os.environ.get("CONTEXTFS_BASE_URL", "").strip()
    if base_url:
        remote = _post_contextfs(base_url, uri, payload)

    if run_id:
        record_step(run_id, {
            "mode": "governance",
            "step": "snapshot_to_contextfs",
            "taskId": task_id,
            "uri": uri,
            "files": str(local_path),
            "decision": f"snapshot v{version} -> {uri}",
            "context": "",
            "tokens": 0,
        })
    return {"uri": uri, "localPath": str(local_path), "remote": remote, "version": version}


def _post_contextfs(base_url: str, uri: str, payload: dict[str, Any]) -> Any:
    import urllib.error
    import urllib.request

    body = json.dumps({
        "uri": uri,
        "content": json.dumps(payload["card"], ensure_ascii=False),
        "type": "MEMORY",
        "generateLevels": True,
        "metadata": {
            "owner": payload["owner"],
            "version": payload["version"],
            "visibility": payload["visibility"],
            "agent_binding": payload["agent_binding"],
            "source_task": payload["source_task"],
        },
    }, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + "/api/v1/fs/write",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=3) as resp:
            return {"status": resp.status, "body": resp.read(500).decode("utf-8", "replace")}
    except (urllib.error.URLError, OSError) as exc:
        return {"status": "failed", "error": str(exc)}


def summarize(run_id: str) -> dict[str, Any]:
    """汇总一次端到端测试，输出 summary.json 与 analysis.md。"""
    run_dir = RESULTS_DIR / run_id
    lines = []
    raw_path = run_dir / "raw.jsonl"
    if raw_path.exists():
        for line in raw_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                lines.append(json.loads(line))

    by_mode: dict[str, dict[str, Any]] = {}
    for row in lines:
        mode = row.get("mode", "unknown")
        bucket = by_mode.setdefault(mode, {
            "steps": 0,
            "injectedTokens": 0,
            "constraintViolations": 0,
            "staleFactRefs": 0,
            "repeatedEdits": 0,
            "resumeSteps": 0,
            "files": [],
        })
        bucket["steps"] += 1
        bucket["injectedTokens"] += int(row.get("tokens") or 0)
        bucket["constraintViolations"] += 1 if row.get("violation") else 0
        bucket["staleFactRefs"] += 1 if row.get("staleRef") else 0
        bucket["repeatedEdits"] += 1 if row.get("repeated") else 0
        bucket["resumeSteps"] += int(row.get("resumeSteps") or 0)
        for f in str(row.get("files") or "").split(","):
            if f.strip() and f.strip() not in bucket["files"]:
                bucket["files"].append(f.strip())

    expected = {"timeout": 50, "region": "shanghai", "retries": 2}
    final_state: dict[str, Any] = {}
    for mode in ("baseline", "asc"):
        path = WORKSPACE_DIR / mode / "config.json"
        if path.exists():
            final_state[mode] = json.loads(path.read_text(encoding="utf-8"))

    summary = {
        "runId": run_id,
        "generatedAt": now_iso(),
        "expectedFinalState": expected,
        "finalState": final_state,
        "finalCorrect": {
            mode: state == expected for mode, state in final_state.items()
        },
        "byMode": by_mode,
        "traceCount": len(lines),
    }
    (run_dir / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (run_dir / "analysis.md").write_text(
        _render_analysis(summary, run_dir), encoding="utf-8"
    )
    return summary


def _render_analysis(summary: dict[str, Any], run_dir: Path) -> str:
    base = summary["byMode"].get("baseline", {})
    asc = summary["byMode"].get("asc", {})
    def pct(old: int, new: int) -> str:
        if not old:
            return "-"
        return f"{(old - new) / old * 100:.1f}%"

    lines = [
        f"# 端到端测试结果分析 · {summary['runId']}",
        "",
        f"生成时间：{summary['generatedAt']}",
        "",
        "## 一、结果总表",
        "",
        "| 指标 | Baseline | ASC | 变化 |",
        "|---|---:|---:|---:|",
        f"| 决策步数 | {base.get('steps', 0)} | {asc.get('steps', 0)} | - |",
        f"| 注入 token（估算） | {base.get('injectedTokens', 0)} | {asc.get('injectedTokens', 0)} | {pct(base.get('injectedTokens', 0), asc.get('injectedTokens', 0))} ↓ |",
        f"| 硬约束违反 | {base.get('constraintViolations', 0)} | {asc.get('constraintViolations', 0)} | - |",
        f"| 过期/被覆盖事实引用 | {base.get('staleFactRefs', 0)} | {asc.get('staleFactRefs', 0)} | - |",
        f"| 重复编辑 | {base.get('repeatedEdits', 0)} | {asc.get('repeatedEdits', 0)} | - |",
        f"| 恢复重放步数 | {base.get('resumeSteps', 0)} | {asc.get('resumeSteps', 0)} | - |",
        f"| 最终文件正确 | {summary['finalCorrect'].get('baseline', False)} | {summary['finalCorrect'].get('asc', False)} | - |",
        "",
        "## 二、最终文件状态",
        "",
        f"期望：{json.dumps(summary['expectedFinalState'], ensure_ascii=False)}",
        "",
    ]
    for mode, state in summary["finalState"].items():
        lines.append(f"- {mode}: `{json.dumps(state, ensure_ascii=False)}`")
    governance = summary["byMode"].get("governance", {})
    lines += [
        "",
        "## 三、口径与边界（写进成果分析时必须保留）",
        "",
    ]
    if (run_dir / "asc_card_audit.json").exists():
        lines += [
            "1. `asc_card_audit.json` 是 audit 视图证据：同 key 的旧值转 SUPERSEDED、",
            "   过期事实转 EXPIRED、软约束可见；lean 视图不注入这些格，所以没有进入决策上下文。",
            "",
        ]
    lines += [
        "2. 本测试的决策器是确定性脚本，不是大模型；它隔离出的是「注入内容质量」带来的差异，",
        "   与真实 LLM 的随机性无关。要测模型行为请按操作手册的 LLM / MCP 手工路径复跑。",
        "3. token 为估算值（CJK 1 字符≈1 token，其余 4 字符≈1 token），用于相对比较，不作绝对成本。",
        "4. 文件修改是真实磁盘 I/O；两组各自写 `workspace/baseline` 与 `workspace/asc`，互不覆盖。",
        "5. 任务快照通过桥接层落成 ContextFS 风格节点，含 owner / version / visibility / agent_binding；",
        "   若配置了 `CONTEXTFS_BASE_URL`，会同时写入真实 ContextFS。",
        f"6. 治理步数：{governance.get('steps', 0)}；快照节点："
        + (governance.get("files") or ["无"])[-1],
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="ASC 端到端结果汇总")
    parser.add_argument("run_id", help="结果目录名，例如 e2e-20261121-001")
    args = parser.parse_args()
    s = summarize(args.run_id)
    print(json.dumps(s, ensure_ascii=False, indent=2))
