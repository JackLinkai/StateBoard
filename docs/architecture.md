# ASC 架构文档

> Agent StateBoard（Agent State Compiler, ASC）——Agent 运行时状态治理层。
> 本文档描述 v0.6 的完整架构：分层、数据模型、关键机制与扩展点。

---

## 1. 分层架构

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
│  约束识别 / 冲突消解（SPI） / TTL 标记 / 权限视图 /      │
│  最小充分集裁剪                                          │
└──────────────────────┬──────────────────────────────────┘
                       │ Compiled State Contract
┌──────────────────────▼──────────────────────────────────┐
│  L3  State Governance（状态治理层）                      │
│  写入策略 / 三级压缩 / 多租户隔离 / 快照恢复 / 回放审计  │
└──────────────────────┬──────────────────────────────────┘
                       │ Governed State Views
┌──────────────────────▼──────────────────────────────────┐
│  L4  State Interfaces（状态接口层）                      │
│  REST / Java SDK / Python SDK / MCP / Dashboard          │
└─────────────────────────────────────────────────────────┘
```

对应代码分层（依赖方向由 ArchUnit 锁定，见
[ArchitectureTest](../src/test/java/io/asc/ArchitectureTest.java)）：

| 包 | 职责 | 依赖 |
|---|---|---|
| `io.asc.domain` | record 建模：Task / Constraint / Fact / Todo / InvalidatedItem / StateCard / Budget / CardView | 零框架 |
| `io.asc.core` | `StateCardCompiler` 纯规则 + `conflict` 冲突消解 SPI（3 实现） | domain |
| `io.asc.application` | 编排：TaskApplicationService / TaskLifecycleService / StateCardQueryService | domain, core, storage |
| `io.asc.storage` | JPA 实体 + Store 门面 + Redis HotStateCache | domain |
| `io.asc.web` | REST 控制器 + 全局异常 + DTO | application |
| `io.asc.client` | Java SDK（AscHttpClient） | domain（独立于服务端分层） |

---

## 2. 数据模型

### 2.1 State Contract（四格状态卡）

```
StateCard
├── taskId, version, compiledAt, estimatedTokens
├── hardConstraints : List<Constraint>   必须遵守（永不裁剪）
├── softConstraints : List<Constraint>   软约束（预算紧张先裁）
├── verifiedFacts   : List<Fact>         已确认事实（同 key 冲突消解后）
├── openTodos       : List<Todo>         待办（只保留 OPEN）
└── invalidated     : List<InvalidatedItem>  过期/冲突（EXPIRED/SUPERSEDED/CONFLICT/REJECTED）
```

- Token 估算：约 4 字符 ≈ 1 token，失效记录固定 20 token；
- 预算档位：SMALL=400 / MEDIUM=800 / LARGE=1200（`Budget`）；
- 编译是纯函数：`compile(taskId, items, now)` 同输入必同输出。

### 2.2 持久化（PostgreSQL，Flyway 迁移 `src/main/resources/db/migration`）

| 表 | 内容 |
|---|---|
| `task` | 任务主档：status（ACTIVE/SNAPSHOTTED/CLOSED）、version、ownerAgent、handoffCount、**tenant_id**（V4） |
| `state_item` | 全量状态项（四类共用，type 区分；version = 写入时的任务版本） |
| `state_card_version` | 每次查询/快照/交接持久化的全量卡（回放与审计的正式版本） |
| `task_snapshot` | 快照（恢复用） |

Redis：`HotStateCache` 按 `(taskId, version)` 缓存原始状态项，
TTL = `asc.compiler.soft-context-ttl`（默认 30m）；缓存的是原始项，编译每次都跑，TTL 判定不失真。

---

## 3. 关键机制

### 3.1 编译流水线（`StateCardCompiler.compile`）

顺序即语义，不可交换：

1. **分类 + TTL 过滤** —— 过期 Fact → `InvalidatedItem(EXPIRED)`（不参与"谁覆盖谁"）；
2. **冲突消解（SPI，可插拔）** —— 见 3.2；
3. **约束分格**（HARD/SOFT）+ **待办过滤**（只留 OPEN）+ **存量失效透传**；
4. 排序组卡，version 取状态项最大 version。

### 3.2 冲突消解策略（v0.5，SPI）

配置 `asc.compiler.fact-conflict-strategy`，策略实现在 `io.asc.core.conflict`：

| 策略 | 行为 |
|---|---|
| `LATEST_WINS`（默认） | 同 key 最新进卡（createdAt 并列比 version），旧条转 SUPERSEDED 指向胜者 |
| `MANUAL` | 冲突不裁决：该 key 全部候选转 CONFLICT 等人工确认，一格都不放 |
| `REJECT` | 写入侧拦截（同 key 异值 → 409 CONFLICT_DETECTED），编译侧兜底抛异常 |

扩展：实现 `FactConflictResolver` SPI + `@Component`，编译器零改动。
租户校验先于 REJECT 冲突检查（跨租户探测得到 404 而非 409）。

### 3.3 双维裁剪（v0.6）

```
全量卡 ──withView(view)──► 权限视图卡 ──trim(budget)──► 注入 Agent 的最终卡
         格子可见性                  条数与 token
         （权限维度）                （容量维度）
