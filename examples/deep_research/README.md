# ASC × open_deep_research

把 ASC 接到 LangChain 官方的 [open_deep_research](https://github.com/langchain-ai/open_deep_research) 上，
做一组「原生 vs 注入状态卡」的长任务对照。

上游代码不随本仓库分发，需自行 clone 到 `vendor/open_deep_research/`，**一行不改**；
接入逻辑全部在本目录。脚本按这个固定路径找上游源码，别换成别的位置。

---

## 一、接入设计

上游的图：

```
clarify_with_user → write_research_brief → research_supervisor → final_report_generation
                                              ├─ supervisor ⇄ supervisor_tools
                                              └─ researcher 子图（搜索 → 反思 → 压缩）
```

`supervisor_messages` 里会累积每一轮 `ConductResearch` 返回的完整研究结论，
supervisor 每轮决策都要把这些历史全读一遍——这就是长任务里的上下文膨胀源。

本目录只覆盖两个节点，其余全部复用：

| 节点 | 原生 | 接入后 |
|---|---|---|
| `supervisor` | 直接把 supervisor_messages 丢给模型 | 先把 ASC 编译的状态卡拼进 system 消息，再丢给模型 |
| `supervisor_tools` | 执行子研究，返回 ToolMessage | 同上，额外把每份结论按 `studied/<topic>` 写回 ASC（同 topic 自动 LATEST_WINS） |

### 一个刻意不做的接入点

`final_report_generation` 上游是 `findings = "\n".join(notes)`，把全部研究结论拼进 prompt。
看起来是第二个明显的注入点，但**这里不该用状态卡替代**：

- 研究报告是**累积关系**：新结论补充旧结论，不是取代
- ASC 的事实语义是**覆盖关系**：同 key 新值取代旧值

用状态卡替 findings 会丢掉被覆盖但依然有效的研究素材，那是滥用语义，不是优化。
ASC 治的是「决策状态」（查到哪了、还差什么、哪条约束不能破），不是「素材全集」。

这条边界如果反过来做，实验数字会好看，但工程上是错的。

---

## 二、目录

```
examples/deep_research/
├── asc_nodes.py      # asc_supervisor / asc_supervisor_tools + 图重建
├── search_patch.py   # 把 Tavily 换成本地固定语料检索（patch get_all_tools）
├── compat.py         # 模型与搜索配置归一化（DeepSeek / Qwen / OpenAI 兼容）
├── run_compare.py    # A/B 对照实验
├── corpus/           # 固定语料，逐条标注 kind = fact / stale / forecast / scope / conflict
├── tasks/tasks.jsonl # 18 个任务：事实型 6 / 对比型 6 / 多约束型 6
└── results/          # 实验输出
```

---

## 三、跑之前要准备四样

### 0. 上游源码（必需）

脚本从 `vendor/open_deep_research/src` 导入上游节点，先把它拉到这个固定位置：

```bash
cd StateBoard
git clone --depth 1 https://github.com/langchain-ai/open_deep_research.git \
    vendor/open_deep_research
```

`vendor/` 已加入 `.gitignore`，不会随本仓库提交；上游版本由你自己固定。

### 1. ASC 服务（必需）

```bash
cd StateBoard
docker compose up -d postgres redis
./mvnw spring-boot:run      # 或 java -jar target/*.jar
curl http://localhost:8080/v1/tasks   # 返回 [] 即就绪
```

Redis 挂了能降级到 DB，PostgreSQL 是硬依赖。

### 2. 模型密钥（必需）

```bash
export DEEPSEEK_API_KEY=sk-xxx        # Windows: $env:DEEPSEEK_API_KEY="sk-xxx"
```

四个模型角色（research / summarization / compression / final_report）会统一指向同一个模型，
保证 A/B 两组只有「注入内容」这一个变量。

### 3. 检索（默认不花钱，也不需要 key）

默认 `--search local`：检索走 `corpus/` 下的固定语料，本地打分返回，不联网、不要 key。

为什么不直接用 Tavily / ollama：

| 方案 | 成本 | 问题 |
|---|---|---|
| Tavily | 免费 1000 credits/月，不要信用卡 | 本实验 18 任务 × 2 组 × 5 次 ≈ 180 次研究，每次 20+ 次搜索，全量 4000–5000 credits，免费额度撑不住 |
| ollama | 免费 | 它是模型不是检索，换本地模型照样搜不到网页；本机 `~/.ollama/models` 是空的，7B 级模型也跑不动这种多轮 function calling |
| 本地固定语料 | 0 | 只能声称"固定语料下的对照实验"，不能声称真实联网 deep research |

关键理由不是省钱，是**方差**。A/B 对照最怕混杂因素：真实搜索每次返回的网页、排序、
摘要长度都在变，两组跑出来的差异分不清是 ASC 的功劳还是搜索抖动的结果。语料钉死之后，
唯一变量只剩「supervisor 每轮能看到什么」。

语料不是编的：正文来自真实联网搜索结果的摘录，落盘成 jsonl。每条带 `kind` 字段，
刻意保留真实搜索里那些害人的东西：

- `stale` —— 旧年份 / 旧统计期的数，看着对但已过期（如"上海 128.8 万辆"其实是 2023 年底）
- `forecast` —— 预测值被当成实际值
- `scope` —— 局部数据当全局（国内装车量当全球装机量、汽车业务毛利率当整体毛利率）
- `conflict` —— 与其他来源直接矛盾（如阿里云营收 1135 亿 vs 1200 亿）

ASC 有没有用，就看这些条目会不会污染最终结论。

真想跑真实联网检索时：

```bash
export TAVILY_API_KEY=tvly-xxx    # app.tavily.com，1000 credits/月免费，不要信用卡
./.venv/Scripts/python.exe examples/deep_research/run_compare.py --search tavily
```

---

## 四、怎么跑

先小样本验证链路（2 个任务、各跑 1 次）：

```bash
cd StateBoard
./.venv/Scripts/python.exe examples/deep_research/run_compare.py \
    --limit 2 --repeats 1
```

正式跑（18 个任务 × 2 组 × 3 次，约 1.5–3 小时，取决于搜索与模型速度）：

```bash
./.venv/Scripts/python.exe examples/deep_research/run_compare.py \
    --repeats 3 --out examples/deep_research/results/compare-18x3.json
```

只跑 ASC 组（调试注入是否生效）：

```bash
./.venv/Scripts/python.exe examples/deep_research/run_compare.py \
    --groups asc --limit 1 --repeats 1
```

跑完检查 ASC 侧确实写进去了：

```bash
curl "http://localhost:8080/v1/tasks" | head -50
curl "http://localhost:8080/v1/tasks/dr-<run_id>-dr-m1-r1/state-card?budget=medium"
```

状态卡里应该能看到 `studied/...` 的若干条事实。

---

## 五、指标

| 指标 | 说明 |
|---|---|
| 约束通过率 | 逐条判，总数作分母 |
| 任务全约束通过率 | 一条不落的任务占比 |
| 平均输入 / 输出 token | 通过 callback 统计，不改节点 |
| 平均研究单元数 | `ConductResearch` 调用次数，反映有没有重复研究同一主题 |
| 平均耗时 | 端到端 |

判分是规则式的（包含 / 排除 / 正则），确定性、可复现，不引入 LLM 判分变量。

任务集分三类：
- **事实型 6 个**：单点查询，用来确认 ASC 不会在简单任务上拖后腿
- **对比型 6 个**：多对象横向比较，检验覆盖是否完整
- **多约束型 6 个**：带 2–3 条硬约束（指定年份区间、指定来源机构、禁止给投资建议），
  这类是主战场——约束在长链路里最容易掉

---

## 六、已知限制（写结论时要带上）

1. 18 个任务是小样本，不是 benchmark；只说明方向，不构成统计显著。
2. 搜索质量受 Tavily 返回影响，同一天内不同时刻结果会变；对照要求两组尽量同批次跑。
3. ASC 组多了一次 HTTP 往返（拉状态卡），耗时会略高——这是成本的一部分，不要藏。
4. 状态卡里的事实做了 800 字截断，超长研究结论会有信息损失，这是有意的预算控制。
