# ASC × 内容生成：WorkBuddy 接入对照实验

## 0. 这个实验回答什么

同一个写作任务，WorkBuddy **不接 ASC** 和 **接 ASC**，产物差在哪。

不是脚本决策器的仿真，是三个独立 WorkBuddy 会话真写出来的三篇文章。
ASC 在这套实验里的角色和 `e2e_file_edit` 一致——提供状态治理工具；
写作由 WorkBuddy 自己的工具完成。

---

## 1. 环境

| 项 | 值 |
|---|---|
| ASC 服务 | `http://localhost:8080`，health `UP` |
| PostgreSQL | 由 `.env` 的 `ASC_DB_URL` 指定（不入库） |
| Redis | 由 `.env` 的 `ASC_REDIS_HOST` / `ASC_REDIS_PORT` 指定 |
| Python | 3.10+（`setup_state.py` / `score_multi.py`） |

启动（**不加** local profile，否则读不到 `.env` 的真实库）：

```bash
cd StateBoard
mvn spring-boot:run
curl http://localhost:8080/actuator/health
```

> 之前若用 `-Dspring-boot.run.profiles=local` 起过 H2 模式，先停掉旧进程再切，
> 否则 health 会一直是旧配置。

Python 依赖：`requests`（或用 `e2e_file_edit/_vendor/requests.py` 兼容层，
`setup_state.py` 已自动把该目录加进 `sys.path`）。

---

## 2. 任务与分组

任务：根据本地相关文件，写一篇 Tool Use 主题的公众号推文《Agent Day2：Tool Use 学习（下）——Tool Runtime、MCP、安全与评估》。
要求包括：首行是「今日学习计划」正文、四个主题作一级标题、术语反引号、重点加粗、
2500–3500 字、衔接上一篇、不出现编程语言名和旧值。

三组**任务描述、素材路径、输出要求完全相同**，唯一差异是注入的上下文包：

| 组 | 上下文包 | 模拟场景 |
|---|---|---|
| A | 只有任务书和 5 个素材路径 | WorkBuddy 未接入 ASC |
| B | 任务书 + ASC `lean` 状态卡（362 token） | 接入 ASC，每轮决策前编译注入 |
| C | 与 B 等量事实，但硬约束降软、旧值并列注入 | 接入了但治理关掉的消融对照 |

素材里的冲突真实存在，不是编造的噪音：某索引文件引用了旧版材料、某工具笔记写的是旧的工具数量口径、某份经历材料里有含编程语言名的旧账号名。

---

## 3. 执行步骤

```bash
cd StateBoard

# 1. 写入 ASC 任务，导出三组冻结上下文包
python e2e_article/setup_state.py

# 2. 开三个独立 WorkBuddy 会话，分别读 contexts/{A,B,C}.md 写作
#    每组重复 3 次，输出到 articles/{A,B,C}-r{1,2,3}.md

# 3. C 组重建为等量事实的降级包（只在需要重做消融时跑）
python e2e_article/make_c.py

# 4. 判分
python e2e_article/score_multi.py
```

手工复跑单个会话时，把对应 `contexts/*.md` 全文贴给一个新 WorkBuddy 会话即可；
包里已写清素材路径和输出路径。每个会话只读自己的包和包里列出的素材，
不读 `results/` 下其他文件，不联网。

---

## 4. 判分口径

`score.py` / `score_multi.py` 共 11 项，全部来自任务书与 ASC 硬约束，不做主观质量打分：

1. 首行是「今日学习计划」
2. 首行无标题标记
3. 四个一级标题齐全
4. 无多余一级标题
5. 衔接上一篇
6. 含边界段
7. 固定句式收尾
8. 反引号术语数 ≥ 10
9. 加粗数 ≥ 5
10. 字数 2500–3500
11. 无禁用内容（编程语言名 / 后端数字 / 过期旧值 / 自我矮化 / 空泛副词）

---

## 5. 结果

结果目录：`results/e2e-article-20261121-1200/`

| 指标 | A 未接入 | B 接入 ASC | C 等量无治理 |
|---|---:|---:|---:|
| 通过项 / 11 | 8.3 ± 0.5 | **10.7 ± 0.5** | 9.3 ± 0.5 |
| 四主题一级标题数 | 0.0 | **4.0** | 0.0 |
| 禁用内容类别数 | 1.7 | **0.0** | 0.3 |
| 字数达标 | 1/3 | **3/3** | 2/3 |
| 反引号术语数 | 86.3 ± 11.1 | 86.7 ± **3.4** | 60.0 ± 10.7 |

逐次明细、关键对照组、完整口径与对外表述见 [analysis.md](results/e2e-article-20261121-1200/analysis.md)，
N=3 汇总见 [summary.md](results/e2e-article-20261121-1200/summary.md)。

选用稿：[articles/B-r3.md](results/e2e-article-20261121-1200/articles/B-r3.md)，即结果目录中的最终成品（标题即成品文件名）。

---

## 6. 结果文件

```text
results/<run_id>/
├── manifest.json              运行元信息、数据源、状态卡 token 数
├── asc_card_lean.json         进入决策的状态卡（过期项已移出）
├── asc_card_audit.json        审计视图（可见旧值被标 SUPERSEDED / EXPIRED）
├── contexts/{A,B,C}.md        三组冻结上下文包
├── articles/{A,B,C}-r{1,2,3}.md   九篇原始产物
├── scores/*.json              逐篇机器判分明细
├── objective.md               单次判分说明
├── summary.md                 N=3 汇总表与口径
└── analysis.md                成果分析、对外表述
```

---

## 7. 边界

1. N=3 样本量小，只作方向性判断，不报显著性。
2. 独立 WorkBuddy 会话无法固定 temperature，同一包三次产物不同是观测到的方差来源。
3. 机器判分只判可判定项；可读性、钩子质量、说服力不打分，用稿是人工从 B 组三篇里挑的。
4. 只测了中文技术推文写作，不能外推到代码修改、多轮问答或长任务。
5. 收益是「约束遵循 + 过期值拦截」，不证明文章本身比人写得好。