```

- **view（CardView）**：`FULL` 四格全量 / `LEAN` 执行态三格（硬约束+事实+待办）/
  `AUDIT` 审计两格（软约束+失效记录）；
- **trim（Budget）**：丢弃优先级 失效记录 → 软约束 → 待办 → 事实，硬约束永不裁；
- 先 view 后 budget：不可见的格子不占预算。

### 3.4 多租户（v0.6）

- 任务归属租户：`Task.tenantId`（缺省 `default`，Flyway V4 迁移 + 索引）；
- 身份声明：请求头 `X-ASC-Tenant`（web 层解析，缺省 default；
  Java SDK 构造时固定；Python SDK / MCP / Dashboard 全链路透传）；
- **隔离语义**：跨租户读写一律 `TaskNotFoundException`（404），
  与"任务不存在"同响应，不泄露其他租户的任务存在性；
- 恢复（restore）的新任务继承源任务租户；租户校验先于一切写入与冲突检查。

> ⚠️ `X-ASC-Tenant` 是**数据隔离**（分区视角），不是身份认证。
> 正式鉴权（token / mTLS / OIDC）在长期规划。

### 3.5 生命周期与治理

| 用例 | 语义 |
|---|---|
| snapshot | 编译全量卡落库 + 任务转 SNAPSHOTTED（保留 ownerAgent） |
| restore | 只复制活状态（失效历史不跨会话携带），新 ID 重生成、createdAt/source 保留溯源、新任务继承源租户 |
| replay | 取 `state_card_version` 历史版本；缺省最新一版 |
| handoff | ownerAgent 更新、handoffCount +1，返回全量卡给接手方直接注入 |
| close | 置 CLOSED（幂等），卡与快照保留 |
| updateTodo | 就地更新 status，版本 +1（DONE/CANCELLED 不再进卡，历史可回放） |

---

## 4. 接口层（L4）

| 接口 | 入口 | 租户 |
|---|---|---|
| REST | `/v1/tasks...`（TaskController） | `X-ASC-Tenant` 头 |
| Java SDK | `AscHttpClient.create(baseUrl, tenant)` | 构造时固定 |
| Python SDK | `AscClient(base_url, tenant=...)` | 构造时固定 |
| MCP | `asc_mcp_server.py`，env `ASC_TENANT` | 进程级固定 |
| Dashboard | 构建产物随服务部署，侧栏切租户 | localStorage |

错误码：`TASK_NOT_FOUND`(404) / `CARD_NOT_FOUND`(404) / `TASK_ALREADY_EXISTS`(409) /
`CONFLICT_DETECTED`(409) / `INVALID_ARGUMENT`(400) / `VALIDATION_FAILED`(400)。

---

## 5. 评测

[benchmarks/](../benchmarks/)：Baseline（全量历史 + 注意力窗口截断）vs ASC（状态卡注入），
五指标对比——约束违反率 / 任务完成率 / 输入 token / 重复工具调用 / 恢复重放步数。
Runner 用 Python SDK 连真实服务跑（MockAgent 确定性决策）。

---

## 6. Roadmap 摘要

- ✅ v0.1 核心服务 → v0.2 治理 → v0.3 生态接入 → v0.4 可观测与评测 →
  v0.5 冲突策略插件化 → v0.6 多租户与权限视图
- 长期：状态压缩三级折叠、跨任务经验复用、租户级正式鉴权
