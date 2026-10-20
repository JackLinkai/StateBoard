# Contributing to ASC

感谢你愿意参与 Agent StateBoard（ASC）。这是一个早期项目，我们更看重**方向正确、边界清晰、可验证**，而不是功能数量。

在提交 Issue 或 PR 之前，请先花几分钟读完本文。

---

## 目录

- [行为准则](#行为准则)
- [适合贡献的方向](#适合贡献的方向)
- [报告问题](#报告问题)
- [提交 PR](#提交-pr)
- [开发环境](#开发环境)
- [代码规范](#代码规范)
- [提交信息规范](#提交信息规范)
- [测试要求](#测试要求)
- [文档要求](#文档要求)
- [适配器贡献指南](#适配器贡献指南)
- [评测贡献指南](#评测贡献指南)

---

## 行为准则

- 对事不对人，技术讨论欢迎直接，但不要人身攻击。
- 不欢迎“这个项目没意义”式的空泛否定；如果你认为方向有问题，请给出具体的失败场景或替代方案。
- 尊重不同技术栈的选择。ASC 核心用 Java，但不排斥 Python/TypeScript 生态的适配贡献。

---

## 适合贡献的方向

按优先级从高到低：

1. **状态编译策略**：冲突消解、TTL 判定、预算裁剪、约束分类。
2. **Agent 框架适配器**：LangGraph、CrewAI、Spring AI、LangChain4j、AutoGen、自研框架。
3. **评测数据集与脚本**：长周期多工具任务、约束违反检测、token 统计。
4. **存储与治理**：快照、恢复、交接、回放、审计。
5. **文档与示例**：让新用户 10 分钟内跑起来。
6. **可视化面板**：React 调试界面。

如果你不确定某个方向是否合适，先开一个 Discussion，不要直接写大 PR。

---

## 报告问题

提 Issue 时请尽量包含：

- **环境**：JDK 版本、OS、Docker 版本、ASC 版本 / commit。
- **复现步骤**：最小可复现的命令或代码。
- **期望行为 vs 实际行为**。
- **日志**：相关堆栈或 API 响应，注意脱敏。
- **是否与状态卡内容相关**：如果是，请附上出问题时的 `state-card` 响应（脱敏后）。

Bug 模板和 Feature Request 模板见 `.github/ISSUE_TEMPLATE/`。

---

## 提交 PR

流程：

1. Fork 仓库，从 `main` 切出分支：`feat/xxx`、`fix/xxx`、`docs/xxx`。
2. 保持 PR 聚焦：一个 PR 只做一件事。大改动请先开 Issue 或 Discussion 对齐。
3. 确保 `./mvnw verify` 通过。
4. 更新相关文档和测试。
5. PR 描述里写清：**做了什么、为什么、怎么验证、影响范围**。
6. 如果 PR 涉及 API 或数据模型变更，请同步更新 `docs/architecture.md` 和 README 的 API 示例。

我们会尽量在 7 天内回复。早期项目维护者精力有限，催 PR 请礼貌。

---

## 开发环境

### 依赖

- JDK 21+
- Maven 3.9+（仓库自带 `mvnw`）
- Docker & Docker Compose

### 启动本地依赖

```bash
docker compose up -d postgres redis
```

### 运行服务

```bash
./mvnw spring-boot:run
```

### 运行测试

```bash
./mvnw verify
```

集成测试使用 Testcontainers 启动 PostgreSQL 和 Redis，需要本机 Docker 可用。

### 常用命令

```bash
./mvnw spotless:apply     # 格式化
./mvnw test               # 单元测试
./mvnw verify             # 单元 + 集成测试 + 格式检查
```

---

## 代码规范

### Java

- 语言级别：Java 21。
- 格式：Spotless + Google Java Format，提交前跑 `./mvnw spotless:apply`。
- 领域模型优先使用 `record` 和 `sealed interface`，保持不可变。
- 状态相关类型放在 `io.asc.core.state` 包下，不要和 Web/存储层耦合。
- 策略类实现统一接口，通过 Spring 注入，不要在编译器里写 `if/else` 堆策略。
- 公共 API 必须有 Javadoc，说明**语义**和**边界**，不要只写“getter”。

### 分层约束

```
web / adapter  →  application  →  core（domain + compiler + governance）  →  storage
```

- `core` 不依赖 Spring Web、不依赖具体存储实现。
- `storage` 通过接口暴露给 `core`，便于替换。
- 适配器（LangGraph、Spring AI 等）只做协议转换，不写业务逻辑。

### 命名

- 领域术语统一：`StateItem`、`StateCard`、`Constraint`、`Fact`、`Todo`、`InvalidatedItem`、`Snapshot`。
- 不要引入同义词（比如 `Memory`、`Context`）描述状态卡里的内容，避免概念漂移。

---

## 提交信息规范

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
feat(compiler): add TTL-based invalidation for soft facts
fix(api): return 404 when task not found
docs(readme): clarify MCP status
test(governance): cover snapshot restore with conflicting facts
```

类型：`feat`、`fix`、`docs`、`test`、`refactor`、`chore`、`perf`。

范围建议：`compiler`、`governance`、`storage`、`api`、`adapter-*`、`docs`。

---

## 测试要求

- 新功能必须有单元测试；涉及存储和 API 的必须有集成测试。
- 编译器相关改动必须覆盖：
    - 硬约束不被裁剪；
    - 事实覆盖后旧值进入 `invalidated`；
    - TTL 过期后不再出现在 `verifiedFacts`；
    - 预算裁剪顺序符合优先级。
- 冲突消解策略改动必须提供“旧行为 vs 新行为”的对比用例。
- 不要为了让测试通过而放宽断言；如果语义变了，先改文档和 Issue。

---

## 文档要求

- 新增 API：更新 README 的“核心 API”和 `docs/architecture.md` 的接口章节。
- 新增策略：在 `docs/architecture.md` 的“扩展点”里登记接口和默认实现。
- 新增适配器：在 README 的“接入 Agent”里加最小示例，并在 `examples/` 下提供可运行代码。
- 未完成的能力必须标注“规划中”或“开发中”，不要写成已完成。

---

## 适配器贡献指南

适配器的目标是：**让某个 Agent 框架能用最小改动接入 ASC**，而不是重写框架。

要求：

- 只做协议转换：拉状态卡、写事件、写约束、触发快照。
- 不修改框架本身的执行语义。
- 提供 `examples/<framework>/` 可运行示例，包含 README 说明启动步骤。
- Python 适配器通过 HTTP 调用 ASC，不要试图在 Python 进程内嵌 Java 服务。
- Java 适配器优先做成 Starter 或 SDK，避免让用户手写 HTTP 调用。
- 适配器里不要硬编码业务规则（比如“什么算硬约束”），规则应放在 ASC 服务端策略里。

---

## 评测贡献指南

评测是 ASC 证明价值的关键。欢迎贡献：

- **任务数据集**：长周期、多工具、包含硬约束和事实冲突的任务。
- **约束违反检测器**：判断 Agent 输出是否违反硬约束。
- **token 统计脚本**：对比 baseline 与 ASC 的输入 token。
- **重复工具调用检测**：相同工具 + 相同参数的重复调用。

提交评测相关 PR 时请附：

- 数据集格式说明；
- 运行方式；
- 基线结果（至少在 baseline 上跑通）；
- 不要编造 ASC 的评测结果，没有跑通就写“待验证”。

---

## 最后

ASC 还在早期，很多设计会变。**先对齐问题，再写代码**，比写一个大而全的 PR 更有价值。

如果你不确定从哪里开始，开一个 Discussion，标题写清楚你想解决的问题。
