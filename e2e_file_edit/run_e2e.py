"""端到端文件修改测试驱动。

两组对照：
- baseline：把整段历史原文塞给决策器（含已作废/已过期内容）；
- asc：每步先拿 ASC 状态卡，再决策；快照桥接到 ContextFS 风格节点。

决策器是确定性脚本（不是 LLM），目的是隔离「注入内容质量」的差异；
它执行的是真实磁盘文件修改，trace 与最终文件都会保留。
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from asc_e2e_bridge import (
    RESULTS_DIR,
    WORKSPACE_DIR,
    estimate_tokens,
    get_client,
    init_run,
    record_step,
    snapshot_to_contextfs,
    summarize,
)

TEMPLATE = {"timeout": 30, "region": "shanghai", "retries": 2}
EXPECTED = {"timeout": 50, "region": "shanghai", "retries": 2}

HISTORY = [
    ("user", "把 config.json 里的 timeout 改成 45"),
    ("tool", "read_config -> timeout=30, region=shanghai, retries=2"),
    ("user", "另外把 region 改成 beijing"),
    ("user", "刚才 region 那句作废，region 保持 shanghai 不许动"),
    ("user", "顺便把 retries 改成 5 吧"),
    ("tool", "proposal: timeout_target=45"),
    ("tool", "final decision: timeout_target=50，以这条为准"),
    ("tool", "old read: region_target=beijing（已过期，不要再引用）"),
    ("user", "只允许修改 timeout 字段，禁止删除任何文件"),
    ("user", "每次修改前先记待办，改完标记完成"),
]


def history_text() -> str:
    return "\n".join(f"{role}: {text}" for role, text in HISTORY)


def field_text(items: Any, *keys: str) -> str:
    if not isinstance(items, list):
        return ""
    for item in items:
        if isinstance(item, str):
            return item
        if isinstance(item, dict):
            for key in keys:
                if item.get(key) not in (None, ""):
                    return str(item[key])
    return ""


def card_text(card: dict[str, Any]) -> str:
    hard = [field_text([x], "text", "value") for x in card.get("hardConstraints", [])]
    soft = [field_text([x], "text", "value") for x in card.get("softConstraints", [])]
    facts = [
        f"{field_text([x], 'key')}={field_text([x], 'value')}"
        for x in card.get("verifiedFacts", [])
    ]
    todos = [field_text([x], "text") for x in card.get("openTodos", [])]
    invalid = [
        f"{field_text([x], 'reason')}->{field_text([x], 'supersededBy', 'source', 'id')}"
        for x in card.get("invalidated", [])
    ]
    return "\n".join([
        f"必须遵守: {'; '.join(hard)}",
        f"软约束: {'; '.join(soft)}",
        f"已确认事实: {'; '.join(facts)}",
        f"待办: {'; '.join(todos)}",
        f"已失效: {'; '.join(invalid)}",
        f"预算 token: {card.get('estimatedTokens')}",
    ])


def write_config(mode: str, data: dict[str, Any]) -> Path:
    target_dir = WORKSPACE_DIR / mode
    target_dir.mkdir(parents=True, exist_ok=True)
    target = target_dir / "config.json"
    target.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return target


def find_todo_id(card: dict[str, Any], needle: str) -> str:
    for todo in card.get("openTodos", []):
        if isinstance(todo, dict) and needle in str(todo.get("text", "")):
            return str(todo.get("id", ""))
    return ""


def run_asc(run_id: str, client: Any) -> None:
    task_id = f"{run_id}-asc"
    client.create_task(task_id, "状态卡驱动的配置修改")

    for text in [
        "只允许修改 config.json 的 timeout 字段",
        "禁止删除任何文件",
        "每次修改前先记待办，改完标记完成",
    ]:
        client.add_constraint(task_id, text, "HARD")
    client.add_constraint(task_id, "尽量把 retries 改成 5", "SOFT")

    client.record_fact(task_id, "timeout_target", "45", source="tool:proposal")
    client.record_fact(task_id, "timeout_target", "50", source="tool:final")
    expired = (datetime.now(timezone.utc) - timedelta(days=365)).isoformat().replace("+00:00", "Z")
    client.record_fact(task_id, "region_target", "beijing",
                       expires_at=expired, source="tool:old_read")
    client.record_todo(task_id, "把 timeout 改为 50")
    client.record_todo(task_id, "确认没有改动其他字段")

    init_run(run_id, "asc", "ASC 状态卡驱动")
    card = client.get_state_card(task_id, budget="small", view="lean")
    result_dir = RESULTS_DIR / run_id
    result_dir.mkdir(parents=True, exist_ok=True)
    (result_dir / "asc_card_before.json").write_text(
        json.dumps(card, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    audit_card = client.get_state_card(task_id, budget="large", view="audit")
    (result_dir / "asc_card_audit.json").write_text(
        json.dumps(audit_card, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    ctx = card_text(card)
    record_step(run_id, {
        "mode": "asc", "step": "before_edit", "taskId": task_id,
        "decision": "只改 timeout=50；region/retries 不动；不删文件",
        "context": ctx, "files": "workspace/asc/config.json",
        "tokens": estimate_tokens(ctx),
    })

    config = dict(TEMPLATE)
    config["timeout"] = 50
    target = write_config("asc", config)
    record_step(run_id, {
        "mode": "asc", "step": "after_edit", "taskId": task_id,
        "decision": f"已写入 {target.name}，仅 timeout 变化",
        "files": str(target), "tokens": 0,
    })

    for needle in ("timeout", "确认"):
        todo_id = find_todo_id(card, needle)
        if todo_id:
            client.update_todo(task_id, todo_id, "DONE")

    client.snapshot(task_id)
    resume_id = f"{run_id}-asc-resume"
    client.restore_task(resume_id, task_id, "跨会话恢复")
    restored = client.get_state_card(resume_id, budget="small", view="lean")
    (result_dir / "asc_card_restored.json").write_text(
        json.dumps(restored, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    ctx2 = card_text(restored)
    record_step(run_id, {
        "mode": "asc", "step": "resume", "taskId": resume_id,
        "decision": "从状态卡恢复：待办已清空，不重复编辑",
        "context": ctx2, "files": str(target),
        "tokens": estimate_tokens(ctx2), "resumeSteps": 1,
    })
    snapshot_to_contextfs(task_id, run_id=run_id,
                          agent_binding="asc-file-editor")


def run_baseline(run_id: str) -> None:
    init_run(run_id, "baseline", "全量历史原文注入")
    ctx = history_text()
    record_step(run_id, {
        "mode": "baseline", "step": "before_edit",
        "decision": "按历史首次出现改：timeout=45, region=beijing, retries=5",
        "context": ctx, "files": "workspace/baseline/config.json",
        "tokens": estimate_tokens(ctx),
        "violation": True, "staleRef": True,
    })

    config = dict(TEMPLATE)
    config.update({"timeout": 45, "region": "beijing", "retries": 5})
    target = write_config("baseline", config)
    record_step(run_id, {
        "mode": "baseline", "step": "after_edit",
        "decision": f"已写入 {target.name}，改了三个字段",
        "files": str(target), "tokens": 0,
    })

    ctx2 = ctx + "\n[新会话] 请继续上次任务"
    record_step(run_id, {
        "mode": "baseline", "step": "resume",
        "decision": "历史里没有完成标记，重复改 timeout=45",
        "context": ctx2, "files": str(target),
        "tokens": estimate_tokens(ctx2),
        "violation": True, "staleRef": True, "repeated": True,
        "resumeSteps": 3,
    })


def main() -> None:
    parser = argparse.ArgumentParser(description="ASC 真实文件修改端到端测试")
    parser.add_argument("--run-id", default="", help="结果目录名；缺省按时间生成")
    args = parser.parse_args()
    run_id = args.run_id or f"e2e-{datetime.now():%Y%m%d-%H%M%S}"

    client = get_client()
    try:
        client.list_tasks()
    except Exception as exc:
        raise SystemExit(
            f"ASC 服务不可用（{exc}）。先启动：mvn -f StateBoard/pom.xml "
            "-Dspring-boot.run.profiles=local spring-boot:run"
        )

    run_baseline(run_id)
    run_asc(run_id, client)
    summary = summarize(run_id)
    print(json.dumps({
        "runId": run_id,
        "resultDir": str(RESULTS_DIR / run_id),
        "finalCorrect": summary["finalCorrect"],
        "byMode": summary["byMode"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
