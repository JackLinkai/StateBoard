package io.asc.application;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.StateCard;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * application 层纯单元测试：Mockito 模拟 Store 和状态卡查询，不连数据库。
 *
 * <p>验证编排语义：任务不存在抛 TaskNotFoundException、
 * 各写入入口按服务端规则归一化（taskId/version 覆盖、缺省值补齐）、
 * 任务版本 +1 且 updatedAt 刷新、状态卡查询透传给 StateCardQueryService。
 * 持久化本身由 storage 层的 StateItemStoreTest（Testcontainers）覆盖。
 */
@ExtendWith(MockitoExtension.class)
class TaskApplicationServiceImplTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private StateItemStore stateItemStore;

    @Mock
    private StateCardQueryService stateCardQueryService;

    @Mock
    private io.asc.core.conflict.ConflictStrategyProperties conflictProperties;

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
        when(taskStore.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

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
    @DisplayName("addConstraint：level=null 时按 HARD 处理，source 固定 user")
    void add_constraint_defaults_level_to_hard() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(0)));
        when(taskStore.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addConstraint("task-1", "只允许查询最近30天数据", null);

        ArgumentCaptor<Constraint> captor = ArgumentCaptor.forClass(Constraint.class);
        verify(stateItemStore).save(captor.capture());
        Constraint written = captor.getValue();
        assertThat(written.taskId()).isEqualTo("task-1");
        assertThat(written.level()).isEqualTo(Constraint.ConstraintLevel.HARD);
        assertThat(written.source()).isEqualTo("user");
        assertThat(written.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordFact：字段透传，source 空白时缺省为 tool")
    void record_fact_defaults_source_to_tool() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(2)));
        when(taskStore.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant expiresAt = T0.plusSeconds(86400);
        service.recordFact("task-1", "shanghai_sales", "120", expiresAt, "  ");

        ArgumentCaptor<Fact> captor = ArgumentCaptor.forClass(Fact.class);
        verify(stateItemStore).save(captor.capture());
        Fact written = captor.getValue();
        assertThat(written.key()).isEqualTo("shanghai_sales");
        assertThat(written.value()).isEqualTo("120");
        assertThat(written.expiresAt()).isEqualTo(expiresAt);
        assertThat(written.source()).isEqualTo("tool");
        assertThat(written.version()).isEqualTo(3);
    }

    @Test
    @DisplayName("recordTodo：初始 OPEN，source 缺省为 user")
    void record_todo_defaults_source_to_user() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(1)));
        when(taskStore.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        service.recordTodo("task-1", "杭州数据待查", null);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(stateItemStore).save(captor.capture());
        Todo written = captor.getValue();
        assertThat(written.text()).isEqualTo("杭州数据待查");
        assertThat(written.status()).isEqualTo(Todo.TodoStatus.OPEN);
        assertThat(written.source()).isEqualTo("user");
        assertThat(written.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("updateTodoStatus：OPEN → DONE，其余字段原样保留，任务版本 +1")
    void update_todo_status_marks_done_and_bumps_version() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(3)));
        when(taskStore.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        Todo todo = new Todo("t1", "task-1", "杭州数据待查", Todo.TodoStatus.OPEN,
                T0.minusSeconds(60), "user", 2);
        when(stateItemStore.findById("t1")).thenReturn(Optional.of(todo));

        service.updateTodoStatus("task-1", "t1", Todo.TodoStatus.DONE);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(stateItemStore).save(captor.capture());
        Todo written = captor.getValue();
        assertThat(written.id()).isEqualTo("t1");
        assertThat(written.text()).isEqualTo("杭州数据待查");
        assertThat(written.status()).isEqualTo(Todo.TodoStatus.DONE);
        assertThat(written.createdAt()).isEqualTo(T0.minusSeconds(60));
        assertThat(written.source()).isEqualTo("user");
        assertThat(written.version()).isEqualTo(4);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().version()).isEqualTo(4);
    }

    @Test
    @DisplayName("updateTodoStatus：待办不存在 / 属于别的任务 / 不是待办类型 → TaskNotFoundException")
    void update_todo_status_unknown_or_mismatched_todo_throws() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(3)));
        when(stateItemStore.findById("t-x")).thenReturn(Optional.empty());
        when(stateItemStore.findById("t-other"))
                .thenReturn(Optional.of(new Todo("t-other", "task-2", "x",
                        Todo.TodoStatus.OPEN, T0, "user", 1)));
        when(stateItemStore.findById("f-not-todo"))
                .thenReturn(Optional.of(new Fact("f-not-todo", "task-1", "k", "v", T0, null, "tool", 1)));

        assertThatThrownBy(() -> service.updateTodoStatus("task-1", "t-x", Todo.TodoStatus.DONE))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.updateTodoStatus("task-1", "t-other", Todo.TodoStatus.DONE))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.updateTodoStatus("task-1", "f-not-todo", Todo.TodoStatus.DONE))
                .isInstanceOf(TaskNotFoundException.class);

        verify(stateItemStore, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("listTasks：透传给 TaskStore.findAll")
    void list_tasks_delegates_to_store() {
        when(taskStore.findAll()).thenReturn(java.util.List.of(existingTask(3)));

        assertThat(service.listTasks()).hasSize(1).first().extracting(Task::taskId).isEqualTo("task-1");
    }

    @Test
    @DisplayName("getTask：任务存在原样返回，不存在抛 TaskNotFoundException")
    void get_task() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(3)));
        when(taskStore.findById("task-x")).thenReturn(Optional.empty());

        assertThat(service.getTask("task-1").taskId()).isEqualTo("task-1");
        assertThatThrownBy(() -> service.getTask("task-x")).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("recordFact（REJECT 策略）：同 key 值不同 → ConflictDetectedException，不落库")
    void record_fact_rejects_conflicting_value() {
        when(conflictProperties.get()).thenReturn(io.asc.core.conflict.ConflictStrategy.REJECT);
        when(stateItemStore.findByTaskId("task-1")).thenReturn(java.util.List.of(
                new Fact("f1", "task-1", "sales", "100", T0, null, "tool", 1)));

        assertThatThrownBy(() -> service.recordFact("task-1", "sales", "120", null, "tool"))
                .isInstanceOf(io.asc.core.conflict.ConflictDetectedException.class);

        verify(stateItemStore, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("recordFact（REJECT 策略）：同值重写放行（幂等）；旧事实已过期不拦截")
    void record_fact_allows_same_value_and_expired_conflict() {
        when(conflictProperties.get()).thenReturn(io.asc.core.conflict.ConflictStrategy.REJECT);
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(1)));
        when(stateItemStore.findByTaskId("task-1")).thenReturn(java.util.List.of(
                new Fact("f1", "task-1", "sales", "120", T0, null, "tool", 1),
                // 同 key 另一条已过期的事实，值不同但已失效，不参与冲突判定
                new Fact("f2", "task-1", "sales", "999",
                        T0.minusSeconds(120), T0.minusSeconds(60), "tool", 2)));
        when(stateItemStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordFact("task-1", "sales", "120", null, "tool");

        verify(stateItemStore).save(any());
    }

    @Test
    @DisplayName("recordFact（LATEST_WINS 策略）：不做写入拦截，直接落库")
    void record_fact_latest_wins_no_interception() {
        when(conflictProperties.get()).thenReturn(io.asc.core.conflict.ConflictStrategy.LATEST_WINS);
        when(taskStore.findById("task-1")).thenReturn(Optional.of(existingTask(1)));
        when(stateItemStore.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordFact("task-1", "sales", "120", null, "tool");

        verify(stateItemStore).save(any());
    }

    @Test
    @DisplayName("getStateCard：透传给 StateCardQueryService，不触碰 Store")
    void get_state_card_delegates_to_query_service() {
        StateCard card = new StateCard("task-1", 7,
                java.util.List.of(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), 10, T0);
        when(stateCardQueryService.getStateCard("task-1", null)).thenReturn(card);

        StateCard result = service.getStateCard("task-1", null);

        assertThat(result).isSameAs(card);
        verifyNoInteractions(taskStore, stateItemStore);
    }
}
