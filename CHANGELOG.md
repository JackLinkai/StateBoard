# Changelog

ASC 的版本迭代记录。格式参考 [Keep a Changelog](https://keepachangelog.com/)，按版本倒序排列。
每个版本的关键实现与实测数据会同步到 [README.md](README.md) 的「项目状态 / Roadmap / 端到端实测结果」。

---

## [v0.8] — 2026-11-21 — 真实工作负载端到端验证

把 ASC 从「确定性决策器 + 玩具场景」推进到真实工作负载：MCP 直连 WorkBuddy / Trae、真实文件 I/O、真实会话内容生成。

### 新增

- **本地 H2 模式**：`src/main/resources/application-local.yml` + `pom.xml` 的 H2 依赖。
  无 Docker / PostgreSQL / Redis 也能启动（`mvn spring-boot:run -Dspring-boot.run.profiles=local`），
  H2 以 `MODE=PostgreSQL` 运行、Flyway 关闭、JPA `create-drop`；Redis health indicator 关闭，
  不可达时由 `HotStateCache` 本地降级。
- **MCP 端到端扩展**：`mcp/asc_e2e_server.py` 在原版 11 工具上新增 4 个，共 15 个：
  `e2e_start` / `e2e_record_step` / `e2e_snapshot` / `e2e_report`。
  支持「每轮取状态卡 → 客户端改文件 → 逐步留痕 → 快照固化」完整闭环。
- **ASC → ContextFS 桥接**：ASC 快照写成 `ctxfs://task/{id}/snapshots/v{n}` 风格节点，
  带 owner / version / visibility / agent_binding；配置 `CONTEXTFS_BASE_URL` 后同步写真实 ContextFS。
- **端到端测试套件**：`e2e_file_edit/`（文件修改，确定性决策器）与 `e2e_article/`
  （WorkBuddy 内容生成，真实会话 N=3），各含操作手册、上下文包、原始数据与判分脚本。

### 实测结果

**确定性仿真 · 文件修改**（规则决策器，详见 README「评测指标」）（`e2e_file_edit/results/e2e-20261121-1120/`）

| 指标 | Baseline | ASC |
|---|---:|---:|
| 注入 token（估算） | 315 | 153（−51.4%） |
| 硬约束违反 | 2 | 0 |
| 过期/被覆盖事实引用 | 2 | 0 |
| 重复编辑 | 1 | 0 |
| 恢复重放步数 | 3 | 1 |
| 最终文件正确 | 否 | 是 |

**端到端实测 · 内容生成**（`e2e_article/results/e2e-article-20261121-1200/`，真实 WorkBuddy 会话 N=3）

| 指标 | A 未接入 | B 接入 ASC | C 等量无治理 | D prompt 自检 |
|---|---:|---:|---:|---:|
| 通过项 / 11 | 8.3 ± 0.5 | 10.7 ± 0.5 | 9.3 ± 0.5 | 10.0 ± 0.8 |
| 四主题一级标题数 | 0.0 | 4.0 | 0.0 | 2.7 |
| 禁用内容类别数 | 1.7 | 0.0 | 0.3 | 0.3 |
| 字数达标 | 1/3 | 3/3 | 2/3 | 2/3 |
| 上下文包 token（估算） | 243 | 3188 | 3326 | 3462 |
| 成品输出 token（估算） | 2825 ± 712 | 2780 ± 300 | 2869 ± 158 | 2899 ± 223 |

D 组（后补）在 C 的等量信息上只加一条 agent.md 风格自检约定，验证「给 agent 自检规则能否替代 ASC」：通过项升到 10.0，但四主题一级标题三次有一次写成二级标题、禁用内容仍 1/3 命中，方差全表最大。D 组由隔离子代理会话生成，与前三组的 WorkBuddy 会话环境不同，只作方向性参考。

环境：PostgreSQL + Redis 由 `.env` 注入（地址不入库）。
完整口径与边界见 `e2e_article/results/e2e-article-20261121-1200/analysis.md`。

### 边界

- 文件修改那组决策器是确定性脚本，隔离注入内容质量差异，不替代真实 LLM 随机性测试。
- 端到端实测用独立会话、无法固定 temperature，N=3 只作方向性判断。
- token 为规则估算值，真实 API usage 未记录；内容生成这组的 B 包体大于 A 包体，证明的是约束遵循而非省 token，注入侧节省见文件修改那组（315→153，−51.4%）。两组口径不可混报。

---

## [v0.7] — 真实 LLM 端到端评测

- `benchmarks/runner.py --agent llm`：任意 OpenAI Chat Completions 兼容端点，服务商可插拔
  （`providers.json`：DeepSeek / Ollama / OpenAI / Qwen / custom）。
- 固定 JSON 决策协议（`EXECUTE` / `REJECT` / `SKIP`）；规则判分覆盖约束越界、过期事实引用、
  参数漂移、误拒绝、重复调用、恢复重放步数。
- 真实 usage 计费、完成率均值 ± 方差、逐轮 trace（`--trace-dir/<run_id>/raw.jsonl`）。
- `--view full|lean|audit` 权限视图对照；离线单测覆盖 JSON 解析与成本计算。

## [v0.6] — 多租户与权限视图

- 任务归属租户（Flyway V4），请求头 `X-ASC-Tenant`，跨租户读写 404 防探测。
- 状态卡 `view=full|lean|audit`：view 裁格、budget 裁条，先裁格再裁条。

## [v0.5] — 编译策略演进

- 冲突消解策略插件化：`LATEST_WINS` / `MANUAL` / `REJECT`，
  实现 `FactConflictResolver` SPI + `@Component` 即注册新策略。

## [v0.4] — 可观测与评测

- React 可视化面板（任务列表 / 状态卡四格 / 版本回放 / 待办流转 / 交接）。
- 评测数据集与脚本（Baseline vs ASC 五指标对比），任务列表 / 待办流转 / 版本列表 API。
- Dockerfile + Docker Compose 一键启动。

## [v0.3] — 生态接入

- LangGraph / CrewAI 示例与 Python SDK、Spring AI / LangChain4j 适配（Java SDK `io.asc.client`）。
- MCP Server（`mcp/asc_mcp_server.py`，11 工具）。

## [v0.2] — 治理能力

- 任务快照、跨会话恢复、多 Agent 状态交接、版本回放与审计 API、Redis 热状态与 TTL。

## [v0.1] — 核心服务（MVP）

- REST API、最简编译器、PostgreSQL 持久化 + 版本号、Docker Compose 一键启动。
