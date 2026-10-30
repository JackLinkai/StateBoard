<div align="center">

# Agent StateBoard (Agent State Compiler(ASC))

**Agent 运行时状态治理层**

让 Agent 每一步都知道：什么必须遵守、什么已经确认、什么还没做、什么已经过期。

<p align="center">
  <img src="docs/assets/logo.jpg" alt="logo" width="360">
</p>

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

| 问题          | 典型表现                              |
|-------------|-----------------------------------|
| **硬约束丢失**   | 用户一开始说“只查最近 30 天”，聊了十几轮后 Agent 忘了 |
| **事实冲突**    | 工具第一次返回销量 100，第二次返回 120，模型引用错旧值   |
| **上下文膨胀**   | 工具日志、检索片段、聊天历史全塞进去，关键信息被淹没        |
| **跨会话失忆**   | 任务暂停后恢复，上一轮确认的约束、排除的路径全部归零        |
| **交接靠聊天记录** | Agent A 交给 Agent B，只能传整段历史，又长又乱   |
| **难以审计**    | 出问题时无法回答“当时模型到底看到了什么状态”           |

现有方案的边界：

- **编排框架**（LangGraph、CrewAI、AutoGen、Spring AI、LangChain4j）负责“怎么跑”；
- **记忆 / RAG**（mem0、RAGFlow 等）负责“记住什么、知道什么”；
- **但“当前这一刻，哪些状态有效、哪些过期、哪些必须遵守、下一步该基于什么决策”** —— 这一层是空的。

ASC 就是补这一层。

---

## ASC 是什么

ASC 站在 Agent 编排层之下、存储之上，接收 Agent 执行过程中产生的原始状态流，**编译**成结构化、可审计、可回放、可继承的 **State
Contract（状态契约）**，并治理它的全生命周期。

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

| 格子                         | 含义            | 示例                        |
|----------------------------|---------------|---------------------------|
| **必须遵守**（Hard Constraints） | 不可覆盖的任务边界     | “只查最近 30 天”“无删除权限”        |
| **已确认事实**（Verified Facts）  | 工具或人工确认过的可信信息 | “上海上周销量 120”“city 已解析为上海” |
| **待办 / 待决**（Open Todos）    | 下一步依赖的未完成事项   | “杭州数据待查”“库存异常待确认”         |
| **过期 / 冲突**（Invalidated）   | 不应再使用的信息      | “旧销量 100 已被 120 覆盖”       |

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
git clone https://github.com/JackLinkai/StateBoard.git
cd StateBoard

# 启动 PostgreSQL + Redis + ASC Server
docker compose up -d

# 或本地开发
./mvnw spring-boot:run
```

默认端口：`8080`。健康检查：

```bash
curl http://localhost:8080/actuator/health
```

### 可视化面板（v0.4）

```bash
cd dashboard && npm install && npm run build   # 产物输出到 src/main/resources/static
# 重启 asc-server 后访问 http://localhost:8080 即可
```

开发模式：`npm run dev`（Vite 5173 端口，`/v1` 自动代理到 8080）。

面板功能：任务列表与创建/恢复、状态卡四格视图、待办一键完成/取消、快照与交接、版本条点击回放。

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
curl -X POST http://localhost:8080/v1/tasks/order-check-001/facts \
  -H "Content-Type: application/json" \
  -d '{
    "key": "shanghai_sales_last_7d",
    "value": "120",
    "expiresAt": "2026-10-26T00:00:00Z",
    "source": "tool:query_sales"
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

### 6. 待办状态流转 / 任务列表 / 版本列表

```bash
# 待办完成（不再注入状态卡）：PATCH /v1/tasks/{id}/todos/{todoId}
curl -X PATCH http://localhost:8080/v1/tasks/order-check-001/todos/t2 \
  -H "Content-Type: application/json" \
  -d '{"status": "DONE"}'

# 任务列表（面板 / 运维）
curl http://localhost:8080/v1/tasks

