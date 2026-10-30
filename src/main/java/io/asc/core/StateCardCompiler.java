package io.asc.core;

import io.asc.core.conflict.ConflictStrategy;
import io.asc.core.conflict.ConflictStrategyProperties;
import io.asc.core.conflict.FactConflictResolver;
import io.asc.core.conflict.LatestWinsConflictResolver;
import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Todo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * L2 状态编译器：把任务的全量状态项编译成一张 StateCard，并支持按预算裁剪。
 *
 * <p>这是 README 架构图里标 ★ 的核心层。编排逻辑（查任务、取状态项）在
 * application 层，这里只做纯规则转换，输入输出都是 domain 类型：
 *
 * <ol>
 *   <li><b>TTL 过滤</b>：过期的 Fact 不再进已确认事实，转成
 *       InvalidatedItem(EXPIRED) 进"过期/冲突"格——Agent 能看到"这条为什么没了"，
 *       而不是静默消失；</li>
 *   <li><b>事实冲突消解（可插拔 SPI）</b>：同 key 多条活事实交给
 *       {@link FactConflictResolver} 处理。策略由
 *       {@code asc.compiler.fact-conflict-strategy} 配置选择：
 *       LATEST_WINS（默认，最新进卡旧转 SUPERSEDED）/
 *       MANUAL（冲突全部转 CONFLICT 等人工裁决）/
 *       REJECT（写入侧拦截为主，编译兜底抛异常）——
 *       新增策略实现 SPI + @Component 即可，编译器不改；</li>
 *   <li><b>约束分格</b>：HARD / SOFT 各进一格；</li>
 *   <li><b>待办过滤</b>：只保留 OPEN，DONE / CANCELLED 不进卡；</li>
 *   <li><b>存量 InvalidatedItem 透传</b>：之前持久化过的失效记录原样进卡；</li>
 *   <li><b>预算裁剪（trim）</b>：按 token 预算从低价值格开始丢
 *       （失效记录 → 软约束 → 待办 → 事实），硬约束永不裁剪。</li>
 * </ol>
 *
 * <p>消费顺序即优先级：先 TTL 过滤再冲突消解——已过期的事实不参与"谁覆盖谁"，
 * 它的失效原因记 EXPIRED 而不是 SUPERSEDED，语义更准。
 *
 * <p>compile 的 now 由调用方传入：编译结果完全由 (taskId, items, now) 决定，
 * 同输入必同输出，测试可确定、将来可离线重放。
 */
@Component
public class StateCardCompiler {

    /** 每条失效记录的固定 token 估值：reason + supersededBy 等元数据开销。 */
    private static final int INVALIDATED_TOKENS = 20;

    private final FactConflictResolver conflictResolver;

    /** Spring 装配：按配置选择策略实现；配置非法在启动期即失败。 */
    @Autowired
    public StateCardCompiler(List<FactConflictResolver> resolvers,
                             ConflictStrategyProperties properties) {
        ConflictStrategy strategy = properties.get();
        this.conflictResolver = resolvers.stream()
                .filter(r -> r.strategy() == strategy)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no FactConflictResolver registered for strategy " + strategy));
    }

    /** 便捷构造：默认 LATEST_WINS（测试 / 手工装配）。 */
    public StateCardCompiler() {
        this(List.of(new LatestWinsConflictResolver()),
                new ConflictStrategyProperties("LATEST_WINS"));
    }

    /**
     * 编译全量状态卡（不裁剪）。裁剪由 {@link #trim} 单独承担：
     * 持久化和回放用全量卡，注入 Agent 前才按预算裁。
     *
     * @param taskId 任务 ID
     * @param items  该任务的全量状态项（不要求有序）
     * @param now    编译时刻，用于 TTL 判断和失效记录的时间戳
     */
    public StateCard compile(String taskId, List<StateItem> items, Instant now) {
        List<Constraint> hard = new ArrayList<>();
        List<Constraint> soft = new ArrayList<>();
        List<Todo> openTodos = new ArrayList<>();
        List<InvalidatedItem> invalidated = new ArrayList<>();
        List<Fact> liveFacts = new ArrayList<>();

        /* ---------- 1. 分类 + TTL 过滤 ---------- */
        for (StateItem item : items) {
            switch (item) {
                case Constraint c -> {
                    if (c.level() == Constraint.ConstraintLevel.HARD) hard.add(c);
                    else soft.add(c);
                }
                case Fact f -> {
                    if (f.isExpired(now)) {
                        invalidated.add(new InvalidatedItem(f.id(), taskId,
                                InvalidatedItem.Reason.EXPIRED.name(), null,
                                now, FactConflictResolver.COMPILER_SOURCE, f.version()));
                    } else {
                        liveFacts.add(f);
                    }
                }
                case Todo t -> {
                    if (t.status() == Todo.TodoStatus.OPEN) openTodos.add(t);
                }
                case InvalidatedItem i -> invalidated.add(i);
            }
        }

        /* ---------- 2. 事实冲突消解（SPI，按配置策略） ---------- */
        List<Fact> winners = conflictResolver.resolve(taskId, liveFacts, invalidated, now);

        /* ---------- 3. 排序后组卡 ---------- */
        hard.sort(Comparator.comparing(StateItem::createdAt));
        soft.sort(Comparator.comparing(StateItem::createdAt));
        openTodos.sort(Comparator.comparing(StateItem::createdAt));
        winners.sort(Comparator.comparing(Fact::createdAt));
        invalidated.sort(Comparator.comparing(InvalidatedItem::createdAt)
                .thenComparing(InvalidatedItem::id));

        long version = items.stream().mapToLong(StateItem::version).max().orElse(0);
        return new StateCard(taskId, version, List.copyOf(hard), List.copyOf(soft),
                List.copyOf(winners), List.copyOf(openTodos), List.copyOf(invalidated),
                estimateTokens(hard, soft, winners, openTodos, invalidated), now);
    }

