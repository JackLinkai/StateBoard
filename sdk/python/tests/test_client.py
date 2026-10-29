"""AscClient 的纯单元测试：注入假 Session，不发真实 HTTP 请求。

运行：cd sdk/python && python -m unittest discover tests -v
"""

import json
import unittest

from asc import AscClient, AscConflictError, AscError, AscNotFoundError


class FakeResponse:
    def __init__(self, status_code=200, body=None, text=""):
        self.status_code = status_code
        self._body = body
        self.text = text or (json.dumps(body) if body is not None else "")
        self.content = b"x" if body is not None else b""

    def json(self):
        if self._body is None:
            raise ValueError("no body")
        return self._body


class FakeSession:
    """记录请求并按脚本返回响应。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, method, url, json=None, params=None, timeout=None):
        self.calls.append({"method": method, "url": url, "json": json,
                           "params": params, "timeout": timeout})
        return self.responses.pop(0)


class AscClientTest(unittest.TestCase):

    def setUp(self):
        self.session = FakeSession([])
        self.client = AscClient("http://asc.test/", timeout=2.0, session=self.session)

    def test_create_task_posts_body(self):
        self.session.responses.append(FakeResponse(201, {"taskId": "t1", "version": 0}))

        task = self.client.create_task("t1", "描述")

        call = self.session.calls[0]
        self.assertEqual(call["method"], "POST")
        self.assertEqual(call["url"], "http://asc.test/v1/tasks")
        self.assertEqual(call["json"], {"taskId": "t1", "description": "描述"})
        self.assertEqual(task["taskId"], "t1")

    def test_restore_task_carries_restore_from(self):
        self.session.responses.append(FakeResponse(201, {"taskId": "t2"}))

        self.client.restore_task("t2", "t1", "新会话")

        self.assertEqual(self.session.calls[0]["json"],
                         {"taskId": "t2", "description": "新会话", "restoreFrom": "t1"})

    def test_add_constraint_defaults_hard(self):
        self.session.responses.append(FakeResponse(201))

        self.client.add_constraint("t1", "只查30天")

        call = self.session.calls[0]
        self.assertEqual(call["url"], "http://asc.test/v1/tasks/t1/constraints")
        self.assertEqual(call["json"], {"text": "只查30天", "level": "HARD"})
        self.assertIsNone(call["json"].get("source") if isinstance(call["json"], dict) else None)

    def test_record_fact_maps_expires_at(self):
        self.session.responses.append(FakeResponse(201))

        self.client.record_fact("t1", "sales", "120",
                                expires_at="2026-11-05T00:00:00Z",
                                source="tool:query_sales")

        self.assertEqual(self.session.calls[0]["json"], {
            "key": "sales", "value": "120",
            "expiresAt": "2026-11-05T00:00:00Z",
            "source": "tool:query_sales",
        })

    def test_get_state_card_budget_lowercased(self):
        self.session.responses.append(FakeResponse(200, {"taskId": "t1", "version": 7}))

        card = self.client.get_state_card("t1", budget="SMALL")

        self.assertEqual(self.session.calls[0]["url"],
                         "http://asc.test/v1/tasks/t1/state-card")
        self.assertEqual(self.session.calls[0]["params"], {"budget": "SMALL"})
        self.assertEqual(card["version"], 7)

    def test_replay_version_param_omitted_when_none(self):
        self.session.responses.append(FakeResponse(200, {"taskId": "t1", "version": 9}))

        self.client.replay("t1")

        self.assertIsNone(self.session.calls[0]["params"]["version"])

    def test_handoff_body(self):
        self.session.responses.append(FakeResponse(200, {"taskId": "t1"}))

        self.client.handoff("t1", "agent-b")

        self.assertEqual(self.session.calls[0]["json"], {"toAgent": "agent-b"})

    def test_update_todo_patches_status(self):
        self.session.responses.append(FakeResponse(200))

        self.client.update_todo("t1", "todo-9", "DONE")

        call = self.session.calls[0]
        self.assertEqual(call["method"], "PATCH")
        self.assertEqual(call["url"], "http://asc.test/v1/tasks/t1/todos/todo-9")
        self.assertEqual(call["json"], {"status": "DONE"})

    def test_list_tasks_and_card_versions(self):
        self.session.responses.append(FakeResponse(200, [{"taskId": "t1"}]))
        self.session.responses.append(FakeResponse(
            200, [{"taskId": "t1", "version": 3, "estimatedTokens": 120}]))

        tasks = self.client.list_tasks()
        versions = self.client.list_card_versions("t1")

        self.assertEqual(self.session.calls[0]["method"], "GET")
        self.assertEqual(self.session.calls[0]["url"], "http://asc.test/v1/tasks")
        self.assertEqual(self.session.calls[1]["url"],
                         "http://asc.test/v1/tasks/t1/versions")
        self.assertEqual(tasks[0]["taskId"], "t1")
        self.assertEqual(versions[0]["version"], 3)

    def test_no_body_returns_none(self):
        self.session.responses.append(FakeResponse(201))

        self.assertIsNone(self.client.add_constraint("t1", "x"))

    def test_404_raises_not_found_with_code(self):
        self.session.responses.append(FakeResponse(
            404, {"code": "TASK_NOT_FOUND", "message": "task not found: t9"}))

        with self.assertRaises(AscNotFoundError) as ctx:
            self.client.get_state_card("t9")
        self.assertEqual(ctx.exception.code, "TASK_NOT_FOUND")
        self.assertEqual(ctx.exception.status, 404)

    def test_409_raises_conflict(self):
        self.session.responses.append(FakeResponse(
            409, {"code": "TASK_ALREADY_EXISTS", "message": "task already exists: t1"}))

        with self.assertRaises(AscConflictError):
            self.client.restore_task("t1", "t0")

    def test_error_body_falls_back_to_http_code(self):
        self.session.responses.append(FakeResponse(500, body=None, text="boom"))

        with self.assertRaises(AscError) as ctx:
            self.client.get_state_card("t1")
        self.assertEqual(ctx.exception.code, "HTTP_500")
        self.assertEqual(ctx.exception.message, "boom")


if __name__ == "__main__":
    unittest.main()
