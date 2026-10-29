package io.asc.application;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 状态卡查询的纯单元测试：验证分组、过滤和版本取值规则。
 *
 * <p>阶段 1 的行为约定（也是本测试锁住的行为）：
 * 约束按 HARD/SOFT 分格、待办只保留 OPEN、事实全量返回（含已过期，
 * TTL 过滤留给阶段 2 的 core 编译器）、版本号取所有状态项的最大 version。
 */
@ExtendWith(MockitoExtension.class)
class StateCardQueryServiceTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private StateItemStore stateItemStore;

    @InjectMocks
    private StateCardQueryService service;

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    @DisplayName("任务不存在抛 TaskNotFoundException")
    void missing_task_throws() {
        when(taskStore.findById("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStateCard("no-such"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("分组：HARD/SOFT 分开、只保留 OPEN 待办、DONE 待办不进卡")
    void groups_and_filters_items() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(
                new Task("task-1", "d", Task.TaskStatus.ACTIVE, T0, T0, 5)));

        Fact fact1 = new Fact("f1", "task-1", "k1", "v1", T0, null, "tool", 1);
        Fact fact2 = new Fact("f2", "task-1", "k2", "v2", T0.plusSeconds(1), null, "tool", 2);
        Constraint hard = new Constraint("c1", "task-1", "硬约束", Constraint.ConstraintLevel.HARD,
                T0, "user", 3);
        Constraint soft = new Constraint("c2", "task-1", "软约束", Constraint.ConstraintLevel.SOFT,
                T0, "user", 4);
        Todo open = new Todo("t1", "task-1", "待查", Todo.TodoStatus.OPEN, T0, "model", 5);
        Todo done = new Todo("t2", "task-1", "已完成", Todo.TodoStatus.DONE, T0, "model", 6);

        when(stateItemStore.findByTaskId("task-1"))
                .thenReturn(List.of(fact1, fact2, hard, soft, open, done));

        StateCard card = service.getStateCard("task-1");

        assertThat(card.taskId()).isEqualTo("task-1");
        assertThat(card.hardConstraints()).containsExactly(hard);
        assertThat(card.softConstraints()).containsExactly(soft);
        assertThat(card.verifiedFacts()).containsExactly(fact1, fact2);
        assertThat(card.openTodos()).containsExactly(open);
        assertThat(card.version()).isEqualTo(6);
        assertThat(card.estimatedTokens()).isPositive();
        assertThat(card.compiledAt()).isNotNull();
    }

    @Test
    @DisplayName("过期事实阶段 1 仍然返回（TTL 过滤归 core 编译器），InvalidatedItem 全量进卡")
    void expired_facts_and_invalidated_are_returned_as_is() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(
                new Task("task-1", "d", Task.TaskStatus.ACTIVE, T0, T0, 3)));

        Fact expired = new Fact("f1", "task-1", "k", "v",
                T0.minusSeconds(120), T0.minusSeconds(60), "tool", 1);
        InvalidatedItem invalidated = new InvalidatedItem(
                "i1", "task-1", "SUPERSEDED", "f2", T0, "tool", 2);
        InvalidatedItem withoutSuperseded = new InvalidatedItem(
                "i2", "task-1", "EXPIRED", null, T0, "compiler", 3);

        when(stateItemStore.findByTaskId("task-1"))
                .thenReturn(List.of(expired, invalidated, withoutSuperseded));

        StateCard card = service.getStateCard("task-1");

        assertThat(card.verifiedFacts()).containsExactly(expired);
        assertThat(card.invalidated()).containsExactly(invalidated, withoutSuperseded);
        assertThat(card.hardConstraints()).isEmpty();
        assertThat(card.openTodos()).isEmpty();
    }

    @Test
    @DisplayName("任务还没有任何状态项时返回空卡，版本 0")
    void empty_task_returns_empty_card() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(
                new Task("task-1", "d", Task.TaskStatus.ACTIVE, T0, T0, 0)));
        when(stateItemStore.findByTaskId("task-1")).thenReturn(List.of());

        StateCard card = service.getStateCard("task-1");

        assertThat(card.version()).isZero();
        assertThat(card.hardConstraints()).isEmpty();
        assertThat(card.verifiedFacts()).isEmpty();
        assertThat(card.openTodos()).isEmpty();
        assertThat(card.invalidated()).isEmpty();
    }
}
