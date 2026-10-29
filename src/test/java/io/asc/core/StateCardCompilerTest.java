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
}