# 单个任务详情
curl http://localhost:8080/v1/tasks/order-check-001

# 某任务全部已持久化的状态卡版本（回放面板 / 审计）
curl http://localhost:8080/v1/tasks/order-check-001/versions

# 关闭任务（幂等）：置 CLOSED，快照与回放保留
curl -X POST http://localhost:8080/v1/tasks/order-check-001/close
```

### 7. 版本回放

```bash
curl "http://localhost:8080/v1/tasks/order-check-001/replay?version=5"
```

---

## 接入 Agent

### Python（LangGraph / CrewAI）

安装 SDK（仅依赖 requests）：

```bash
cd sdk/python && pip install .
```

在 LLM 调用前拉取状态卡，在工具返回后写入事件：

```python
from asc import AscClient

asc = AscClient("http://localhost:8080", tenant="team-a")   # 多租户：随请求走 X-ASC-Tenant 头
asc.add_constraint("order-check-001", "只允许查询最近30天数据", level="HARD")

# 每轮决策前（view="lean" 只注入执行态三格，省 token；缺省 full）
card = asc.get_state_card("order-check-001", budget="small", view="lean")
prompt = str(card) + "\n\n用户输入：" + user_input

# 工具返回后（同 key 自动 LATEST_WINS 冲突消解）
asc.record_fact("order-check-001", "shanghai_sales", "120", source="tool:query_sales")
```

完整胶水示例：[examples/langgraph_example.py](examples/langgraph_example.py)、[examples/crewai_example.py](examples/crewai_example.py)。

### Java（Spring AI / LangChain4j）

Java SDK 在 `io.asc.client` 包内（服务同仓库直接可用）：

```java
AscClient asc = AscHttpClient.create("http://localhost:8080", "team-a"); // 多租户
asc.recordFact(taskId, "shanghai_sales", "120", null, "tool:query_sales");

StateCard card = asc.getStateCard("order-check-001", Budget.SMALL, CardView.LEAN);
String prompt = card.toPrompt() + "\n\n用户输入：" + userInput;
```

服务端 4xx/5xx 抛 `AscApiException`（含业务错误码，如 `TASK_NOT_FOUND`）。

### MCP

任意支持 MCP 的客户端（Trae / Claude Desktop / 自研）按需拉取状态：

```json
{
  "mcpServers": {
    "asc": {
      "command": "python",
      "args": ["mcp/asc_mcp_server.py"],
      "env": { "ASC_BASE_URL": "http://localhost:8080", "ASC_TENANT": "team-a" }
    }
  }
}
```

依赖：`pip install "mcp>=2"`。提供 11 个工具：create_task / add_constraint / record_fact / record_todo / update_todo / close_task / get_state_card / snapshot / restore_task / replay / handoff。

---

## 评测指标

ASC 的目标不是“更花哨的上下文”，而是可验证的改善。评测对比两组：

- **Baseline**：原始历史 + 工具日志全量堆砌；
- **ASC**：每轮注入编译后的状态卡。

指标：

| 指标              | 说明                |
|-----------------|-------------------|
| **关键约束违反率**     | 任务中违反硬约束的次数 / 任务数 |
| **任务完成率**       | 端到端成功完成任务的比例      |
| **输入 token 消耗** | 每轮平均输入 token      |
| **重复工具调用次数**    | 相同工具 + 相同参数重复调用次数 |
| **恢复任务重放步数**    | 跨会话恢复后回到原进度所需步数   |

> 评测数据集与脚本已随 v0.4 开源：[benchmarks/](benchmarks/)。

运行（需先启动 asc-server）：

```bash
python benchmarks/runner.py --window 350 --json-out results.json
```

数据集在 [benchmarks/datasets/](benchmarks/datasets/)（JSONL，每行一个场景），当前包含三类场景：

| 场景                        | 考察点                                       |
|---------------------------|--------------------------------------------|
| `constraint_erosion`      | 开场约束被多轮干扰稀释后，Agent 是否还守得住边界 |
| `fact_conflict`           | 数仓修正后，Agent 是否引用唯一有效值而不是旧值    |
| `resume_progress`         | 跨会话恢复后，回原进度要几步、重复查询有多少      |

MockAgent 是确定性规则解析器（无 LLM），两组行为差异完全由"注入内容的质量"驱动。一次实测（窗口 350 字符，3 场景）：

| 指标              | Baseline | ASC   |
|-----------------|----------|-------|
| 约束违反率/场景    | 0.33     | **0** |
| 重复工具调用总数   | 3        | **0** |
| 平均每轮输入 token | 161      | **47** |
| 恢复重放步数总数   | 2        | **1** |

---

## 项目状态

**Early Development（早期开发）**。当前重点是验证核心概念：

- [x] 概念设计与架构定义
- [x] State Contract 四类状态建模
- [x] Spring Boot 服务骨架 + REST API
- [x] 状态编译核心（约束识别 / 冲突消解 / TTL / 预算裁剪）
- [x] PostgreSQL + Redis 持久化
- [x] 任务快照 / 恢复 / 回放
- [x] 多 Agent 状态交接
- [x] LangGraph / CrewAI HTTP 适配示例（[examples/](examples/)）
- [x] Spring AI / LangChain4j 适配示例（Java SDK `io.asc.client`）
- [x] MCP Server（[mcp/asc_mcp_server.py](mcp/asc_mcp_server.py)）
- [x] 可视化调试面板（[dashboard/](dashboard/)，构建后随服务访问 `http://localhost:8080`）
- [x] 评测数据集与脚本（[benchmarks/](benchmarks/)）
- [x] 冲突消解策略插件化（LATEST_WINS / MANUAL / REJECT）
- [x] 多租户隔离（`X-ASC-Tenant` 请求头）与状态卡权限视图（full / lean / audit）

