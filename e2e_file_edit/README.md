# ASC 端到端文件修改测试 · 操作手册

**ASC 已经有 MCP Server，WorkBuddy / Trae 能直接接**，不需要再写插件。

但要分清楚两件事：

- ASC MCP 提供的是**状态治理工具**（建任务、写约束/事实/待办、取状态卡、快照、回放、交接）；
- **改文件由客户端自己的文件工具完成**（WorkBuddy 的文件读写、Trae 的编辑能力）。

所以端到端闭环是：客户端每轮先 `get_state_card`，再决定怎么改文件，每步 `e2e_record_step` 留痕，结束 `e2e_snapshot` 固化快照。本目录在原版 11 个 MCP 工具之上加了 4 个工具，专门做这件事。

---

## 1. 环境准备

| 依赖                          | 版本    | 说明                    |
| --------------------------- | ----- | --------------------- |
| JDK                         | 21+   | 已验证 21.0.8            |
| Maven                       | 3.9+  | 已验证 3.9.16            |
| Python                      | 3.10+ | 已验证 3.13              |
| PostgreSQL / Redis / Docker | 可选    | 本地 E2E 用 H2 内存库，不需要它们 |
| LLM API Key                 | 可选    | 确定性脚本测试不需要；手工用真实模型时需要 |

Python 依赖：

```bash
python -m pip install "mcp>=2" requests
```

国内网络慢的话加镜像源：

```bash
python -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple "mcp>=2" requests
```

如果 pip 装不上 `requests`，仓库内置了一个仅覆盖 SDK 所需方法的兼容层  
（`e2e_file_edit/_vendor/requests.py`），桥接层会在检测到缺失时自动启用；  
它不影响真实环境里已安装的 requests。MCP Server 本身仍需 `mcp>=2`。

---

## 2. 启动 ASC 服务

### 2.1 本地快速模式（本次实测采用）

项目已新增 `StateBoard/src/main/resources/application-local.yml` 与 `pom.xml` 中的 H2 依赖：

```bash
mvn -f pom.xml \
  -Dspring-boot.run.profiles=local spring-boot:run
```

- 服务地址：`http://localhost:8080`
- 数据库：H2 内存库（`MODE=PostgreSQL`），进程退出即清空
- Redis 不可用会自动降级（热缓存只是加速器，不是正确性依赖）
- 健康检查：`curl http://localhost:8080/actuator/health`

> 如果 health 显示 DOWN 但 REST 能返回，通常是 Redis indicator 没关掉；local profile 已配置 `management.health.redis.enabled=false`，重启后生效。

### 2.2 生产模式（有 Docker 时）

沿用仓库原有 `docker compose up -d`，用 PostgreSQL + Redis，不加 profile。

---

## 3. 接入 MCP

### 3.1 WorkBuddy

配置文件：`~/.workbuddy/mcp.json`，把下面这段合并进已有的 `mcpServers`（不要覆盖其他 server）：

```json
{
  "mcpServers": {
    "asc-e2e": {
      "command": "python",
      "args": ["<仓库绝对路径>/mcp/asc_e2e_server.py"],
      "env": {
        "ASC_BASE_URL": "http://localhost:8080",
        "ASC_TENANT": "e2e"
      }
    }
  }
}
```

写入后在 WorkBuddy 连接器管理页打开自定义连接器入口，对新 server 点「信任」才会激活。模板文件：`e2e_file_edit/mcp-workbuddy.example.json`。

### 3.2 Trae

在项目根目录 `.trae/mcp.json`（或 Trae 设置里的 MCP 配置）写同样内容，`command` 用绝对路径：

```json
{
  "mcpServers": {
    "asc-e2e": {
      "command": "python",
      "args": ["<仓库绝对路径>/mcp/asc_e2e_server.py"],
      "env": { "ASC_BASE_URL": "http://localhost:8080", "ASC_TENANT": "e2e" }
    }
  }
}
```

### 3.3 新增的 4 个工具

| 工具                                     | 作用                                                                  |
| -------------------------------------- | ------------------------------------------------------------------- |
| `e2e_start(run_id, mode, description)` | 建结果目录，写 `meta.json`                                                 |
| `e2e_record_step(...)`                 | 记一步决策；`violation` / `stale_ref` / `repeated` / `resume_steps` 用来算指标 |
| `e2e_snapshot(task_id, ...)`           | ASC 快照 → ContextFS 风格节点（owner/版本/可见性/Agent 绑定）                      |
| `e2e_report(run_id)`                   | 生成 `summary.json` 与 `analysis.md`                                   |

原版 11 个工具（`create_task` / `add_constraint` / `record_fact` / `get_state_card` / `snapshot` / `replay` / `handoff` 等）全部保留。

---

## 4. 自动跑一遍（确定性脚本，含真实文件修改）

```bash
cd StateBoard/e2e_file_edit
python run_e2e.py
```

指定结果名：

```bash
python run_e2e.py --run-id e2e-20261121-001
```

输出示例：

```json
{
  "runId": "e2e-20261121-113000",
  "resultDir": ".../results/e2e-20261121-113000",
  "finalCorrect": { "baseline": false, "asc": true },
  "byMode": { "baseline": {...}, "asc": {...} }
}
```

脚本做了什么：

1. `workspace/baseline/config.json` 与 `workspace/asc/config.json` 从同一模板复制；
2. baseline 组拿**整段历史原文**（含已作废的 region、已过期的 target、软约束 retries）；
3. asc 组建任务、写 3 条 HARD + 1 条 SOFT、写冲突事实与过期事实、写待办，每步取状态卡；
4. 两组都**真实写文件**；
5. asc 组做 snapshot → restore 跨会话恢复，baseline 组用全量历史模拟新会话；
6. 全部步骤写入 `raw.jsonl`，最后生成汇总与分析。

