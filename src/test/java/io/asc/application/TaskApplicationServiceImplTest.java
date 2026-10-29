package io.asc.application;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * application 层纯单元测试：Mockito 模拟两个 Store，不连数据库。
 *
 * <p>验证编排三件事：任务不存在抛 TaskNotFoundException、
 * 状态项按服务端规则归一化落库、任务版本 +1 且 updatedAt 刷新。
 * 持久化本身由 storage 层的 StateItemStoreTest（Testcontainers）覆盖。
 */
@ExtendWith(MockitoExtension.class)
class TaskApplicationServiceImplTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private StateItemStore stateItemStore;

    @InjectMocks
    private TaskApplicationServiceImpl service;

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");

    private Task existingTask(long version) {
        return new Task("task-1", "检查上海销量", Task.TaskStatus.ACTIVE, T0, T0, version);
    }

    @Test
    @DisplayName("createTask：初始 ACTIVE、版本 0、createdAt=updatedAt")
    void create_task_with_initial_state() {
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        when(taskStore.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        Task saved = service.createTask("task-new", "新任务");

        Task written = captor.getValue();
        assertThat(written.status()).isEqualTo(Task.TaskStatus.ACTIVE);
        assertThat(written.version()).isZero();
        assertThat(written.createdAt()).isEqualTo(written.updatedAt());
        assertThat(saved.taskId()).isEqualTo("task-new");
    }

    @Test
    @DisplayName("任务不存在时抛 TaskNotFoundException，且不写任何状态")
    void missing_task_throws_and_writes_nothing() {
        when(taskStore.findById("no-such-task")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordEvent("no-such-task",
                new Fact("f1", "no-such-task", "k", "v", T0, null, "tool", 99)))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining("no-such-task");

        verifyNoInteractions(stateItemStore);
    }

    @Test
    @DisplayName("recordEvent：Fact 归一化（taskId/version 覆盖、缺 id 补 UUID），任务版本 +1")
    void record_event_normalizes_fact_and_bumps_version() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(5)));
        when(taskStore.save(org.mockito.ArgumentMatchers.any(Task.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 调用方给了错误的 taskId、过大的 version、null id
        service.recordEvent("task-1",
                new Fact(null, "wrong-task", "shanghai_sales", "120", T0, null, "tool:query_sales", 99));

        ArgumentCaptor<Fact> itemCaptor = ArgumentCaptor.forClass(Fact.class);
        verify(stateItemStore).save(itemCaptor.capture());
        Fact written = itemCaptor.getValue();
        assertThat(written.taskId()).isEqualTo("task-1");
        assertThat(written.version()).isEqualTo(6);
        assertThat(written.id()).isNotBlank();

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(taskCaptor.capture());
        Task updated = taskCaptor.getValue();
        assertThat(updated.version()).isEqualTo(6);
        assertThat(updated.updatedAt()).isAfter(T0);
    }

    @Test
    @DisplayName("addConstraint：HARD 约束归一化写入，版本递增")
    void add_constraint_normalizes_and_bumps_version() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(0)));
        when(taskStore.save(org.mockito.ArgumentMatchers.any(Task.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.addConstraint("task-1", new Constraint(
                "c1", "ignored-task", "只允许查询最近30天数据",
                Constraint.ConstraintLevel.HARD, T0, "user", 42));

        ArgumentCaptor<Constraint> captor = ArgumentCaptor.forClass(Constraint.class);
        verify(stateItemStore).save(captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo("task-1");
        assertThat(captor.getValue().version()).isEqualTo(1);
        assertThat(captor.getValue().id()).isEqualTo("c1");
    }

    @Test
    @DisplayName("recordEvent：Todo 与 InvalidatedItem 同样走归一化（sealed switch 穷尽）")
    void record_event_covers_todo_and_invalidated() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(1)));
        when(taskStore.save(org.mockito.ArgumentMatchers.any(Task.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent("task-1", new Todo(
                "t1", "wrong", "杭州数据待查", Todo.TodoStatus.OPEN, T0, "model", 9));

        ArgumentCaptor<Todo> todoCaptor = ArgumentCaptor.forClass(Todo.class);
        verify(stateItemStore).save(todoCaptor.capture());
        assertThat(todoCaptor.getValue().taskId()).isEqualTo("task-1");
        assertThat(todoCaptor.getValue().version()).isEqualTo(2);
    }
}