> 勾选状态请以实际仓库进度为准。详细设计见 [docs/architecture.md](docs/architecture.md)

---

## Roadmap

### v0.1 — 核心服务（MVP）✅

- REST API：任务、约束、事件、状态卡
- 最简编译器：硬约束保留、事实覆盖、待办过滤、预算截断
- PostgreSQL 持久化 + 版本号
- Docker Compose 一键启动

### v0.2 — 治理能力 ✅

- 任务快照与跨会话恢复
- 多 Agent 状态交接
- 版本回放与审计 API
- Redis 热状态与 TTL

### v0.3 — 生态接入 ✅

- LangGraph / CrewAI 示例与 Python SDK（[sdk/python](sdk/python/)）
- Spring AI / LangChain4j Starter（Java SDK `io.asc.client`）
- MCP Server（[mcp/asc_mcp_server.py](mcp/asc_mcp_server.py)）

### v0.4 — 可观测与评测 ✅

- React 可视化面板（任务列表 / 状态卡四格视图 / 版本回放 / 待办流转 / 交接）
- 评测数据集与脚本（[benchmarks/](benchmarks/)，Baseline vs ASC 五指标对比）
- 任务列表 / 待办状态流转 / 版本列表 API（`GET /v1/tasks`、`PATCH /v1/tasks/{id}/todos/{id}`、`GET /v1/tasks/{id}/versions`）
- Dockerfile + Docker Compose 一键启动 asc-server

### v0.5 — 编译策略演进 ✅

- **冲突消解策略插件化**：配置 `asc.compiler.fact-conflict-strategy` 一键切换——
  - `LATEST_WINS`（默认）：同 key 最新事实进卡，旧值转 SUPERSEDED，可看到完整演变链；
  - `MANUAL`：冲突不自动裁决，该 key 全部候选转 CONFLICT 等人工确认，宁可缺不可错；
  - `REJECT`：写入时就拒绝同 key 异值（409 `CONFLICT_DETECTED`），编译侧兜底拦截；
  - 扩展：实现 [FactConflictResolver](src/main/java/io/asc/core/conflict/FactConflictResolver.java) SPI + `@Component` 即注册新策略，编译器零改动。