---

## 5. 手工用 WorkBuddy / Trae 测（真实 Agent 路径）

把下面这段当作 prompt 发给已接入 `asc-e2e` MCP 的客户端：

```text
请按以下步骤做一次端到端测试，所有工具调用都要真实执行，不要只描述：

1. 调用 e2e_start，run_id 用 hand-YYYYMMDD-001，mode=asc。
2. 调用 create_task，task_id=hand-001。
3. 调用 add_constraint 写三条 HARD：只允许改 config.json 的 timeout 字段；禁止删除任何文件；改之前先记待办、改完标记完成。再写一条 SOFT：尽量把 retries 改成 5。
4. 调用 record_fact 写 timeout_target=45，再写 timeout_target=50（同 key，验证 LATEST_WINS）；写一条 expiresAt 已过去的 region_target=beijing。
5. 调用 record_todo 写「把 timeout 改为 50」和「确认没有改动其他字段」。
6. 调用 get_state_card（view=lean, budget=small），把返回内容原文作为 context，调用 e2e_record_step 记一步 before_edit。
7. 用你自己的文件工具，只修改 `e2e_file_edit/workspace/asc/config.json` 的 timeout 为 50，其他字段不动。
8. 再调用 e2e_record_step 记一步 after_edit，files 写实际路径。
9. 调 snapshot，再调 restore_task 得到新任务，调 get_state_card，记一步 resume，resume_steps 写实际用了几步回到进度。
10. 调 e2e_snapshot 把快照写到 ContextFS 节点，最后调 e2e_report 出报告。
```

对照组的 prompt：把第 3–5、9 步的 ASC 调用去掉，第 6 步 context 直接贴完整历史（可参考 `run_e2e.py` 里的 `HISTORY`），`mode=baseline`，让它按历史改文件——预期会踩到过期/已作废内容。

> 手工路径的 `mode` 由 `e2e_record_step` 的参数决定，和 MCP 进程环境变量无关。

---

## 6. 本次实测结果（已保留）

主结果目录：`results/e2e-20261121-1120/`（索引见 `results/INDEX.md`）

| 指标           | Baseline |         ASC |
| ------------ | -------: | ----------: |
| 注入 token（估算） |      315 | 153（−51.4%） |
| 硬约束违反        |        2 |           0 |
| 过期/被覆盖事实引用   |        2 |           0 |
| 重复编辑         |        1 |           0 |
| 恢复重放步数       |        3 |           1 |
| 最终文件正确       |        否 |           是 |

原始 trace：`results/e2e-20261121-1120/raw.jsonl`  
证据卡：`asc_card_before.json`（lean，进入决策）、`asc_card_audit.json`（audit，显示 SUPERSEDED / EXPIRED / 软约束）  
快照节点：`contextfs/task/e2e-20261121-1120-asc/snapshots/v11.json`

MCP 工具注册已用 15 个工具清单验证（原版 11 + 扩展 4）。

## 7. 结果文件

```text
results/<run_id>/
├── meta.json                 # 运行元信息：mode、服务地址、ContextFS 地址
├── raw.jsonl                 # 每一步原始 trace：context / 决策 / 文件 / 标记
├── summary.json              # 汇总指标与最终文件比对
├── analysis.md               # 成果分析（含口径边界）
├── asc_card_before.json      # ASC 首次状态卡
└── asc_card_restored.json    # 跨会话恢复后的状态卡

contextfs/task/<task_id>/snapshots/v<n>.json
                              # ASC 快照落成的 ContextFS 风格节点
```

如果设置了 `CONTEXTFS_BASE_URL`，同一快照还会 POST 到真实 ContextFS 的 `/api/v1/fs/write`，URI 形如 `ctxfs://task/<task_id>/snapshots/v<n>.md`。

---

## 8. 指标口径

| 指标       | 定义                                                                  |
| -------- | ------------------------------------------------------------------- |
| 注入 token | 每步 context 的估算量求和；CJK 1 字符≈1 token，其余 4 字符≈1 token                  |
| 硬约束违反    | 该步决策是否违反 HARD 约束（改了非 timeout 字段 / 删文件 / 未记待办）                       |
| 过期事实引用   | 是否引用了被 `LATEST_WINS` 覆盖或 TTL 过期的值                                   |
| 重复编辑     | 跨会话恢复后是否重复执行了已完成编辑                                                  |
| 恢复重放步数   | 新会话回到原进度用了几步                                                        |
| 最终文件正确   | `config.json` 是否等于 `{"timeout":50,"region":"shanghai","retries":2}` |

**边界（写成果分析必须保留）**：自动脚本的决策器是确定性规则，不是大模型；它隔离的是「注入内容质量」的差异，不能替代真实 LLM 的随机性测试。要测模型行为，走第 5 节手工路径或接真实模型的 LLM benchmark。

---

## 9. 故障排查

| 现象                         | 原因                | 处理                                              |
| -------------------------- | ----------------- | ----------------------------------------------- |
| `ASC 服务不可用`                | 服务没起              | 按第 2 节启动，确认 `curl localhost:8080/v1/tasks` 有响应  |
| health DOWN 但接口可用          | local profile 未加载 | 确认启动参数 `-Dspring-boot.run.profiles=local`       |
| MCP 客户端看不到工具               | 配置未信任 / 路径错       | WorkBuddy 点「信任」；`args` 用绝对路径                    |
| `TASK_ALREADY_EXISTS`      | 同 run_id 跑过       | 换一个 `--run-id`；H2 重启也会清空                        |
| `ModuleNotFoundError: asc` | 未安装依赖或未从仓库根运行     | `pip install requests`；脚本会自动把 `sdk/python` 加进路径 |
