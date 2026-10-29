package io.asc.storage.converter;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateItem;
import io.asc.domain.Todo;
import io.asc.storage.entity.StateItemEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StateItemConverter 纯单元测试：不依赖数据库。
 *
 * <p>重点验证 round-trip：domain → entity → domain 之后 record 的 equals 相等，
 * 包括可空字段（Fact.expiresAt、InvalidatedItem.supersededBy）在 null 与非 null 两种情况下都不丢、不错位。
 * 数据库层面的 jsonb 读写由 StateItemStoreTest（Testcontainers）覆盖。
 */
class StateItemConverterTest {

    private final StateItemConverter converter = new StateItemConverter(JsonMapper.builder().build());

    private static final Instant CREATED = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    @DisplayName("Fact：带 TTL，round-trip 后 equals 相等，type=FACT")
    void fact_round_trip_with_ttl() {
        Fact fact = new Fact("f-1", "task-1", "shanghai_sales_last_7d", "120",
                CREATED, Instant.parse("2026-11-05T00:00:00Z"), "tool:query_sales", 7);

        StateItemEntity entity = converter.toEntity(fact);

        assertThat(entity.getType()).isEqualTo("FACT");
        assertThat(entity.getPayload()).contains("shanghai_sales_last_7d").contains("120");
        assertThat(converter.toDomain(entity)).isEqualTo(fact);
    }

    @Test
    @DisplayName("Fact：无 TTL（expiresAt=null），round-trip 后仍然 equals")
    void fact_round_trip_without_ttl() {
        Fact fact = new Fact("f-2", "task-1", "city_resolved", "上海",
                CREATED, null, "model", 3);

        StateItemEntity entity = converter.toEntity(fact);

        assertThat(entity.getPayload()).doesNotContain("expiresAt");
        assertThat(converter.toDomain(entity)).isEqualTo(fact);
    }

    @Test
    @DisplayName("Constraint：HARD 等级 round-trip 相等，type=CONSTRAINT")
    void constraint_round_trip() {
        Constraint constraint = new Constraint("c-1", "task-1", "只允许查询最近30天数据",
                Constraint.ConstraintLevel.HARD, CREATED, "user", 1);

        StateItemEntity entity = converter.toEntity(constraint);

        assertThat(entity.getType()).isEqualTo("CONSTRAINT");
        assertThat(converter.toDomain(entity)).isEqualTo(constraint);
    }

    @Test
    @DisplayName("Todo：OPEN 状态 round-trip 相等，type=TODO")
    void todo_round_trip() {
        Todo todo = new Todo("t-1", "task-1", "杭州数据待查",
                Todo.TodoStatus.OPEN, CREATED, "model", 5);

        StateItemEntity entity = converter.toEntity(todo);

        assertThat(entity.getType()).isEqualTo("TODO");
        assertThat(converter.toDomain(entity)).isEqualTo(todo);
    }

    @Test
    @DisplayName("InvalidatedItem：带 supersededBy 与不带各验证一次，type=INVALIDATED")
    void invalidated_item_round_trip() {
        InvalidatedItem superseded = new InvalidatedItem("i-1", "task-1", "SUPERSEDED",
                "f-3", CREATED, "tool:query_sales", 8);
        InvalidatedItem expired = new InvalidatedItem("i-2", "task-1", "EXPIRED",
                null, CREATED, "compiler", 9);

        assertThat(converter.toEntity(superseded).getType()).isEqualTo("INVALIDATED");
        assertThat(converter.toDomain(converter.toEntity(superseded))).isEqualTo(superseded);
        assertThat(converter.toDomain(converter.toEntity(expired))).isEqualTo(expired);
    }

    @Test
    @DisplayName("payload 缺必填字段时快速失败，而不是静默构造出脏 record")
    void missing_required_payload_field_fails_fast() {
        StateItemEntity corrupted = new StateItemEntity(
                "x-1", "task-1", "CONSTRAINT", "{}", "user", 1, CREATED);

        assertThatThrownBy(() -> converter.toDomain(corrupted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("text");
    }

    @Test
    @DisplayName("未知 type 拒绝反序列化")
    void unknown_type_fails_fast() {
        StateItemEntity bogus = new StateItemEntity(
                "x-2", "task-1", "SOMETHING_NEW", "{}", "user", 1, CREATED);

        assertThatThrownBy(() -> converter.toDomain(bogus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SOMETHING_NEW");
    }
}
