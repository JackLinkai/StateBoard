今日学习计划

今天是 `Agent Day2` 的下半场。

上一篇《Agent Day2：Tool Use 学习（上）》我把 `Function Calling` 的调用流程、幂等、错误处理，还有 `Tool Registry` 那套四域架构过了一遍。后台有读者问我一句："模型输出一个 `tool_calls` 不就完了吗，为什么还要搞这么多层？"

这问题问得特别好，恰好是今天要回答的核心——**模型给出的只是"意图"，从意图到真正可执行、可追责、可复用，中间还隔着一层运行时。** 今天四个目标，就围绕这层展开。

- **目标一：吃透 `Tool Runtime`**——把一次调用变成可观测、可控制、可追责的操作。
- **目标二：搞懂 `MCP`**——它解决的是能力描述的组合爆炸，不是又一个传输协议。
- **目标三：把安全当一层来设计**——`Server` 侧鉴权、体积控制、多租户隔离，一个都不能少。
- **目标四：给工具调用建评估闭环**——不能评测的优化都是猜测。

**目标一：`Tool Runtime`——从"意图"到"可追责"**

先说个真事。我早先写工具调用，就是一个 `switch`：模型返回 `name` 和 `arguments`，我 `JSON.parse` 一下，调对应函数，把结果塞回 `messages`。跑通了，挺高兴。

直到工具涨到二十几个，问题一起冒出来：普通租户能调到管理员工具；一个慢接口把整个会话拖死；重试一次创建了两笔订单；出了事我答不上来"当时是谁调的、传了什么"。

**根因是：我把执行散落在业务代码里，没有一层统一拦着。** `Tool Runtime` 就是这层。它的职责有七个，顺序不能乱：

1. **白名单校验**：这个 `Agent` 能调这个工具吗？不在 `allowedTools` 里直接拒绝。
2. **参数校验**：`JSON Schema` 管类型，业务规则管语义，必须双层。
3. **鉴权**：租户 / 角色 / 数据范围，`tenantId`、`userId`、`requiredScopes` 一个不能少。
4. **幂等检查**：有副作用的工具，先看幂等键在不在。
5. **执行**：超时 / 重试 / 限流 / 熔断，全在这里兜。
6. **结果裁剪**：截断 / 字段裁剪 / 摘要，把回填给模型的数据压到最小。
7. **审计**：输入输出、耗时、`token`，一条不漏记下来。

**顺序有讲究：校验在鉴权前，幂等在执行前，裁剪在回填前，审计贯穿全程。** 换序就出事——比如先执行再鉴权，权限形同虚设。

代码骨架（关键十几行）：

```java
public ToolResult invoke(ToolCall call, AgentContext ctx) {
    ToolSpec spec = registry.get(call.name())
            .orElseThrow(() -> new UnknownToolException(call.name()));
    if (!ctx.allowedTools().contains(spec.name())) return ToolResult.denied("工具未授权");
    Args args = validator.validate(call.arguments(), spec.inputSchema());
    authz.check(ctx.tenantId(), ctx.userId(), spec.requiredScopes());
    String key = spec.idem() == KEY_REQUIRED ? idemKey(ctx.taskId(), spec.name(), args) : null;
    if (key != null && idemRepo.exists(key)) return idemRepo.get(key);
    ToolResult result = limiter.execute(spec.name(),
            () -> retryTemplate.execute(c -> executor.call(spec, args)));
    result = trimmer.trim(result, spec.outputSchema(), MAX_RESULT_TOKENS);
    audit.log(ctx.traceId(), spec.name(), args, result, elapsed(t0));
    if (key != null) idemRepo.save(key, result);
    return result;
}
```

**最容易被忽略的是第六步结果裁剪。** 我踩过一个坑：一个订单查询返回 200 条 `JSON`，直接回填，上下文瞬间撑爆，后面几轮推理全乱。后来三种裁法都用了：列表类**截断**（保留前 N 条加"还有 X 条"），结构化数据**只留模型需要的字段**，长文本**先用小模型摘要再回填**。原则是——**回填给模型的是它做判断需要的最小集，不是原始数据。**

