package io.asc.core.conflict;

import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三种冲突消解策略的行为测试：同输入，三种策略三种结果——
 * 这正是"插件化"要保证的：策略之间语义隔离，切换配置即可切换行为。
 */
class ConflictResolversTest {

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");
    private static final String TASK = "task-1";

    private static Fact fact(String id, String key, String value, int sec, long version) {
        return new Fact(id, TASK, key, value, T0.plusSeconds(sec), null, "tool", version);
    }

    @Test
    @DisplayName("LATEST_WINS：最新进卡，旧转 SUPERSEDED 指向新 id")
    void latest_wins() {
        List<InvalidatedItem> sink = new ArrayList<>();
        List<Fact> winners = new LatestWinsConflictResolver().resolve(TASK,
                List.of(fact("f1", "sales", "100", 0, 1), fact("f2", "sales", "120", 5, 2)),
                sink, T0);

        assertThat(winners).extracting(Fact::id).containsExactly("f2");
        assertThat(sink).hasSize(1);
        assertThat(sink.getFirst().reason()).isEqualTo(InvalidatedItem.Reason.SUPERSEDED.name());
        assertThat(sink.getFirst().supersededBy()).isEqualTo("f2");
        assertThat(sink.getFirst().source()).isEqualTo(FactConflictResolver.COMPILER_SOURCE);
    }

    @Test
    @DisplayName("MANUAL：无冲突照常进卡；同 key 多条全部转 CONFLICT，一格都不放")
    void manual_review() {
        ManualReviewConflictResolver resolver = new ManualReviewConflictResolver();

        // 单条：正常进卡
        List<InvalidatedItem> sink1 = new ArrayList<>();
        List<Fact> ok = resolver.resolve(TASK,
                List.of(fact("f1", "city", "上海", 0, 1)), sink1, T0);
        assertThat(ok).extracting(Fact::id).containsExactly("f1");
        assertThat(sink1).isEmpty();

        // 冲突：全部候选转 CONFLICT，supersededBy 为空
        List<InvalidatedItem> sink2 = new ArrayList<>();
        List<Fact> winners = resolver.resolve(TASK,
                List.of(fact("f1", "sales", "100", 0, 1), fact("f2", "sales", "120", 5, 2)),
                sink2, T0);
        assertThat(winners).isEmpty();
        assertThat(sink2).hasSize(2)
                .allSatisfy(i -> {
                    assertThat(i.reason()).isEqualTo(InvalidatedItem.Reason.CONFLICT.name());
                    assertThat(i.supersededBy()).isNull();
                });
    }

    @Test
    @DisplayName("REJECT：无冲突原样返回；同 key 多条抛 ConflictDetectedException")
    void reject() {
        RejectConflictResolver resolver = new RejectConflictResolver();

        List<Fact> ok = resolver.resolve(TASK,
                List.of(fact("f1", "sales", "120", 0, 1)), new ArrayList<>(), T0);
        assertThat(ok).hasSize(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve(TASK,
                List.of(fact("f1", "sales", "100", 0, 1), fact("f2", "sales", "120", 5, 2)),
                new ArrayList<>(), T0))
                .isInstanceOf(ConflictDetectedException.class)
                .hasMessageContaining("sales")
                .hasMessageContaining(TASK);
    }

    @Test
    @DisplayName("不同 key 互不干扰：每组独立消解，组序保持首次出现顺序")
    void different_keys_independent() {
        List<InvalidatedItem> sink = new ArrayList<>();
        List<Fact> winners = new LatestWinsConflictResolver().resolve(TASK,
                List.of(fact("f1", "a", "1", 0, 1), fact("f2", "b", "2", 1, 2),
                        fact("f3", "a", "9", 2, 3)),
                sink, T0);

        assertThat(winners).extracting(Fact::id).containsExactly("f3", "f2");
        assertThat(sink).hasSize(1);
    }
}
