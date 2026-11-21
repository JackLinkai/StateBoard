"""极简 requests 兼容层，仅用于本地无网络时跑 ASC Python SDK。

只在系统未安装 requests 时由 asc_e2e_bridge 注入 sys.path。
支持 SDK 用到的 Session.request / status_code / content / text / json()。
"""

from __future__ import annotations

import json as _json
import urllib.error
import urllib.parse
import urllib.request


class _Response:
    def __init__(self, status: int, body: bytes):
        self.status_code = status
        self.content = body
        self.text = body.decode("utf-8", "replace")

    def json(self):
        if not self.content:
            raise ValueError("empty response")
        return _json.loads(self.content.decode("utf-8", "replace"))


class Session:
    def request(self, method, url, json=None, params=None, headers=None, timeout=None):
        if params:
            query = urllib.parse.urlencode(
                {k: v for k, v in params.items() if v is not None}
            )
            url = url + ("&" if "?" in url else "?") + query
        data = None
        all_headers = {"Content-Type": "application/json"}
        if headers:
            all_headers.update(headers)
        if json is not None:
            data = _json.dumps(json).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=all_headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or 5) as resp:
                return _Response(resp.status, resp.read())
        except urllib.error.HTTPError as exc:
            return _Response(exc.code, exc.read())


def post(url, **kwargs):
    return Session().request("POST", url, **kwargs)


def get(url, **kwargs):
    return Session().request("GET", url, **kwargs)
