# 端到端测试结果分析 · e2e-20261121-1116

生成时间：2026-11-21T03:15:27Z

## 一、结果总表

| 指标 | Baseline | ASC | 变化 |
|---|---:|---:|---:|
| 决策步数 | 3 | 3 | - |
| 注入 token（估算） | 315 | 153 | 51.4% ↓ |
| 硬约束违反 | 2 | 0 | - |
| 过期/被覆盖事实引用 | 2 | 0 | - |
| 重复编辑 | 1 | 0 | - |
| 恢复重放步数 | 3 | 1 | - |
| 最终文件正确 | False | True | - |

## 二、最终文件状态

期望：{"timeout": 50, "region": "shanghai", "retries": 2}

- baseline: `{"timeout": 45, "region": "beijing", "retries": 5}`
- asc: `{"timeout": 50, "region": "shanghai", "retries": 2}`

## 三、口径与边界（写进成果分析时必须保留）

1. `asc_card_audit.json` 是 audit 视图证据：同 key 的旧值转 SUPERSEDED、
   过期事实转 EXPIRED、软约束可见；lean 视图不注入这些格，所以没有进入决策上下文。

2. 本测试的决策器是确定性脚本，不是大模型；它隔离出的是「注入内容质量」带来的差异，
   与真实 LLM 的随机性无关。要测模型行为请按操作手册的 LLM / MCP 手工路径复跑。
3. token 为估算值（CJK 1 字符≈1 token，其余 4 字符≈1 token），用于相对比较，不作绝对成本。
4. 文件修改是真实磁盘 I/O；两组各自写 `workspace/baseline` 与 `workspace/asc`，互不覆盖。
5. 任务快照通过桥接层落成 ContextFS 风格节点，含 owner / version / visibility / agent_binding；
   若配置了 `CONTEXTFS_BASE_URL`，会同时写入真实 ContextFS。
6. 治理步数：1；快照节点：e2e_file_edit/contextfs/task/e2e-20261121-1116-asc/snapshots/v11.json
