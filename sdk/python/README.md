# ASC Python SDK

Agent StateBoard (ASC) 的 Python 客户端，仅依赖 `requests`，
LangGraph / CrewAI / 自研框架都能直接用。

## 安装

```bash
cd sdk/python
pip install .
```

## 快速上手

```python
from asc import AscClient, AscNotFoundError

asc = AscClient("http://localhost:8080")

# 创建任务并写入约束（用户一开始说的话，聊一百轮也不能丢）
asc.create_task("order-check-001", "检查上海最近30天销量")
asc.add_constraint("order-check-001", "只允许查询最近30天数据", level="HARD")

# 工具返回后记录事实（同 key 自动 LATEST_WINS 冲突消解）
asc.record_fact("order-check-001", "shanghai_sales", "120",
                source="tool:query_sales")

# 每轮决策前拿状态卡，拼进 prompt
try:
    card = asc.get_state_card("order-check-001", budget="small")
except AscNotFoundError:
    ...  # 任务不存在，先创建
prompt = str(card)  # 状态卡是 dict；框架示例见 examples/
```

## 错误处理

| 异常 | 触发 | 典型处理 |
|---|---|---|
| `AscNotFoundError` | 404（TASK_NOT_FOUND / CARD_NOT_FOUND） | 先建任务，或该版本未持久化 |
| `AscConflictError` | 409（TASK_ALREADY_EXISTS） | 换任务 ID |
| `AscError` | 其他 4xx/5xx | 按 `e.code` 分支 |

## 测试

```bash
cd sdk/python
python -m unittest discover tests -v
```

框架胶水示例：[examples/langgraph_example.py](../../examples/langgraph_example.py)、
[examples/crewai_example.py](../../examples/crewai_example.py)。
