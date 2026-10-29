package io.asc.domain;

import java.time.Instant;
import java.util.List;

/**
 * 状态卡：某一版本下，给 Agent 看的最小状态集合。
 *
 * <p>这是 ASC 的核心产物。Agent 每轮决策前拿到的不是整段历史，
 * 而是这张卡：硬约束、软约束、已验证事实、未完成待办、失效摘要。
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有 List 在构造器里用 List.copyOf 拷贝成不可变集合。
 *       防止调用方拿到卡后修改内部列表，破坏“状态卡是某一时刻快照”的语义。</li>
 *   <li>estimatedTokens 让调用方判断“这张卡塞进 LLM 上下文会不会超预算”。</li>
 *   <li>version 让调用方对比版本号，判断拿到的卡是否过期。</li>
 * </ul>
 */
public record StateCard(
        /* 这张状态卡属于哪个任务。 */
        String taskId,

        /* 编译时的任务版本号。Agent 据此判断“手里的卡是不是最新版”，也是回放和审计的定位依据。 */
        long version,

        /* 必须遵守的硬约束，如“只查最近 30 天”“无删除权限”。每轮必须注入，预算裁剪时永不丢弃。 */
        List<Constraint> hardConstraints,

        /* 可覆盖的软约束（建议性边界）。token 预算不足时先于硬约束被裁剪。 */
        List<Constraint> softConstraints,

        /* 已确认事实：工具返回或人工确认过的可信信息，如“上海上周销量 120”。Fact 的 key 是冲突消解的锚点。 */
        List<Fact> verifiedFacts,

        /* 待办：下一步依赖的未完成事项，如“杭州数据待查”。只包含 OPEN 状态的待办。 */
        List<Todo> openTodos,

        /* 作废项：被覆盖、过期、冲突、拒绝的旧状态，如“旧销量 100 已被 120 覆盖”。防止模型继续引用旧值。 */
        List<InvalidatedItem> invalidated,

        /* 整卡估算的 token 数。编译层按 budget-tokens 裁剪后写回，让调用方知道这张卡占多少上下文预算。 */
        int estimatedTokens,

        /* 状态卡编译完成的时间戳。用于审计：“当时模型看到的是哪一版、什么时候编译的”。 */
        Instant compiledAt
) {
    /**
     * 紧凑构造器：把外部传入的 List 拷贝成不可变副本。
     *
     * <p>List.copyOf 会做三件事：
     * 1. 拒绝 null 元素；
     * 2. 如果传入的已经是不可变 List，直接复用；
     * 3. 否则拷贝一份，之后外部修改原 List 不会影响状态卡。
     *
     * <p>为什么重要：状态卡代表“某一时刻的快照”，如果内部 List 还能被外部改，
     * 回放和审计就对不上了。
     */
    public StateCard {
        hardConstraints = List.copyOf(hardConstraints);
        softConstraints = List.copyOf(softConstraints);
        verifiedFacts = List.copyOf(verifiedFacts);
        openTodos = List.copyOf(openTodos);
        invalidated = List.copyOf(invalidated);
    }

    /**
     * 渲染成可直接拼进 system prompt 的文本。
     *
     * <p>四格分区，空格省略（省 token）；失效项标注失效原因和替代者。
     * 典型用法：{@code String prompt = card.toPrompt() + "\n\n用户输入：" + userInput}。
     */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder()
                .append("【任务状态卡】task=").append(taskId)
                .append(" v").append(version).append('\n');
        if (!hardConstraints.isEmpty()) {
            sb.append("【必须遵守】\n");
            for (Constraint c : hardConstraints) {
                sb.append("- ").append(c.text()).append('\n');
            }
        }
        if (!softConstraints.isEmpty()) {
            sb.append("【软约束（尽量遵守）】\n");
            for (Constraint c : softConstraints) {
                sb.append("- ").append(c.text()).append('\n');
            }
        }
        if (!verifiedFacts.isEmpty()) {
            sb.append("【已确认事实】\n");
            for (Fact f : verifiedFacts) {
                sb.append("- ").append(f.key()).append(" = ").append(f.value()).append('\n');
            }
        }
        if (!openTodos.isEmpty()) {
            sb.append("【待办】\n");
            for (Todo t : openTodos) {
                sb.append("- ").append(t.text()).append('\n');
            }
        }
        if (!invalidated.isEmpty()) {
            sb.append("【已失效/冲突，勿再使用】\n");
            for (InvalidatedItem i : invalidated) {
                sb.append("- ").append(i.id()).append(": ").append(i.reason());
                if (i.supersededBy() != null) {
                    sb.append("（已被 ").append(i.supersededBy()).append(" 取代）");
                }
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}