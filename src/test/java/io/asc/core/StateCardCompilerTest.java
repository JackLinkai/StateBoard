package io.asc.core;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态编译器的纯单元测试：无框架、无数据库，编译是 (taskId, items, now) → card 的纯函数。
 *
 * <p>锁定阶段 2 的核心规则语义：
 * TTL 过滤把过期事实转为 EXPIRED 失效记录、
 * LATEST_WINS 把同 key 旧事实转为 SUPERSEDED 并指向获胜者、
 * 过期事实不参与冲突消解（失效原因是 EXPIRED 而非 SUPERSEDED）。
 */
class StateCardCompilerTest {

    private final StateCardCompiler compiler = new StateCardCompiler();

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String TASK = "task-1";

    private Fact factAt(String id, String key, String value, Instant createdAt) {
        return new Fact(id, TASK, key, value, createdAt, null, "tool", 1);
    }

    @Test
    @DisplayName("TTL：过期事实不进已确认事实，转成 EXPIRED 失效记录")
    void expired_fact_moves_to_invalidated() {
        Fact expired = new Fact("f-exp", TASK, "k", "v",
                NOW.minusSeconds(120), NOW.minusSeconds(60), "tool", 1);
        Fact live = factAt("f-live", "k2", "v2", NOW.minusSeconds(30));

        StateCard card = compiler.compile(TASK, List.of(expired, live), NOW);

        assertThat(card.verifiedFacts()).containsExactly(live);
        InvalidatedItem item = card.invalidated().getFirst();
        assertThat(item.id()).isEqualTo("f-exp");
        assertThat(item.reason()).isEqualTo(InvalidatedItem.Reason.EXPIRED.name());
        assertThat(item.supersededBy()).isNull();
        assertThat(item.source()).isEqualTo(StateCardCompiler.COMPILER_SOURCE);
    }