    /**
     * 按预算裁剪状态卡，返回裁剪后的新卡（入参不变）。
     *
     * <p>裁剪规则：
     * <ul>
     *   <li>硬约束永不裁剪——ASC 的意义就是保住它们；</li>
     *   <li>丢弃优先级（从先到后）：失效记录 → 软约束 → 待办 → 事实，
     *       越靠后的信息越直接支撑"下一步决策"；</li>
     *   <li>同格内先丢最老的（createdAt 升序排队，从队首丢）；</li>
     *   <li>整条丢弃，不做截断——半条事实比没有更危险。</li>
     * </ul>
     * 预算内直接原样返回。
     */
    public StateCard trim(StateCard card, int budgetTokens) {
        if (card.estimatedTokens() <= budgetTokens) {
            return card;
        }

        Deque<InvalidatedItem> invalidated = new ArrayDeque<>(card.invalidated());
        Deque<Constraint> soft = new ArrayDeque<>(card.softConstraints());
        Deque<Todo> todos = new ArrayDeque<>(card.openTodos());
        Deque<Fact> facts = new ArrayDeque<>(card.verifiedFacts());

        int used = card.estimatedTokens();
        while (used > budgetTokens) {
            if (!invalidated.isEmpty()) {
                used -= tokensOf(invalidated.pollFirst());
            } else if (!soft.isEmpty()) {
                used -= tokensOf(soft.pollFirst());
            } else if (!todos.isEmpty()) {
                used -= tokensOf(todos.pollFirst());
            } else if (!facts.isEmpty()) {
                used -= tokensOf(facts.pollFirst());
            } else {
                break;  // 只剩硬约束，宁超不裁
            }
        }

        List<Constraint> keptSoft = List.copyOf(soft);
        List<Todo> keptTodos = List.copyOf(todos);
        List<Fact> keptFacts = List.copyOf(facts);
        List<InvalidatedItem> keptInvalidated = List.copyOf(invalidated);

        int estimated = estimateTokens(card.hardConstraints(), keptSoft, keptFacts,
                keptTodos, keptInvalidated);
        return new StateCard(card.taskId(), card.version(), card.hardConstraints(),
                keptSoft, keptFacts, keptTodos, keptInvalidated,
                estimated, card.compiledAt());
    }

    /* ---------- 内部步骤 ---------- */

    /** 卡的估算 token：保留项逐条求和，空卡至少 1。与 {@link #tokensOf} 同一套规则，trim 的算术才自洽。 */
    private int estimateTokens(List<Constraint> hard, List<Constraint> soft,
                               List<Fact> facts, List<Todo> todos,
                               List<InvalidatedItem> invalidated) {
        int sum = 0;
        for (Constraint c : hard) sum += tokensOf(c);
        for (Constraint c : soft) sum += tokensOf(c);
        for (Fact f : facts) sum += tokensOf(f);
        for (Todo t : todos) sum += tokensOf(t);
        for (InvalidatedItem i : invalidated) sum += tokensOf(i);
        return Math.max(1, sum);
    }

    /** 单条状态项的 token 估值：约 4 字符 1 token（向上取整），失效记录固定 20。 */
    private int tokensOf(StateItem item) {
        return switch (item) {
            case Constraint c -> charsToTokens(c.text().length());
            case Fact f -> charsToTokens(f.key().length() + f.value().length());
            case Todo t -> charsToTokens(t.text().length());
            case InvalidatedItem i -> INVALIDATED_TOKENS;
        };
    }

    private int charsToTokens(int chars) {
        return Math.max(1, (chars + 3) / 4);
    }
}
