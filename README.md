<div align="center">

# Agent StateBoard (Agent State Compiler(ASC))

**Agent 运行时状态治理层**

让 Agent 每一步都知道：什么必须遵守、什么已经确认、什么还没做、什么已经过期。

[![Status](https://img.shields.io/badge/status-early%20development-orange)](#项目状态)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-Apache--2.0-lightgrey)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen)](CONTRIBUTING.md)

</div>

---

## 目录

- [为什么需要 ASC](#为什么需要-asc)
- [ASC 是什么](#asc-是什么)
- [核心概念：State Contract](#核心概念state-contract)
- [架构](#架构)
- [快速开始](#快速开始)
- [核心 API](#核心-api)
- [接入 Agent](#接入-agent)
- [评测指标](#评测指标)
- [项目状态](#项目状态)
- [Roadmap](#roadmap)
- [贡献](#贡献)
- [License](#license)

---

## 为什么需要 ASC

Agent 在长周期、多工具、多轮对话任务中，失败往往不是因为“知识不够”，而是因为**运行时状态失控**：

| 问题 | 典型表现 |
|---|---|
| **硬约束丢失** | 用户一开始说“只查最近 30 天”，聊了十几轮后 Agent 忘了 |
| **事实冲突** | 工具第一次返回销量 100，第二次返回 120，模型引用错旧值 |
| **上下文膨胀** | 工具日志、检索片段、聊天历史全塞进去，关键信息被淹没 |
| **跨会话失忆** | 任务暂停后恢复，上一轮确认的约束、排除的路径全部归零 |
| **交接靠聊天记录** | Agent A 交给 Agent B，只能传整段历史，又长又乱 |
| **难以审计** | 出问题时无法回答“当时模型到底看到了什么状态” |

现有方案的边界：

- **编排框架**（LangGraph、CrewAI、AutoGen、Spring AI、LangChain4j）负责“怎么跑”；
- **记忆 / RAG**（mem0、RAGFlow 等）负责“记住什么、知道什么”；
- **但“当前这一刻，哪些状态有效、哪些过期、哪些必须遵守、下一步该基于什么决策”** —— 这一层是空的。

ASC 就是补这一层。

---

## ASC 是什么

ASC 站在 Agent 编排层之下、存储之上，接收 Agent 执行过程中产生的原始状态流，**编译**成结构化、可审计、可回放、可继承的 **State Contract（状态契约）**，并治理它的全生命周期。

它做四件事：

1. **采集**：从工具返回、模型输出、人工审批中抽取状态事件；
2. **编译**：区分硬约束 / 已验证事实 / 待办 / 过期冲突，检测冲突，按 token 预算生成最小状态卡；
3. **注入**：Agent 每轮决策前，给它一张“当前有效任务状态卡”，而不是整段历史；
4. **治理**：任务快照、跨会话恢复、多 Agent 交接、版本回放与审计。

**ASC 不替代编排，不替代 RAG，不替代普通聊天记忆。** 它只回答一个问题：

> **Agent 当前这一刻，应该基于什么状态做决策？**

---

## 核心概念：State Contract

ASC 每轮交给 Agent 的不是整段历史，而是一张四格状态卡：

| 格子 | 含义 | 示例 |
|---|---|---|
| **必须遵守**（Hard Constraints） | 不可覆盖的任务边界 | “只查最近 30 天”“无删除权限” |
| **已确认事实**（Verified Facts） | 工具或人工确认过的可信信息 | “上海上周销量 120”“city 已解析为上海” |
| **待办 / 待决**（Open Todos） | 下一步依赖的未完成事项 | “杭州数据待查”“库存异常待确认” |
| **过期 / 冲突**（Invalidated） | 不应再使用的信息 | “旧销量 100 已被 120 覆盖” |

Java 建模（示意）：

```java
public sealed interface StateItem permits Constraint, Fact, Todo, InvalidatedItem {
    String id();
    Instant createdAt();
    String source();
}

public record Constraint(String id, String text, ConstraintLevel level,
                         Instant createdAt, String source) implements StateItem {}

public record Fact(String id, String key, String value,
                   Instant createdAt, Instant expiresAt,
                   String source) implements StateItem {}

public record Todo(String id, String text, TodoStatus status,
                   Instant createdAt, String source) implements StateItem {}

public record InvalidatedItem(String id, String reason,
                              String supersededBy,
                              Instant createdAt, String source) implements StateItem {}

public record StateCard(String taskId, long version,
                        List<Constraint> hardConstraints,
                        List<Fact> verifiedFacts,
                        List<Todo> openTodos,
                        List<InvalidatedItem> invalidated,
                        int estimatedTokens) {}
```

---

## 架构

```
┌─────────────────────────────────────────────────────────┐
│  Agent 编排层                                            │
│  LangGraph / CrewAI / AutoGen / Spring AI / LangChain4j │
└──────────────────────┬──────────────────────────────────┘
                       │ State Delta（状态增量）
┌──────────────────────▼──────────────────────────────────┐
│  L1  State Ingestor（状态采集层）                        │
│  从工具调用、模型输出、人工反馈中抽取原始状态事件        │
└──────────────────────┬──────────────────────────────────┘
                       │ Raw State Events
┌──────────────────────▼──────────────────────────────────┐
│  L2  State Compiler（状态编译层）★ 核心                 │
│  约束识别 / 冲突消解 / TTL 标记 / 最小充分集裁剪         │
└──────────────────────┬──────────────────────────────────┘
                       │ Compiled State Contract
┌──────────────────────▼──────────────────────────────────┐
│  L3  State Governance（状态治理层）                      │
│  写入策略 / 三级压缩 / 跨会话继承 / 审计回放 / 权限视图  │
└──────────────────────┬──────────────────────────────────┘
                       │ Governed State Views
┌──────────────────────▼──────────────────────────────────┐
│  L4  State Interfaces（状态接口层）                      │
│  Model-facing / Program-facing / Human-facing / MCP      │
└─────────────────────────────────────────────────────────┘
```

存储：

- **PostgreSQL**：状态、版本、快照、审计；
- **Redis**：热状态、TTL、分布式锁。

---

## 快速开始

### 环境要求

- JDK 21+
- Docker & Docker Compose
- Maven 3.9+（或使用 `mvnw`）

### 启动服务

```bash
git clone https://github.com/<your-org>/asc.git
cd asc

# 启动 PostgreSQL + Redis + ASC Server
docker compose up -d

# 或本地开发
./mvnw spring-boot:run
```

默认端口：`8080`。健康检查：

```bash
curl http://localhost:8080/actuator/health
```

### 配置（`application.yml` 示意）

```yaml
asc:
  storage:
    postgres:
      url: jdbc:postgresql://localhost:5432/asc
      username: asc
      password: asc
    redis:
      host: localhost
      port: 6379
  compiler:
    default-budget-tokens: 1200
    fact-conflict-strategy: LATEST_WINS   # LATEST_WINS | MANUAL | REJECT
    soft-context-ttl: 30m
```

---

## 核心 API

### 1. 创建任务

```bash
curl -X POST http://localhost:8080/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"taskId": "order-check-001", "description": "检查上海最近30天销量"}'
```

### 2. 写入硬约束

```bash
curl -X POST http://localhost:8080/v1/tasks/order-check-001/constraints \
  -H "Content-Type: application/json" \
  -d '{
    "text": "只允许查询最近30天数据",
    "level": "HARD",
    "source": "user"
  }'
```

### 3. 写入工具返回事实

```bash
curl -X POST http://localhost:8080/v1/tasks/order-check-001/events \
  -H "Content-Type: application/json" \
  -d '{
    "type": "TOOL_RESULT",
    "tool": "query_sales",
    "payload": {
      "key": "shanghai_sales_last_7d",
      "value": "120",
      "expiresAt": "2026-10-26T00:00:00Z"
    }
  }'
```

### 4. 获取状态卡（Agent 决策前调用）

```bash
curl "http://localhost:8080/v1/tasks/order-check-001/state-card?budget=small"
```

响应示例：

```json
{
  "taskId": "order-check-001",
  "version": 7,
  "hardConstraints": [
    { "id": "c1", "text": "只允许查询最近30天数据", "level": "HARD" }
  ],
  "verifiedFacts": [
    { "id": "f3", "key": "shanghai_sales_last_7d", "value": "120" }
  ],
  "openTodos": [
    { "id": "t2", "text": "杭州数据待查", "status": "OPEN" }
  ],
  "invalidated": [
    { "id": "f1", "reason": "SUPERSEDED", "supersededBy": "f3" }
  ],
  "estimatedTokens": 312
}
```

### 5. 任务快照与恢复

```bash
# 结束任务，生成快照
curl -X POST http://localhost:8080/v1/tasks/order-check-001/snapshot

# 新会话按快照恢复
curl -X POST http://localhost:8080/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"taskId": "order-check-002", "restoreFrom": "order-check-001"}'
```

### 6. 版本回放

```bash
curl "http://localhost:8080/v1/tasks/order-check-001/replay?version=5"
```

---

## 接入 Agent

### Python（LangGraph / CrewAI）

通过 HTTP 桥接。在 LLM 调用前拉取状态卡，在工具返回后写入事件：

```python
import requests

ASC = "http://localhost:8080"

def get_state_card(task_id: str) -> str:
    r = requests.get(f"{ASC}/v1/tasks/{task_id}/state-card", params={"budget": "small"})
    return r.json()

def record_tool_result(task_id: str, tool: str, payload: dict):
    requests.post(f"{ASC}/v1/tasks/{task_id}/events", json={
        "type": "TOOL_RESULT", "tool": tool, "payload": payload
    })

# LangGraph 节点内
state_card = get_state_card("order-check-001")
prompt = f"{state_card}\n\n用户输入：{user_input}"
```

### Java（Spring AI / LangChain4j）

直接依赖 ASC Java SDK（开发中），或在工具调用前后调用 REST API：

```java
StateCard card = ascClient.getStateCard("order-check-001", Budget.SMALL);
String prompt = card.toPrompt() + "\n\n用户输入：" + userInput;
```

### MCP

ASC 计划暴露为 MCP Server，任意支持 MCP 的 Agent 可按需拉取状态：

```json
{
  "mcpServers": {
    "asc": {
      "command": "npx",
      "args": ["-y", "@asc/mcp-server"],
      "env": { "ASC_BASE_URL": "http://localhost:8080" }
    }
  }
}
```

> MCP Server 状态见 [Roadmap](#roadmap)。

---

## 评测指标

ASC 的目标不是“更花哨的上下文”，而是可验证的改善。评测对比两组：

- **Baseline**：原始历史 + 工具日志全量堆砌；
- **ASC**：每轮注入编译后的状态卡。

指标：

| 指标 | 说明 |
|---|---|
| **关键约束违反率** | 任务中违反硬约束的次数 / 任务数 |
| **任务完成率** | 端到端成功完成任务的比例 |
| **输入 token 消耗** | 每轮平均输入 token |
| **重复工具调用次数** | 相同工具 + 相同参数重复调用次数 |
| **恢复任务重放步数** | 跨会话恢复后回到原进度所需步数 |

> 评测数据集与脚本将在 MVP 阶段开源，见 [Roadmap](#roadmap)。

---

## 项目状态

**Early Development（早期开发）**。当前重点是验证核心概念：

- [x] 概念设计与架构定义
- [x] State Contract 四类状态建模
- [ ] Spring Boot 服务骨架 + REST API
- [ ] 状态编译核心（约束识别 / 冲突消解 / TTL / 预算裁剪）
- [ ] PostgreSQL + Redis 持久化
- [ ] 任务快照 / 恢复 / 回放
- [ ] LangGraph / CrewAI HTTP 适配示例
- [ ] Spring AI / LangChain4j 适配示例
- [ ] MCP Server
- [ ] 可视化调试面板
- [ ] 评测数据集与脚本

> 勾选状态请以实际仓库进度为准。

---

## Roadmap

### v0.1 — 核心服务（MVP）

- REST API：任务、约束、事件、状态卡
- 最简编译器：硬约束保留、事实覆盖、待办过滤、预算截断
- PostgreSQL 持久化 + 版本号
- Docker Compose 一键启动

### v0.2 — 治理能力

- 任务快照与跨会话恢复
- 多 Agent 状态交接
- 版本回放与审计 API
- Redis 热状态与 TTL

### v0.3 — 生态接入

- LangGraph / CrewAI 示例与 Python SDK
- Spring AI / LangChain4j Starter
- MCP Server

### v0.4 — 可观测与评测

- React 可视化面板
- 评测数据集与脚本
- 冲突消解策略插件化

### 长期

- 多租户与权限视图
- 状态压缩的三级折叠（会话 → 任务 → 经验）
- 跨任务经验复用

---

## 贡献

欢迎 Issue、Discussion 和 PR。请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

适合切入的方向：

- 状态编译策略（冲突消解、压缩、预算裁剪）
- Agent 框架适配器
- 评测数据集设计
- 文档与示例

如果这个方向对你有用，欢迎 Star 支持。

---

## License

[Apache-2.0](LICENSE)

---

<div align="center">

**Every step, a valid state.**
每一步，都有有效状态。

</div>