    @Test
    @DisplayName("LATEST_WINS：同 key 两条事实，最新进卡，旧转 SUPERSEDED 并指向新 id")
    void same_key_latest_wins() {
        Fact old = factAt("f-old", "sales", "100", NOW.minusSeconds(60));
        Fact latest = factAt("f-new", "sales", "120", NOW.minusSeconds(10));

        StateCard card = compiler.compile(TASK, List.of(old, latest), NOW);

        assertThat(card.verifiedFacts()).containsExactly(latest);
        InvalidatedItem item = card.invalidated().getFirst();
        assertThat(item.id()).isEqualTo("f-old");
        assertThat(item.reason()).isEqualTo(InvalidatedItem.Reason.SUPERSEDED.name());
        assertThat(item.supersededBy()).isEqualTo("f-new");
        assertThat(item.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("冲突消解按 createdAt 排序（并列时比 version），与输入顺序无关")
    void conflict_order_independent_of_input() {
        // 旧事实放在列表后面
        Fact latest = factAt("f-new", "sales", "120", NOW.minusSeconds(10));
        Fact old = factAt("f-old", "sales", "100", NOW.minusSeconds(60));

        StateCard card = compiler.compile(TASK, List.of(latest, old), NOW);

        assertThat(card.verifiedFacts()).containsExactly(latest);
        assertThat(card.invalidated().getFirst().supersededBy()).isEqualTo("f-new");
    }

    @Test
    @DisplayName("不同 key 不冲突：各自进卡，无失效记录")
    void different_keys_no_conflict() {
        Fact a = factAt("f-a", "shanghai", "100", NOW.minusSeconds(60));
        Fact b = factAt("f-b", "beijing", "80", NOW.minusSeconds(30));

        StateCard card = compiler.compile(TASK, List.of(a, b), NOW);

        assertThat(card.verifiedFacts()).containsExactlyInAnyOrder(a, b);
        assertThat(card.invalidated()).isEmpty();
    }

    @Test
    @DisplayName("过期事实不参与冲突消解：失效原因是 EXPIRED 而非 SUPERSEDED")
    void expired_fact_not_in_conflict_pool() {
        Fact expiredOld = new Fact("f-exp", TASK, "sales", "100",
                NOW.minusSeconds(600), NOW.minusSeconds(300), "tool", 1);
        Fact latest = factAt("f-new", "sales", "120", NOW.minusSeconds(10));

        StateCard card = compiler.compile(TASK, List.of(expiredOld, latest), NOW);

        assertThat(card.verifiedFacts()).containsExactly(latest);
        assertThat(card.invalidated()).hasSize(1);
        assertThat(card.invalidated().getFirst().reason())
                .isEqualTo(InvalidatedItem.Reason.EXPIRED.name());
    }

    @Test
    @DisplayName("三分支并发：同 key 三条，最新获胜，两条旧的都 SUPERSEDED")
    void three_way_conflict() {
        Fact f1 = factAt("f1", "k", "a", NOW.minusSeconds(90));
        Fact f2 = factAt("f2", "k", "b", NOW.minusSeconds(60));
        Fact f3 = factAt("f3", "k", "c", NOW.minusSeconds(30));

        StateCard card = compiler.compile(TASK, List.of(f1, f2, f3), NOW);

        assertThat(card.verifiedFacts()).containsExactly(f3);
        assertThat(card.invalidated())
                .extracting(InvalidatedItem::id)
                .containsExactlyInAnyOrder("f1", "f2");
        assertThat(card.invalidated())
                .allMatch(i -> i.supersededBy().equals("f3"));
    }

    @Test
    @DisplayName("约束分格：HARD/SOFT 各进一格，按 createdAt 排序")
    void constraints_grouped_by_level() {
        Constraint hard2 = new Constraint("c2", TASK, "硬2", Constraint.ConstraintLevel.HARD,
                NOW.minusSeconds(10), "user", 2);
        Constraint hard1 = new Constraint("c1", TASK, "硬1", Constraint.ConstraintLevel.HARD,
                NOW.minusSeconds(60), "user", 1);
        Constraint soft1 = new Constraint("c3", TASK, "软1", Constraint.ConstraintLevel.SOFT,
                NOW.minusSeconds(30), "user", 3);

        StateCard card = compiler.compile(TASK, List.of(hard2, soft1, hard1), NOW);

        assertThat(card.hardConstraints()).containsExactly(hard1, hard2);
        assertThat(card.softConstraints()).containsExactly(soft1);
    }

    @Test
    @DisplayName("待办过滤：只保留 OPEN，DONE/CANCELLED 不进卡")
    void todos_filtered_to_open() {
        Todo open = new Todo("t1", TASK, "待查", Todo.TodoStatus.OPEN, NOW, "model", 1);
        Todo done = new Todo("t2", TASK, "完成", Todo.TodoStatus.DONE, NOW, "model", 2);
        Todo cancelled = new Todo("t3", TASK, "取消", Todo.TodoStatus.CANCELLED, NOW, "model", 3);

        StateCard card = compiler.compile(TASK, List.of(open, done, cancelled), NOW);

        assertThat(card.openTodos()).containsExactly(open);
    }

    @Test
    @DisplayName("存量 InvalidatedItem 原样透传进卡")
    void existing_invalidated_passthrough() {
        InvalidatedItem stored = new InvalidatedItem("i1", TASK,
                InvalidatedItem.Reason.REJECTED.name(), null, NOW, "human", 5);

        StateCard card = compiler.compile(TASK, List.of(stored), NOW);

        assertThat(card.invalidated()).containsExactly(stored);
    }

    @Test
    @DisplayName("版本号取所有状态项（含透传失效项）的最大 version，空任务为 0")
    void version_is_max_of_items() {
        Constraint c = new Constraint("c1", TASK, "硬", Constraint.ConstraintLevel.HARD,
                NOW, "user", 9);
        InvalidatedItem stored = new InvalidatedItem("i1", TASK,
                "REJECTED", null, NOW, "human", 3);
        StateCard full = compiler.compile(TASK, List.of(c, stored), NOW);
        StateCard empty = compiler.compile(TASK, List.of(), NOW);

        assertThat(full.version()).isEqualTo(9);
        assertThat(empty.version()).isZero();
        assertThat(empty.verifiedFacts()).isEmpty();
        assertThat(empty.invalidated()).isEmpty();
        assertThat(empty.compiledAt()).isEqualTo(NOW);
    }

    /* ---------- trim：预算裁剪 ---------- */

    /**
     * 组一张"富"卡：1 硬约束(4 tok) + 1 事实(2 tok) + 2 待办(各3) + 2 软约束(各3)
     * + 3 存量失效记录(各20)，全量估算 4+2+6+6+60 = 78。
     */
    private StateCard richCard() {
        List<StateItem> items = List.of(
                new Constraint("c-hard", TASK, "硬约束只允许查询最近三十天数据",
                        Constraint.ConstraintLevel.HARD, NOW.minusSeconds(90), "user", 1),
                factAt("f-win", "sales", "120", NOW.minusSeconds(30)),
                new Todo("t-old", TASK, "待办一待办一待办一待办",
                        Todo.TodoStatus.OPEN, NOW.minusSeconds(80), "model", 2),
                new Todo("t-new", TASK, "待办二待办二待办二待办",
                        Todo.TodoStatus.OPEN, NOW.minusSeconds(40), "model", 3),
                new Constraint("c-soft1", TASK, "软约束一软约束一软约束一",
                        Constraint.ConstraintLevel.SOFT, NOW.minusSeconds(70), "user", 4),
                new Constraint("c-soft2", TASK, "软约束二软约束二软约束二",
                        Constraint.ConstraintLevel.SOFT, NOW.minusSeconds(50), "user", 5),
                new InvalidatedItem("i-1", TASK, "REJECTED", null, NOW, "human", 2),
                new InvalidatedItem("i-2", TASK, "REJECTED", null, NOW, "human", 3),
                new InvalidatedItem("i-3", TASK, "REJECTED", null, NOW, "human", 4));
        return compiler.compile(TASK, items, NOW);
    }

    @Test
    @DisplayName("trim：预算内直接原样返回同一实例")
    void trim_within_budget_returns_same_card() {
        StateCard card = richCard();

        assertThat(compiler.trim(card, 78)).isSameAs(card);
        assertThat(compiler.trim(card, 1000)).isSameAs(card);
    }

    @Test
    @DisplayName("trim：先丢失效记录（20 tok/条），其他格不动")
    void trim_drops_invalidated_first() {
        StateCard card = richCard();  // 78 tok

        StateCard trimmed = compiler.trim(card, 70);  // 78-20=58 ≤ 70

        assertThat(trimmed.invalidated()).hasSize(2);
        assertThat(trimmed.softConstraints()).hasSize(2);
        assertThat(trimmed.openTodos()).hasSize(2);
        assertThat(trimmed.verifiedFacts()).hasSize(1);
        assertThat(trimmed.hardConstraints()).hasSize(1);
    }

    @Test
    @DisplayName("trim：失效记录丢完后丢软约束，再丢待办，最后丢事实")
    void trim_drop_priority_order() {
        StateCard card = richCard();

        // 78→58→38→18：三条失效全丢
        StateCard noInvalidated = compiler.trim(card, 30);
        assertThat(noInvalidated.invalidated()).isEmpty();
        assertThat(noInvalidated.softConstraints()).hasSize(2);
        assertThat(noInvalidated.openTodos()).hasSize(2);
        assertThat(noInvalidated.verifiedFacts()).hasSize(1);

        // 18→15→12：软约束逐条丢
        StateCard noSoft = compiler.trim(card, 12);
        assertThat(noSoft.invalidated()).isEmpty();
        assertThat(noSoft.softConstraints()).isEmpty();
        assertThat(noSoft.openTodos()).hasSize(2);
        assertThat(noSoft.verifiedFacts()).hasSize(1);

        // 12→9→6：待办逐条丢
        StateCard noTodos = compiler.trim(card, 6);
        assertThat(noTodos.openTodos()).isEmpty();
        assertThat(noTodos.verifiedFacts()).hasSize(1);

        // 6→3：事实也可丢，只剩硬约束（宁超不裁）
        StateCard hardOnly = compiler.trim(card, 1);
        assertThat(hardOnly.verifiedFacts()).isEmpty();
        assertThat(hardOnly.hardConstraints()).hasSize(1);
        assertThat(hardOnly.softConstraints()).isEmpty();
        assertThat(hardOnly.openTodos()).isEmpty();
        assertThat(hardOnly.invalidated()).isEmpty();
    }

    @Test
    @DisplayName("trim：同格内先丢最老的")
    void trim_drops_oldest_first() {
        // 硬 4 tok + 两条事实各 1 tok（key/value 都是 2 字符），全量 6
        List<StateItem> items = List.of(
                new Constraint("c-hard", TASK, "硬约束只允许查询最近三十天数据",
                        Constraint.ConstraintLevel.HARD, NOW.minusSeconds(90), "user", 1),
                factAt("f-a1", "a1", "v1", NOW.minusSeconds(60)),
                factAt("f-b2", "b2", "v2", NOW.minusSeconds(30)));
        StateCard card = compiler.compile(TASK, items, NOW);

        // 预算 5：丢最老的 f-a1，留 f-b2
        StateCard trimmed = compiler.trim(card, 5);

        assertThat(trimmed.verifiedFacts())
                .extracting(Fact::id)
                .containsExactly("f-b2");
    }

    @Test
    @DisplayName("trim：硬约束永不裁剪，即使它自己就超预算")
    void trim_never_drops_hard_constraints() {
        // 硬约束文本很长（40 字符 ≈ 10 tok），远超预算
        List<StateItem> items = List.of(new Constraint("c-big", TASK, "硬".repeat(40),
                Constraint.ConstraintLevel.HARD, NOW, "user", 1));
        StateCard card = compiler.compile(TASK, items, NOW);

        StateCard trimmed = compiler.trim(card, 2);

        assertThat(trimmed.hardConstraints()).hasSize(1);
        assertThat(trimmed.estimatedTokens()).isEqualTo(card.estimatedTokens());
    }
}
