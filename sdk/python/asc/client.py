"""ASC REST API 的 Python 客户端。

依赖仅 requests（标准生态），无框架绑定——LangGraph / CrewAI / 自研框架
都能直接用；框架胶水代码见仓库 examples/ 目录。

用法：
    from asc import AscClient, AscNotFoundError

    asc = AscClient("http://localhost:8080")
    try:
        card = asc.get_state_card("order-check-001", budget="small")
        prompt = str(card) + "\\n\\n用户输入：" + user_input
    except AscNotFoundError:
        asc.create_task("order-check-001", "检查上海最近30天销量")
"""

from __future__ import annotations

from typing import Any, Optional

import requests


class AscError(Exception):
    """ASC 服务端返回的业务错误（4xx/5xx）。

    属性：
        status: HTTP 状态码
        code: 业务错误码，如 TASK_NOT_FOUND / INVALID_ARGUMENT
        message: 错误描述
    """

    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"ASC {status} [{code}]: {message}")
        self.status = status
        self.code = code
        self.message = message


class AscNotFoundError(AscError):
    """404：任务或状态卡不存在（TASK_NOT_FOUND / CARD_NOT_FOUND）。"""


class AscConflictError(AscError):
    """409：任务 ID 已被占用（TASK_ALREADY_EXISTS）。"""


class AscClient:
    """ASC 服务端客户端。线程安全，可全局复用。

    :param base_url: 服务地址，默认 http://localhost:8080
    :param timeout: 单请求超时秒数
    :param session: 可注入自定义 requests.Session（测试用），默认新建
    """

    def __init__(self, base_url: str = "http://localhost:8080",
                 timeout: float = 5.0, session: Optional[Any] = None):
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout
        self._session = session if session is not None else requests.Session()

    # ---------- 任务与写入 ----------

    def create_task(self, task_id: str, description: Optional[str] = None) -> dict:
        """创建任务。task_id 由调用方指定，方便后续按 ID 引用。"""
        return self._request("POST", "/v1/tasks",
                             json_body={"taskId": task_id, "description": description})

    def restore_task(self, new_task_id: str, restore_from: str,
                     description: Optional[str] = None) -> dict:
        """按源任务的当前有效状态恢复出新任务（跨会话恢复）。"""
        return self._request("POST", "/v1/tasks", json_body={
            "taskId": new_task_id, "description": description,
            "restoreFrom": restore_from,
        })

    def add_constraint(self, task_id: str, text: str, level: str = "HARD") -> None:
        """写入硬/软约束。level: HARD（默认）| SOFT。"""
        self._request("POST", f"/v1/tasks/{task_id}/constraints",
                      json_body={"text": text, "level": level})

    def record_fact(self, task_id: str, key: str, value: str,
                    expires_at: Optional[str] = None, source: str = "tool") -> None:
        """写入工具返回的事实。expires_at 传 ISO-8601 字符串或 None（永不过期）。"""
        self._request("POST", f"/v1/tasks/{task_id}/facts", json_body={
            "key": key, "value": value, "expiresAt": expires_at, "source": source,
        })

    def record_todo(self, task_id: str, text: str, source: str = "user") -> None:
        """写入待办事项。"""
        self._request("POST", f"/v1/tasks/{task_id}/todos",
                      json_body={"text": text, "source": source})

    def update_todo(self, task_id: str, todo_id: str, status: str) -> None:
        """待办状态流转：status 取 OPEN / DONE / CANCELLED。"""
        self._request("PATCH", f"/v1/tasks/{task_id}/todos/{todo_id}",
                      json_body={"status": status})

    def list_tasks(self) -> list:
        """全量任务列表，最近更新的排前面。"""
        return self._request("GET", "/v1/tasks")

    def list_card_versions(self, task_id: str) -> list:
        """列出某任务全部已持久化的状态卡版本摘要（版本号升序）。"""
        return self._request("GET", f"/v1/tasks/{task_id}/versions")

    # ---------- 读取与治理 ----------

    def get_state_card(self, task_id: str, budget: Optional[str] = None) -> dict:
        """拿状态卡（Agent 每轮决策前调用）。

        :param budget: small / medium / large（大小写不敏感），None 用服务端默认
        :return: 状态卡 dict（taskId/version/hardConstraints/.../estimatedTokens）
        """
        return self._request("GET", f"/v1/tasks/{task_id}/state-card",
                             params={"budget": budget})

    def snapshot(self, task_id: str) -> dict:
        """结束任务并生成快照，任务转为 SNAPSHOTTED，返回固化的全量卡。"""
        return self._request("POST", f"/v1/tasks/{task_id}/snapshot")

    def replay(self, task_id: str, version: Optional[int] = None) -> dict:
        """版本回放。version 为 None 时取最新持久化的一版。"""
        return self._request("GET", f"/v1/tasks/{task_id}/replay",
                             params={"version": version})

    def handoff(self, task_id: str, to_agent: str) -> dict:
        """多 Agent 交接：移交给 to_agent，返回交接时刻的全量卡。"""
        return self._request("POST", f"/v1/tasks/{task_id}/handoff",
                             json_body={"toAgent": to_agent})

    # ---------- 内部 ----------

    def _request(self, method: str, path: str,
                 json_body: Optional[dict] = None,
                 params: Optional[dict] = None) -> Optional[dict]:
        resp = self._session.request(
            method, self._base_url + path,
            json=json_body, params=params, timeout=self._timeout)
        if resp.status_code >= 400:
            raise self._error(resp)
        if resp.status_code == 204 or not resp.content:
            return None
        return resp.json()

    @staticmethod
    def _error(resp: Any) -> AscError:
        try:
            body = resp.json()
            code = body.get("code", f"HTTP_{resp.status_code}")
            message = body.get("message", resp.text)
        except (ValueError, AttributeError):
            code = f"HTTP_{resp.status_code}"
            message = getattr(resp, "text", "")
        cls = AscError
        if resp.status_code == 404:
            cls = AscNotFoundError
        elif resp.status_code == 409:
            cls = AscConflictError
        return cls(resp.status_code, code, message)