幂等这块，上一篇讲过 `hash(taskId + toolName + canonicalJson(arguments))`，今天补个致命细节：key 一定要用 `**canonical JSON**`（key 排序、去空格），并且**排除时间戳、随机数这类每次都变的字段**，否则算出来的 key 每次都不同，幂等形同虚设。存储上 `Redis` 存短期、`DB` 唯一索引长期兜底。

错误分类也别偷懒。我分五类：**可重试**（超时、429、502）指数退避最多三次；**不可重试**（401、403、404、参数非法）立即失败；**需模型修正**的把"可执行错误信息"回填让它重生成参数，最多两次；**需人工**的中断走 `HITL`；**未知**异常记完整堆栈但不重试。最忌讳所有错误一律重试三次——既费钱又放大故障。

**目标二：`MCP`——把 M×N 变成 M+N**

`Tool Runtime` 是"自己家里怎么管工具"，`MCP`（`Model Context Protocol`）解决的是"怎么跟别人 `share` 工具"。

它解决的是组合爆炸：**M 个模型 × N 个工具 = M×N 套适配**；有了 `MCP` 变成 **M+N**。同一个 `Server`，`Claude Desktop`、你的 `Agent` 平台、`IDE` 都能直接接，不用各写一套。

三个角色要分清：

- **`Host`**：宿主应用，管理连接、决定暴露哪些能力、做权限与确认。
- **`Client`**：`Host` 内部的连接器，跟某个 `Server` 一对一通信。
- **`Server`**：能力提供方，也就是你的业务系统，暴露 `tools` / `resources` / `prompts`。

三类能力是面试高频，一句话记：**`Tools` 是"做"，`Resources` 是"看"，`Prompts` 是"问的模板"。** `Tools` 有副作用、由模型决定调用；`Resources` 是只读数据、由应用或用户挂载；`Prompts` 是预置模板、由用户显式选择。

传输我见过三种：`stdio` 最简单，`Host` 拉起 `Server` 子进程；老项目用 `HTTP + SSE`，`Server` 到 `Client` 单向流；新项目上 `Streamable HTTP`，请求响应加可选流式，正在替代 `SSE`。生命周期是 `initialize` 交换协议版本与 `capability` → `initialized` 通知 → 正常调用 → 关闭。**能力协商是关键**：双方各自声明支持什么，不支持的不能假定存在。

我自己做过一个 11 工具的 `MCP Server`，就是把 `Registry` 里的工具批量适配成 `MCP` 的 `tools` 列表，额外暴露 `resources`（状态卡）和 `prompts`（分析模板）。这事让我分清了"做过"和"读过文档"的分界线——**四个治理点**：

1. 工具描述同样要写"何时不用"，否则模型乱调。
2. **`Server` 侧必须自己做鉴权**，`Host` 传来的身份不能盲信。
3. 返回体积要控，`MCP` 没有强制大小限制，一个 resource 返回 5MB 就能打死上下文。
4. 版本与能力协商要兼容旧 `Client`，否则用户端集体连不上。

最小 `Server` 骨架（关键几行）：

```python
from mcp.server.fastmcp import FastMCP
mcp = FastMCP("order-service")

@mcp.tool()
def query_orders(user_id: str, status: str = "PAID") -> list[dict]:
    """查询用户订单。
    何时使用：用户问"我的订单""订单状态"。
    何时不要用：问退款进度（请用 query_refunds）。"""
    return db.query(user_id=user_id, status=status, limit=50)

@mcp.resource("order://{order_id}/detail")
def order_detail(order_id: str) -> str:
    return json.dumps(db.get(order_id), ensure_ascii=False)

if __name__ == "__main__":
    mcp.run(transport="stdio")
```

**目标三：安全不是一道墙，是一层**

很多人把安全理解成"加个登录"。但在工具调用这条链上，安全是**散落在每一环的约束**，得当成一层来设计。

第一道是**白名单与最小权限**：`allowedTools` 决定这个 `Agent` 能碰哪些工具，别让普通租户摸到管理员工具。第二道是**鉴权**，而且是 `Server` 侧独立校验——`Host` 说"这是张三"你不能全信，要自己验 `token` 和租户；多租户场景下跨租户统一返回 404，防止探测。

