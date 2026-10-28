package io.asc.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 领域模型的单元测试。
 *
 * <p>这一层不启动 Spring，不连数据库，纯 Java 测试，跑得最快。
 * 覆盖三类东西：业务不变量、TTL 判定、状态卡不可变性。
 */
class StateModelTest {

    /**
     * 约束的 text 不能为空白。
     * 这是领域不变量，不管从 HTTP、MQ 还是测试进来都必须成立。
     */
    @Test
    void constraint_rejects_blank_text() {
        assertThatThrownBy(() -> new Constraint(
                "c1", "t1", " ", Constraint.ConstraintLevel.HARD,
                Instant.now(), "user", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("constraint text");
    }

    /**
     * 带 TTL 的事实，在 expiresAt 之后应判定为过期。
     * 注意这里传入固定 now，不依赖真实时钟，测试结果稳定。
     */
    @Test
    void fact_is_expired_after_expires_at() {
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        Fact f = new Fact("f1", "t1", "k", "v",
                now.minusSeconds(60), now.minusSeconds(1), "tool", 1);
        assertThat(f.isExpired(now)).isTrue();
    }

    /**
     * expiresAt 为 null 的事实永不过期。
     * 比如“用户所在城市是上海”这类长期有效信息。
     */
    @Test
    void fact_without_ttl_never_expires() {
        Fact f = new Fact("f1", "t1", "k", "v",
                Instant.now(), null, "tool", 1);
        assertThat(f.isExpired(Instant.now().plusSeconds(3600))).isFalse();
    }

    /**
     * 状态卡内部的 List 必须不可变。
     * 防止调用方拿到卡后修改列表，破坏“某一时刻快照”的语义。
     */
    @Test
    void state_card_lists_are_immutable() {
        StateCard card = new StateCard("t1", 1,
                List.of(), List.of(), List.of(), List.of(), List.of(), 0, Instant.now());
        assertThatThrownBy(() -> card.hardConstraints().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}