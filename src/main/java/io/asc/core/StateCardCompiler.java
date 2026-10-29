package io.asc.core;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Todo;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L2 状态编译器：把任务的全量状态项编译成一张 StateCard。
 *
 * <p>这是 README 架构图里标 ★ 的核心层。编排逻辑（查任务、取状态项）在
 * application 层，这里只做纯规则转换，输入输出都是 domain 类型：
 *
 * <ol>
 *   <li><b>TTL 过滤</b>：过期的 Fact 不再进已确认事实，转成
 *       InvalidatedItem(EXPIRED) 进"过期/冲突"格——Agent 能看到"这条为什么没了"，
 *       而不是静默消失；</li>
 *   <li><b>事实冲突消解（LATEST_WINS）</b>：同 key 的活事实按 createdAt（并列比
 *       version）取最新一条进卡，旧事实转成 InvalidatedItem(SUPERSEDED)，
 *       supersededBy 指向最新一条——对应"销量 100 被 120 覆盖"；</li>
 *   <li><b>约束分格</b>：HARD / SOFT 各进一格；</li>
 *   <li><b>待办过滤</b>：只保留 OPEN，DONE / CANCELLED 不进卡；</li>
 *   <li><b>存量 InvalidatedItem 透传</b>：之前持久化过的失效记录原样进卡。</li>
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

    /** 衍生失效记录的来源标识：由编译器生成，而非 user / tool 写入。 */
    static final String COMPILER_SOURCE = "compiler";

    /**
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
                                now, COMPILER_SOURCE, f.version()));
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

        /* ---------- 2. LATEST_WINS 冲突消解 ---------- */
        List<Fact> winners = resolveConflicts(taskId, liveFacts, invalidated, now);

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
     * 同 key 冲突消解：每组按 createdAt 升序排（并列比 version），最后一条获胜，
     * 其余转 InvalidatedItem(SUPERSEDED)，supersededBy 指向获胜者。
     */
    private List<Fact> resolveConflicts(String taskId, List<Fact> liveFacts,
                                        List<InvalidatedItem> invalidated, Instant now) {
        Map<String, List<Fact>> byKey = new LinkedHashMap<>();
        for (Fact fact : liveFacts) {
            byKey.computeIfAbsent(fact.key(), k -> new ArrayList<>()).add(fact);
        }

        List<Fact> winners = new ArrayList<>();
        for (List<Fact> group : byKey.values()) {
            group.sort(Comparator.comparing(Fact::createdAt).thenComparingLong(Fact::version));
            Fact winner = group.getLast();
            winners.add(winner);
            for (int i = 0; i < group.size() - 1; i++) {
                Fact loser = group.get(i);
                invalidated.add(new InvalidatedItem(loser.id(), taskId,
                        InvalidatedItem.Reason.SUPERSEDED.name(), winner.id(),
                        now, COMPILER_SOURCE, loser.version()));
            }
        }
        return winners;
    }

    /** 粗估 token：4 字符 ≈ 1 token；每条失效记录摊 20；空卡至少 1。 */
    private int estimateTokens(List<Constraint> hard, List<Constraint> soft,
                               List<Fact> facts, List<Todo> todos,
                               List<InvalidatedItem> invalidated) {
        int chars = 0;
        for (Constraint c : hard) chars += c.text().length();
        for (Constraint c : soft) chars += c.text().length();
        for (Fact f : facts) chars += f.key().length() + f.value().length();
        for (Todo t : todos) chars += t.text().length();
        return Math.max(1, chars / 4 + invalidated.size() * 20);
    }
}