第三道是**副作用控制**：创建订单、发消息、扣款这类 `sideEffect == true` 的工具，强制幂等键，没有商量余地。第四道是**体积控制**：这条常被当成性能优化而非安全问题——一个超大返回不只是费钱，它会挤爆上下文、让模型忽略真正重要的约束，本质是一种"可用性安全"。第五道是**审计**：`traceId` 贯穿全链路，输入输出、耗时、`token` 全部留痕，出事能回放。

我把这五道串起来的判断是：**控制权要在代码里，不在模型里。** 模型只负责"在给定节点内做判断"，分支、重试、终止这些控制流永远握在确定性代码手里。安全尤其如此——你不可能靠 `prompt` 让模型"自觉"不越权。

**目标四：不能评测的优化都是猜测**

最后一个目标，也是我最想安利给每个人的：**给工具调用建评估闭环。**

太多"我改了描述，感觉准了"这种话。我的规矩是——任何"我优化了 X"的说法，都要能给出 `baseline`、指标和对照方式。

先说怎么测工具选择准确率：做法很简单，**固定 50 条 `query`，看工具选择准确率，改完描述跑一遍对比，不靠感觉。** 这 50 条要覆盖正常、边界、异常，至少 30% 是异常样本，别全测 `happy path`。

再说实验纪律，核心是**唯一变量原则**：一次实验只允许一个变量不同——模型写死版本（不用 `latest`）、温度设 0、提示词版本化、同一批样本同一顺序、同一个判分器、同机器同并发。**反例就是"我换了模型顺便换了 prompt，效果好了很多"——这种结论没有意义，你根本不知道是谁的功劳。**

工具治理这层，我最看重的指标是**约束违反率、重复调用率、每轮输入 `token`、恢复步数**。举两个自己测出来的数（口径写清楚）：

- 确定性仿真里，关键约束违反率 **33.3% → 0**，重复工具调用 **3 → 0**，恢复步数 **2 → 1**，而任务完成率两组都是 **100%**。
- 注入 `token`：`ContextFS` 侧每查询 **1002.8 → 128.7（−87.2%）**，状态卡侧每轮 **161 → 47（−70.8%）**。

**"完成率持平"特别重要。** 省 `token` 最容易的做法是少给信息，但那会掉正确率。完成率不变 + `token` 大降，才证明是"提效"而不是"偷工减料"。

还有个我很喜欢的实验设计——**无限窗口对照**。有人质疑"token 降是因为你截断了历史，模型看不到所以省了"。解法是加一组不裁剪、全量给、但换更长窗口模型的对照，结果 `token` 仍从 147 降到 47（−68%）。**这就排除了"注意力截断"这个混淆变量，证明省 `token` 是因为注入的信息密度更高。**

判分上我坚持**规则优先、模型兜底**：能量化的（约束违反、终态字段、步数、`token`）用规则，最可靠；实在不行的上 `LLM-as-judge`，但必须先拿 50 条人工标注集校准一致率、给明确 `rubric`、位置随机化。我一般要求规则类指标占 70% 以上。

不过边界必须说清：**确定性仿真只是下限验证。** 它能证明"机制在原理上有效"，不能证明"在真实模型上也有效"。真实 `LLM` 端到端评测是下一步——同模型、温度 0、结构化决策输出、规则判分、每场景 N ≥ 5，看均值 ± 标准差。说得出"仿真只是下限验证"的人，比声称"我验证过了"的人可信得多。

**收个尾**

今天四个目标其实是一条线：**`Tool Runtime` 管一次调用怎么安全落地，`MCP` 管能力怎么标准化复用，安全是贯穿每一环的约束，评估则回答"改完到底有没有变好"。** 合起来就是我一直在说的那句话——**模型只输出意图，真正让它可靠跑完长任务的，是模型之外那层工程。**

这套东西在你那儿大概率不成立的地方是：如果你的工具就三五个、没有副作用、也没有多租户，那 `Runtime` 和 `MCP` 对你就是纯开销，一个 `switch` 反而更香。欢迎说说你的场景。
