# ASC 架构设计

本文描述 Agent StateBoard（ASC）的设计目标、分层职责、数据模型、核心流程、存储模型和扩展点。

> 状态：早期设计。标注“已实现 / 设计中 / 规划中”的条目以仓库实际进度为准。

---

## 目录

- [设计目标与非目标](#设计目标与非目标)
- [设计原则](#设计原则)
- [分层架构](#分层架构)
- [数据模型](#数据模型)
- [状态编译流程](#状态编译流程)
- [状态治理](#状态治理)
- [存储模型](#存储模型)
- [接口设计](#接口设计)
- [扩展点](#扩展点)
- [失败模式与权衡](#失败模式与权衡)
- [开放问题](#开放问题)

---

## 设计目标与非目标

### 目标

1. 让 Agent 每轮决策前拿到**最小充分、当前有效**的状态，而不是整段历史。
2. 让硬约束**不被工具日志和历史淹没**。
3. 让新旧事实冲突**可检测、可消解、可溯源**。
4. 让任务状态**可快照、可恢复、可交接、可回放**。
5. 与 Agent 框架**解耦**：通过 HTTP / MCP 接入，不绑定 LangGraph、CrewAI、Spring AI 等。
6. 策略**可插拔**：冲突消解、预算裁剪、约束分类、视图渲染都可替换。

### 非目标

- 不做 Agent 编排：不决定下一步调用什么工具。
- 不做 RAG / 文档检索：不解析文档、不做向量检索。
- 不做通用聊天记忆：不负责“用户喜欢什么”这类长期偏好。
- 不做模型调用：不封装 LLM SDK。
- 不做通用工作流引擎：不替代 Temporal、Camunda 等。

---

## 设计原则

1. **状态是一等公民**：运行时状态不是日志的副产品，而是需要被编译和治理的产物。
2. **编译而非堆砌**：每轮输出的是筛选后的状态卡，不是原始事件流。
3. **不可变与版本化**：`StateItem` 和 `StateCard` 不可变；每次编译产生新版本。
4. **溯源优先**：每个状态项都带 `source` 和 `createdAt`；被覆盖的事实进入 `invalidated`，不直接删除。
5. **策略外置**：编译器只负责流程编排，具体判定交给可插拔策略。
6. **框架无关**：核心不依赖任何 Agent 框架；适配器只做协议转换。

---

## 分层架构

```
Agent 编排层（LangGraph / CrewAI / AutoGen / Spring AI / LangChain4j）
        │  HTTP / MCP / SDK
        ▼
┌──────────────────────────────────────────────┐
│ L1 State Ingestor                            │
│  - 接收工具返回、模型输出、人工审批事件       │
│  - 归一化为 RawStateEvent                    │
├──────────────────────────────────────────────┤
│ L2 State Compiler                            │
│  - 约束分类 / 事实提取 / 冲突消解 / TTL 标记  │
│  - 预算裁剪，输出 StateCard                   │
├──────────────────────────────────────────────┤
│ L3 State Governance                          │
│  - 快照 / 恢复 / 交接 / 回放 / 审计 / 权限视图│
├──────────────────────────────────────────────┤
│ L4 State Interfaces                          │
│  - Model-facing / Program-facing / Human / MCP│
└──────────────────────────────────────────────┘
        │
        ▼
PostgreSQL（持久化 + 版本 + 审计）  Redis（热状态 + TTL + 锁）
```

### 各层职责

| 层 | 职责 | 不做什么 |
|---|---|---|
| L1 Ingestor | 事件接收、格式归一化、幂等去重 | 不判断语义 |
| L2 Compiler | 分类、冲突消解、TTL、预算裁剪、生成状态卡 | 不持久化、不直接调模型 |
| L3 Governance | 快照、恢复、交接、回放、权限视图 | 不改变编译语义 |
| L4 Interfaces | 面向模型/程序/人/MCP 的视图 | 不承载业务逻辑 |

---

## 数据模型

### StateItem（已设计）

```java
public sealed interface StateItem
        permits Constraint, Fact, Todo, InvalidatedItem {
    String id();
    String taskId();
    Instant createdAt();
    String source();          // user / tool:xxx / model / human
    long version();
}
```

四类实现：

```java
public record Constraint(
        String id, String taskId, String text,
        ConstraintLevel level,   // HARD | SOFT
        Instant createdAt, String source, long version
) implements StateItem {}

public record Fact(
        String id, String taskId, String key, String value,
        Instant createdAt, Instant expiresAt,   // 可空
        String source, long version
) implements StateItem {}

public record Todo(
        String id, String taskId, String text,
        TodoStatus status,       // OPEN | DONE | CANCELLED
        Instant createdAt, String source, long version
) implements StateItem {}

public record InvalidatedItem(
        String id, String taskId,
        String reason,           // SUPERSEDED | EXPIRED | CONFLICT | REJECTED
        String supersededBy,     // 可空
        Instant createdAt, String source, long version
) implements StateItem {}
```

### StateCard（已设计）

```java
public record StateCard(
        String taskId,
        long version,
        List<Constraint> hardConstraints,
        List<Constraint> softConstraints,
        List<Fact> verifiedFacts,
        List<Todo> openTodos,
        List<InvalidatedItem> invalidated,
        int estimatedTokens,
        Instant compiledAt
) {}
```

### Task 与 Snapshot（设计中）

```java
public record Task(
        String taskId,
        String description,
        TaskStatus status,       // ACTIVE | SNAPSHOTTED | CLOSED
        Instant createdAt,
        Instant updatedAt
) {}

public record Snapshot(
        String snapshotId,
        String taskId,
        long sourceVersion,
        StateCard card,
        Map<String, Object> metadata,
        Instant createdAt
) {}
```

---

## 状态编译流程

编译器（`StateCompiler`）每轮按以下顺序执行：

```
1. Load       加载任务当前有效状态项（Redis 热状态优先，回退 Postgres）
2. Ingest     合并本轮新事件（工具返回、模型输出、人工写入）
3. Normalize  统一格式，幂等去重
4. Classify   分类：约束 / 事实 / 待办 / 无效项
5. Resolve    冲突消解（同 key 事实覆盖、约束冲突标记）
6. Expire     应用 TTL，过期项进入 invalidated
7. Prioritize 按优先级排序：硬约束 > 待办 > 事实 > 软约束 > 无效项摘要
8. Trim       按 token 预算裁剪
9. Render     生成 StateCard，写入版本，更新热状态
```

### 优先级与裁剪规则

默认优先级（可通过 `BudgetTrimmer` 替换）：

1. **硬约束**：永远全量保留，不参与裁剪。
2. **待办 / 待决**：按 `createdAt` 倒序，保留未完成项。
3. **已验证事实**：按 key 分组，保留最新有效值；低优先级 key 可摘要。
4. **软约束**：可摘要或截断。
5. **无效项**：默认只保留最近 N 条摘要，用于提示“不要再用旧值”。

裁剪粒度：

- 事实按 `key` 整体保留或整体丢弃，不截断单个 value。
- 软约束可截断文本，但保留 `id` 和 `source`。
- 无效项只保留 `reason` 和 `supersededBy`，不保留完整旧值。

### Token 估算

- 默认使用轻量估算：字符数 / 4（中英混合偏保守）。
- 预留 `TokenEstimator` 接口，后续可接入模型 tokenizer。
- 估算误差通过预算缓冲（默认 10%）吸收。

### 冲突消解（默认策略）

`ConflictResolver` 默认实现 `LatestWinsResolver`：

- 同一 `key` 的新事实覆盖旧事实；
- 旧事实进入 `invalidated`，`reason=SUPERSEDED`，`supersededBy=新事实 id`；
- 硬约束冲突不自动消解，标记 `CONFLICT` 并保留双方，由上层决策或人工处理；
- 软约束冲突保留最新，旧值进入 `invalidated`。

可替换策略：`ManualResolver`（挂起等待人工）、`RejectNewResolver`（拒绝新值并告警）。

---

## 状态治理

### 快照（设计中）

任务结束时（或按策略定时）生成 `Snapshot`：

- 包含当前 `StateCard`；
- 包含 `sourceVersion`，用于追溯；
- `metadata` 存放任务级信息（如已排除路径、工具使用统计）。

快照不可变，只能新增。

### 恢复（设计中）

新任务通过 `restoreFrom` 引用快照：

- 硬约束：全量继承，标记 `source=snapshot:<id>`；
- 事实：继承未过期项，重新计算 TTL；
- 待办：继承 `OPEN` 项；
- 无效项：只继承摘要，避免上下文膨胀。

恢复不修改源快照。

### 交接（规划中）

多 Agent 交接时，接收方通过 `handoff` 接口拉取状态卡：

- 可按角色过滤视图（如只给执行 Agent 待办和事实，不给审计信息）；
- 交接记录写入 `event_log`，用于回放。

### 回放（设计中）

基于 `event_log` + 版本号：

- `GET /v1/tasks/{id}/replay?version=N` 返回该版本的 `StateCard`；
- 回放只读，不触发编译；
- 用于排查“当时模型看到了什么”。

### 权限视图（规划中）

不同调用方看到不同视图：

- `model`：最小状态卡；
- `program`：完整结构化状态；
- `human`：含溯源、冲突历史和无效项详情；
- `auditor`：只读全量版本和事件日志。

---

## 存储模型

### PostgreSQL（设计中）

```sql
CREATE TABLE task (
    task_id      TEXT PRIMARY KEY,
    description  TEXT,
    status       TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE state_item (
    id           TEXT PRIMARY KEY,
    task_id      TEXT NOT NULL REFERENCES task(task_id),
    type         TEXT NOT NULL,        -- CONSTRAINT | FACT | TODO | INVALIDATED
    payload      JSONB NOT NULL,
    source       TEXT NOT NULL,
    version      BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_state_item_task_version
    ON state_item (task_id, version);

CREATE TABLE state_card_version (
    task_id      TEXT NOT NULL REFERENCES task(task_id),
    version      BIGINT NOT NULL,
    card         JSONB NOT NULL,
    estimated_tokens INT NOT NULL,
    compiled_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (task_id, version)
);

CREATE TABLE snapshot (
    snapshot_id  TEXT PRIMARY KEY,
    task_id      TEXT NOT NULL REFERENCES task(task_id),
    source_version BIGINT NOT NULL,
    card         JSONB NOT NULL,
    metadata     JSONB,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE event_log (
    id           BIGSERIAL PRIMARY KEY,
    task_id      TEXT NOT NULL,
    event_type   TEXT NOT NULL,
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL
);
```

### Redis（设计中）

| Key | 用途 | TTL |
|---|---|---|
| `asc:task:{taskId}:hot` | 当前有效状态项缓存 | 与任务活跃期一致 |
| `asc:task:{taskId}:version` | 当前版本号 | 无 |
| `asc:lock:{taskId}` | 编译与写入锁 | 短 TTL，防死锁 |

一致性策略：**Postgres 为准，Redis 为缓存**。编译时先写 Postgres，再更新 Redis；恢复时以 Postgres 为准。

---

## 接口设计

### REST（已设计，路径以 README 为准）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/tasks` | 创建任务，支持 `restoreFrom` |
| POST | `/v1/tasks/{id}/constraints` | 写入约束 |
| POST | `/v1/tasks/{id}/events` | 写入事件（工具返回、事实、待办） |
| GET | `/v1/tasks/{id}/state-card` | 获取状态卡，支持 `budget` |
| POST | `/v1/tasks/{id}/snapshot` | 生成快照 |
| GET | `/v1/tasks/{id}/replay` | 按版本回放 |
| GET | `/actuator/health` | 健康检查 |

约定：

- 所有写接口幂等：客户端可带 `Idempotency-Key`。
- `state-card` 返回 `version`，客户端可据此判断是否过期。
- 错误响应统一 `{ "code": "...", "message": "...", "traceId": "..." }`。

### MCP（规划中）

ASC 作为 MCP Server 暴露工具：

- `asc.get_state_card`
- `asc.record_event`
- `asc.add_constraint`
- `asc.snapshot`
- `asc.replay`

MCP 只做协议适配，复用同一套应用服务。

---

## 扩展点

| 接口 | 职责 | 默认实现 | 状态 |
|---|---|---|---|
| `ConflictResolver` | 同 key 事实 / 约束冲突消解 | `LatestWinsResolver` | 设计中 |
| `BudgetTrimmer` | 按 token 预算裁剪状态卡 | `PriorityBudgetTrimmer` | 设计中 |
| `ConstraintClassifier` | 判断文本是否为硬约束 | `RuleBasedClassifier` | 设计中 |
| `TokenEstimator` | token 估算 | `CharBasedEstimator` | 设计中 |
| `StateViewRenderer` | 面向不同调用方的视图渲染 | `DefaultViewRenderer` | 规划中 |
| `SnapshotPolicy` | 何时生成快照 | `OnTaskClosePolicy` | 规划中 |

扩展方式：实现接口 + 注册为 Spring Bean，通过配置选择默认策略。

---

## 失败模式与权衡

### 已知失败模式

1. **token 估算不准**：字符估算对代码和 JSON 偏差较大，可能导致状态卡超预算。
   - 缓解：预算缓冲、可替换 `TokenEstimator`、超预算时按优先级二次裁剪。
2. **冲突消解语义错误**：`LatestWins` 在“新值不可信”场景下会覆盖正确旧值。
   - 缓解：硬约束冲突不自动消解；支持 `ManualResolver`；所有覆盖写 `invalidated` 可追溯。
3. **多 Agent 并发写**：同一任务多写者可能产生版本竞争。
   - 缓解：Redis 分布式锁 + 乐观版本号；冲突时返回 409。
4. **状态卡与原始日志不一致**：裁剪或摘要后，模型看到的状态与日志不完全对应。
   - 缓解：状态卡带 `version`，回放基于 `event_log`，审计时以版本为准。
5. **恢复后 TTL 失真**：快照中的事实恢复时 TTL 需要重算，可能过早或过晚过期。
   - 缓解：恢复策略可配置（继承剩余 TTL / 重置 TTL / 标记待确认）。

### 设计权衡

- **编译 vs 检索**：ASC 选择每轮编译状态卡，而不是让模型按需检索。代价是编译开销，收益是状态可控、可审计。
- **强类型 vs 灵活**：Java `sealed interface` 保证类型封闭，但新增状态类型需要改代码。接受这一代价，换取编译期安全。
- **Postgres 为准 vs Redis 为准**：选择 Postgres 为准，Redis 只做缓存，牺牲部分延迟换取一致性。
- **框架无关 vs 深度集成**：选择 HTTP/MCP 解耦，牺牲部分进程内性能，换取跨语言、跨框架可用。

---

## 开放问题

1. **约束分类的边界**：哪些自然语言表述算硬约束？纯规则能否覆盖，是否需要模型辅助？
2. **预算裁剪的公平性**：多事实场景下，按 key 保留还是按重要性打分？打分依据是什么？
3. **跨任务经验复用**：如何把“任务 A 踩过的坑”折叠成任务 B 可用的状态，而不引入噪声？
4. **多 Agent 交接的权限模型**：交接时哪些状态可见、哪些必须脱敏？
5. **评测基准**：如何构造既真实又可复现的长周期多工具任务集？

欢迎在 Discussions 中就以上问题展开讨论。
