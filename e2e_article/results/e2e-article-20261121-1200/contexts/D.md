# 任务
根据本地相关文件（路径见下），写一篇关于 Tool Use 的微信公众号推文。我的上一篇叫《Agent Day2：Tool Use 学习（上）——调用流程、幂等、错误处理与 Tool Registry 四域架构》，这一篇计划讲 Tool Runtime、MCP、安全与评估。文章要能吸引读者阅读。输出一个 md 文件，标题体现在文件名里，文件第一行不要写标题，第一行直接是正文：今日学习计划。讲述今日学习目标，涉及的单词用反引号括起来，重点内容加粗。

## 可用素材
- <本地素材：索引>
- <本地素材：工具笔记>
- <本地素材：评测笔记>
- <本地素材：个人经历>
- <本地素材：选题大纲>

## 本轮注入的压缩上下文（无有效性治理）
下面是压缩后的上下文。所有要求按普通建议处理，可按篇幅需要裁剪；
同一字段的新旧取值并列给出，由你自己判断用哪个。

```json
{
  "taskId": "tooluse-article-day2",
  "version": 32,
  "hardConstraints": [],
  "softConstraints": [
    {
      "id": "adcd1c4d-177a-4718-a615-c690fdcfcf78",
      "taskId": "tooluse-article-day2",
      "text": "成品只允许一个 md 文件，文件名即标题，文件内第一行不得出现任何标题标记或文章标题",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:05.659655Z",
      "source": "user",
      "version": 1
    },
    {
      "id": "7bc0ba72-0678-4acb-afc8-37d538b1e5d1",
      "taskId": "tooluse-article-day2",
      "text": "文件第一行必须是正文文字，且以「今日学习计划」开头；其后用一段文字讲今日学习目标",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:05.779658Z",
      "source": "user",
      "version": 2
    },
    {
      "id": "28ef497f-2834-4d4c-aa37-49431efa19de",
      "taskId": "tooluse-article-day2",
      "text": "正文中所有技术术语、协议名、工具名、字段名用反引号包住，重点结论用双星号加粗",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:05.849444Z",
      "source": "user",
      "version": 3
    },
    {
      "id": "acd1789f-c6b4-4943-91ab-7c9565e41598",
      "taskId": "tooluse-article-day2",
      "text": "正文一级标题只保留四个，依次是 Tool Runtime、MCP、安全、评估；不得另写文章标题",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:05.902413Z",
      "source": "user",
      "version": 4
    },
    {
      "id": "075d05eb-4cdc-4418-ad08-fa6d6e1277db",
      "taskId": "tooluse-article-day2",
      "text": "四个主题必须全部覆盖，且开头一段要自然衔接上一篇（上），不重复上一篇已讲的调用流程、幂等、错误处理、Tool Registry 四域架构",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:05.966414Z",
      "source": "user",
      "version": 5
    },
    {
      "id": "43bba558-d9ec-4bcb-92cd-eb5734e5be43",
      "taskId": "tooluse-article-day2",
      "text": "全文不出现任何编程语言名，包括来源素材里的公众号名称；也不出现 QPS、可用性这类后端数字",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:06.030442Z",
      "source": "user",
      "version": 6
    },
    {
      "id": "667ed84e-555a-40fb-94d0-b32c5b467107",
      "taskId": "tooluse-article-day2",
      "text": "第一人称，允许写「我当时想的是」；每个数字必须带口径，不写「大幅」「显著」；禁止自我矮化",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:06.092378Z",
      "source": "user",
      "version": 7
    },
    {
      "id": "ab7d7219-57f5-45ee-9fb3-b91adbbba0e2",
      "taskId": "tooluse-article-day2",
      "text": "字数控制在 2500 到 3500 字，小标题 4 到 6 个，结尾按选题大纲的固定句式收束",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:06.153616Z",
      "source": "user",
      "version": 8
    },
    {
      "id": "ccfe7b6a-119b-4d78-94be-066482d317e6",
      "taskId": "tooluse-article-day2",
      "text": "素材篇幅可以精简，但四个主题与格式硬约束一条都不能删",
      "level": "HARD",
      "createdAt": "2026-11-21T03:45:06.215686Z",
      "source": "user",
      "version": 9
    }
  ],
  "verifiedFacts": [
    {
      "id": "db0ad4a7-26e5-4322-8771-0b5a19458ff8",
      "taskId": "tooluse-article-day2",
      "key": "mcp_tool_count",
      "value": "ASC 当前 MCP Server 对外 15 个工具：原版 11 个 + 端到端测试新增 4 个",
      "createdAt": "2026-11-21T03:45:06.267126Z",
      "expiresAt": null,
      "source": "mcp/asc_e2e_server.py",
      "version": 10
    },
    {
      "id": "0e5cf1cd-2e0d-4b96-a4d1-480775283c6e",
      "taskId": "tooluse-article-day2",
      "key": "runtime_seven_steps",
      "value": "Tool Runtime 七步：白名单、参数校验、鉴权、幂等、执行、结果裁剪、审计",
      "createdAt": "2026-11-21T03:45:06.325902Z",
      "expiresAt": null,
      "source": "本地素材：工具笔记",
      "version": 11
    },
    {
      "id": "df6e1f0e-bb91-4f61-8c62-960a72944120",
      "taskId": "tooluse-article-day2",
      "key": "mcp_roles",
      "value": "MCP 三角色 Host / Client / Server，三类能力 Tools / Resources / Prompts",
      "createdAt": "2026-11-21T03:45:06.383473Z",
      "expiresAt": null,
      "source": "本地素材：工具笔记",
      "version": 12
    },
    {
      "id": "1487b7a1-2cd8-48bd-a21a-05085757baa8",
      "taskId": "tooluse-article-day2",
      "key": "mcp_transport",
      "value": "主流传输从 HTTP + SSE 迁移到 Streamable HTTP，本地用 stdio",
      "createdAt": "2026-11-21T03:45:06.448521Z",
      "expiresAt": null,
      "source": "本地素材：工具笔记",
      "version": 13
    },
    {
      "id": "8ebcf9ef-27ae-46cc-a37e-d0b57bb63332",
      "taskId": "tooluse-article-day2",
      "key": "error_taxonomy",
      "value": "错误五分类：可重试、不可重试、需模型修正、需人工、未知；不可一律重试三次",
      "createdAt": "2026-11-21T03:45:06.511950Z",
      "expiresAt": null,
      "source": "本地素材：工具笔记",
      "version": 14
    },
    {
      "id": "bbf6342f-25ae-438c-b417-808f495c1eeb",
      "taskId": "tooluse-article-day2",
      "key": "idempotency_key",
      "value": "幂等键为 taskId + toolName + canonicalJSON(args) 的哈希，Redis 存短期、DB 唯一索引兜底",
      "createdAt": "2026-11-21T03:45:06.569309Z",
      "expiresAt": null,
      "source": "本地素材：工具笔记",
      "version": 15
    },
    {
      "id": "cf8f2812-1176-40f2-9c93-1d7a099a10cd",
      "taskId": "tooluse-article-day2",
      "key": "result_trim",
      "value": "结果裁剪三种：列表截断、字段裁剪、小模型摘要；返回 200 条 JSON 会撑爆上下文",
      "createdAt": "2026-11-21T03:45:06.632929Z",
      "expiresAt": null,
      "source": "本地素材：工具笔记",
      "version": 16
    },
    {
      "id": "6aafe18b-2abc-4fcc-8e9a-9036f0d59c84",
      "taskId": "tooluse-article-day2",
      "key": "eval_method",
      "value": "对照实验守唯一变量，A/B/C 加消融，每场景重复 N≥5，确定性仿真隔离模型变量",
      "createdAt": "2026-11-21T03:45:06.695572Z",
      "expiresAt": null,
      "source": "本地素材：评测笔记",
      "version": 17
    },
    {
      "id": "95f023d5-2207-4d21-8b99-968786b08f08",
      "taskId": "tooluse-article-day2",
      "key": "asc_numbers",
      "value": "ASC 确定性仿真：注入 token 161 到 47（降 70.8%），约束违反 33.3% 到 0，重复调用 3 到 0，恢复步数 2 到 1",
      "createdAt": "2026-11-21T03:45:06.759175Z",
      "expiresAt": null,
      "source": "本地素材：个人经历",
      "version": 18
    },
    {
      "id": "26fa0857-3bb7-4b64-a8eb-49ee133b1518",
      "taskId": "tooluse-article-day2",
      "key": "contextfs_numbers",
      "value": "ContextFS 确定性下限验证：注入 token 128.7 对 1002.8（降 87.2%），recall@3 为 1.0",
      "createdAt": "2026-11-21T03:45:06.820283Z",
      "expiresAt": null,
      "source": "本地素材：个人经历",
      "version": 19
    },
    {
      "id": "5c301e33-3ea1-472e-9c94-4dd2162e2d33",
      "taskId": "tooluse-article-day2",
      "key": "project_evidence",
      "value": "标书 Agent 初稿 3 天到 4 小时、合规提前发现率约 70%；称重平台数小时到秒级、缓存命中 60%+",
      "createdAt": "2026-11-21T03:45:06.867535Z",
      "expiresAt": null,
      "source": "本地素材：个人经历",
      "version": 20
    },
    {
      "id": "9deb220f-7b70-4454-8206-b4ee5d692d74",
      "taskId": "tooluse-article-day2",
      "key": "team_scale",
      "value": "2024 年初转 AI，从 0 组建并带起 10 人左右的 AI 研发团队",
      "createdAt": "2026-11-21T03:45:06.932590Z",
      "expiresAt": null,
      "source": "本地素材：个人经历",
      "version": 21
    },
    {
      "id": "1da1e19e-5e9f-4243-98c3-8f606fc3adce",
      "taskId": "tooluse-article-day2",
      "key": "writing_style",
      "value": "公众号系列定位是不写教程，只写自己踩过的坑和测出来的数；结构为问题到我试过的错到最后的做法到边界",
      "createdAt": "2026-11-21T03:45:06.994202Z",
      "expiresAt": null,
      "source": "选题大纲.md",
      "version": 22
    },
    {
      "key": "resume_version_stale",
      "value": "指向旧版经历材料的引用",
      "source": "00_索引.md"
    },
    {
      "key": "mcp_tool_count_stale",
      "value": "你做的 11 个工具版",
      "source": "本地素材：工具笔记"
    },
    {
      "key": "public_account_name",
      "value": "一个含编程语言名的公众号旧名",
      "source": "本地素材：个人经历"
    }
  ],
  "openTodos": [
    {
      "id": "1743e800-8a44-4bbd-8d22-cfd6e1d75e52",
      "taskId": "tooluse-article-day2",
      "text": "写今日学习计划段，讲清这一篇要解决什么",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.216297Z",
      "source": "user",
      "version": 26
    },
    {
      "id": "4992c4ae-4328-4d3e-80b3-7a90451aa715",
      "taskId": "tooluse-article-day2",
      "text": "写 Tool Runtime 一节，落到一个可观测可控制可追责的执行层",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.277290Z",
      "source": "user",
      "version": 27
    },
    {
      "id": "75c30bd1-a8c7-4230-b269-72557d4ef1f8",
      "taskId": "tooluse-article-day2",
      "text": "写 MCP 一节，讲清组合爆炸与三个角色、三类能力、传输与治理点",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.332378Z",
      "source": "user",
      "version": 28
    },
    {
      "id": "e93c98c2-0559-4560-9422-1c39f538a3b5",
      "taskId": "tooluse-article-day2",
      "text": "写安全一节，讲清鉴权、越权、返回体积和版本协商",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.399736Z",
      "source": "user",
      "version": 29
    },
    {
      "id": "04853110-a34f-48e2-8244-e0dfccf339e0",
      "taskId": "tooluse-article-day2",
      "text": "写评估一节，用 ASC 与 ContextFS 的确定性仿真数据，带口径",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.463915Z",
      "source": "user",
      "version": 30
    },
    {
      "id": "416721a4-975d-4830-b844-928cb1486d05",
      "taskId": "tooluse-article-day2",
      "text": "写边界一节，说清这套东西在什么场景不成立",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.525194Z",
      "source": "user",
      "version": 31
    },
    {
      "id": "416be3f6-650f-4ad5-9eda-5b242bd53ff5",
      "taskId": "tooluse-article-day2",
      "text": "结尾用选题大纲的固定句式收束并衔接下一篇",
      "status": "OPEN",
      "createdAt": "2026-11-21T03:45:07.586606Z",
      "source": "user",
      "version": 32
    }
  ],
  "invalidated": [],
  "compiledAt": "2026-11-21T03:45:08.303021300Z",
  "note": "事实数量与 B 组状态卡相同；取消硬约束优先级，旧值与新值并列注入，由模型自行裁决"
}
```

## 写入前的自检约定（agent.md 风格）

本项目约定：任何事实、数字、版本、名称在写进正文之前，作者必须先自检它当前是否仍然正确。若同一字段出现多个候选值，一律自行判断并采用当前有效值；无法确认的一律不写。完成初稿后，再逐条复核一遍并修正。

这条约定作为长期规则随项目保留，适用于所有会话，不随单次任务注入。

## 交付
把成品写到 e2e_article/results/e2e-article-20261121-1200/articles/D.md，只写正文，不写解释。