### v0.6 — 多租户与权限视图 ✅

- **多租户隔离**：任务归属租户（Flyway V4 迁移），请求头 `X-ASC-Tenant` 声明身份，
  缺省 `default`（存量调用方零改动）。跨租户读写一律 404（与任务不存在同响应，防探测）；
  恢复的新任务继承源任务租户；REST / SDK / MCP / 面板全链路支持。
- **状态卡权限视图**：`GET state-card?view=`——
  - `full`（默认）：四格全量；
  - `lean`：只留必须遵守 / 已确认事实 / 待办，给 token 敏感的执行型 Agent；
  - `audit`：只留过期/冲突 / 软约束，给审计回看。
  view 决定格子可见性（权限维度），budget 决定 token 上限（容量维度）：
  先 view 裁格、再 budget 裁条，不可见的格子不占预算。

### v0.7 — 真实 LLM 端到端评测（规划中）

当前评测（v0.4）的决策器是**确定性 MockAgent**（规则解析，无 LLM）：它隔离了模型变量，
证明了注入层本身的收益（token -70.8%、约束违反 33.3%→0%），但测不了模型的随机行为。
v0.7 把决策器换成真实 LLM，在端到端链路上验证并量化同样的结论。

- **实验设计**：对照组不变（Baseline 全量历史 vs ASC 状态卡注入），同一模型、温度 0、
  结构化决策输出（EXECUTE / REJECT / SKIP + 引用的事实值）；判分优先规则化
  （查询参数是否越界、引用值是否等于工具真值），模糊情形才引入 LLM-as-judge（与被测模型不同源）；
- **指标扩展**（在现有五指标之上）：
  - 真实约束违反率：模型实际发起越界查询的比例；
  - **过期事实引用率**：引用已被 SUPERSEDED / EXPIRED 值的次数（LLM 特有失效模式）；
  - 真实计费成本：prompt + completion 按模型定价折算；
  - 完成率以均值 ± 方差报告（每场景 N≥5 次重复）；
- **模型矩阵**：闭源旗舰（GPT-4o / Claude 级）+ 国产（DeepSeek / Qwen 级）+ 7B-14B 小模型，
  覆盖 8k / 128k / 1M 上下文档位——重点回答"**上下文越长，ASC 的收益还剩多少**"
  （对照无截断实验的 -68.0%，验证长上下文模型下成本节省是否依然成立）；
- **待验证假设**：
  1. 失效格标注（EXPIRED / SUPERSEDED）能否显著降低模型对旧值的引用；
  2. `lean` 视图裁掉软约束与失效格后，完成率损失是否可接受（view 裁格的收益/风险量化）；
  3. 干扰文本密度与轮次增加时，状态卡相对全量历史的优势是扩大还是收敛；
- **阶段**：
  - L1（MVP）：1 个模型 × 3 场景 × 5 次重复，跑通结构化输出解析 / 自动判分 / 成本统计管线；
  - L2：模型矩阵 × 场景扩充（干扰密度梯度、30+ 轮长任务）；
  - L3：真实多工具任务（非玩具场景），评测结果页开放可复现脚本与原始数据；
- **交付物**：`benchmarks/runner.py --agent llm` 扩展、评测报告与原始数据入库、技术文章一篇。

### 长期

- 状态压缩的三级折叠（会话 → 任务 → 经验）
- 跨任务经验复用
- 租户级正式鉴权（当前以 `X-ASC-Tenant` 头做数据隔离，非身份认证）

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


---

---

## ❤️ 支持本项目

ASC 是一个由社区驱动的开源项目。如果它对你的工作有帮助，欢迎支持我们的持续开发与维护。

<p align="center">
  <img src="docs/assets/wechat-qr.png" alt="微信赞赏码" width="180">
  <img src="docs/assets/alipay-qr.png" alt="支付宝收款码" width="180">
</p>
