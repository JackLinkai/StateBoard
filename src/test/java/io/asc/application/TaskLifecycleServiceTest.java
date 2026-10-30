package io.asc.application;

import io.asc.core.StateCardCompiler;
import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import io.asc.storage.StateCardVersionStore;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务生命周期用例的纯单元测试：快照 / 恢复 / 回放 / 关闭 / 交接。
 *
 * <p>锁定的编排语义：
 * 快照 = 编译 + 落库 + 任务转 SNAPSHOTTED；
 * 恢复 = 只复制活状态（失效历史不带走）、新 ID 由 recordEvent 重新生成、
 * createdAt/source 保留溯源、新任务继承源任务租户；
 * 回放 = 显式版本走 find、缺省走 findLatest，取不到抛 CardNotFoundException；
 * 租户 = 不匹配一律按任务不存在处理（TaskNotFoundException）。
 */
@ExtendWith(MockitoExtension.class)
class TaskLifecycleServiceTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private StateItemStore stateItemStore;

    @Mock
    private StateCardCompiler compiler;

    @Mock
    private StateCardVersionStore stateCardVersionStore;

    @Mock
    private TaskApplicationService taskApplicationService;

    @InjectMocks
    private TaskLifecycleService service;

    private static final Instant T0 = Instant.parse("2026-10-29T10:00:00Z");
    private static final String SRC = "task-src";
    private static final String NEW = "task-new";
    private static final String DEFAULT = Task.DEFAULT_TENANT;

    private Task task(String id, long version, Task.TaskStatus status) {
        return new Task(id, "d", status, T0, T0, version);
    }

    /* ---------- snapshot ---------- */

    @Test
    @DisplayName("snapshot：任务不存在抛 TaskNotFoundException，零副作用")
    void snapshot_missing_task_throws() {
        when(taskStore.findByIdInTenant("no-such", DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.snapshot("no-such", DEFAULT))
                .isInstanceOf(TaskNotFoundException.class);

        verifyNoInteractions(stateItemStore, compiler, stateCardVersionStore);
    }

    @Test
    @DisplayName("snapshot：跨租户访问按任务不存在处理（防探测）")
    void snapshot_cross_tenant_throws() {
        when(taskStore.findByIdInTenant(SRC, "tenant-b")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.snapshot(SRC, "tenant-b"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    @DisplayName("snapshot：编译全量卡落库，任务转 SNAPSHOTTED，返回该卡")
    void snapshot_persists_card_and_marks_task() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 5, Task.TaskStatus.ACTIVE)));
        List<StateItem> items = List.of();
        when(stateItemStore.findByTaskId(SRC)).thenReturn(items);
        StateCard card = new StateCard(SRC, 5, List.of(), List.of(), List.of(),
                List.of(), List.of(), 10, T0);
        when(compiler.compile(eq(SRC), eq(items), any())).thenReturn(card);

        StateCard result = service.snapshot(SRC, DEFAULT);

        assertThat(result).isSameAs(card);
        verify(stateCardVersionStore).save(card);
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(Task.TaskStatus.SNAPSHOTTED);
        assertThat(captor.getValue().version()).isEqualTo(5);
    }

    @Test
    @DisplayName("snapshot：交接过的任务快照后保留 ownerAgent 与 handoffCount（回归）")
    void snapshot_keeps_owner_and_handoff_count() {
        Task handed = new Task(SRC, "d", Task.TaskStatus.ACTIVE, T0, T0, 5, "agent-b", 2);
        when(taskStore.findByIdInTenant(SRC, DEFAULT)).thenReturn(Optional.of(handed));
        when(stateItemStore.findByTaskId(SRC)).thenReturn(List.of());
        when(compiler.compile(eq(SRC), eq(List.of()), any()))
                .thenReturn(new StateCard(SRC, 5, List.of(), List.of(), List.of(),
                        List.of(), List.of(), 10, T0));

        service.snapshot(SRC, DEFAULT);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(captor.capture());
        assertThat(captor.getValue().ownerAgent()).isEqualTo("agent-b");
        assertThat(captor.getValue().handoffCount()).isEqualTo(2);
    }

    /* ---------- close ---------- */

    @Test
    @DisplayName("close：置 CLOSED，version / owner 原样保留")
    void close_marks_closed_and_keeps_owner() {
        Task active = new Task(SRC, "d", Task.TaskStatus.ACTIVE, T0, T0, 5, "agent-b", 2);
        when(taskStore.findByIdInTenant(SRC, DEFAULT)).thenReturn(Optional.of(active));
        when(taskStore.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task closed = service.close(SRC, DEFAULT);

        assertThat(closed.status()).isEqualTo(Task.TaskStatus.CLOSED);
        assertThat(closed.version()).isEqualTo(5);
        assertThat(closed.ownerAgent()).isEqualTo("agent-b");
        assertThat(closed.handoffCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("close：已关闭的任务幂等返回，不再落库")
    void close_is_idempotent() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 5, Task.TaskStatus.CLOSED)));

        Task closed = service.close(SRC, DEFAULT);

        assertThat(closed.status()).isEqualTo(Task.TaskStatus.CLOSED);
        verify(taskStore, never()).save(any());
    }

    @Test
    @DisplayName("close：任务不存在抛 TaskNotFoundException")
    void close_missing_task_throws() {
        when(taskStore.findByIdInTenant("no-such", DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close("no-such", DEFAULT))
                .isInstanceOf(TaskNotFoundException.class);
    }

    /* ---------- restore ---------- */

    @Test
    @DisplayName("restore：源任务不存在抛 TaskNotFoundException")
    void restore_missing_source_throws() {
        when(taskStore.findByIdInTenant("no-src", DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(NEW, "no-src", null, DEFAULT))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining("no-src");

        verifyNoInteractions(taskApplicationService);
    }

    @Test
    @DisplayName("restore：新任务 ID 已被占用抛 TaskAlreadyExistsException，不复制任何状态")
    void restore_existing_target_throws() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 1, Task.TaskStatus.SNAPSHOTTED)));
        when(taskStore.findById(NEW)).thenReturn(Optional.of(task(NEW, 1, Task.TaskStatus.ACTIVE)));

        assertThatThrownBy(() -> service.restore(NEW, SRC, null, DEFAULT))
                .isInstanceOf(TaskAlreadyExistsException.class)
                .hasMessageContaining(NEW);

        verifyNoInteractions(taskApplicationService, compiler);
    }

    @Test
    @DisplayName("restore：只复制硬/软约束、事实、OPEN 待办；失效历史与 DONE 待办不带走")
    void restore_copies_only_live_items() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 6, Task.TaskStatus.SNAPSHOTTED)));
        when(taskStore.findById(NEW)).thenReturn(Optional.empty());
        when(taskApplicationService.createTask(eq(NEW), anyString(), eq(DEFAULT)))
                .thenReturn(task(NEW, 0, Task.TaskStatus.ACTIVE));

        List<StateItem> items = List.of();
        when(stateItemStore.findByTaskId(SRC)).thenReturn(items);
        // 编译器已把 DONE 待办和失效项过滤，卡里只有活状态 + 透传的失效记录
        StateCard card = new StateCard(SRC, 6,
                List.of(new Constraint("c1", SRC, "只查30天", Constraint.ConstraintLevel.HARD,
                        T0, "user", 1)),
                List.of(new Constraint("c2", SRC, "优先上海", Constraint.ConstraintLevel.SOFT,
                        T0, "user", 2)),
                List.of(new Fact("f1", SRC, "sales", "120", T0, null, "tool", 3)),
                List.of(new Todo("t1", SRC, "杭州待查", Todo.TodoStatus.OPEN, T0, "model", 4)),
                List.of(new InvalidatedItem("i1", SRC, "SUPERSEDED", "f0", T0, "compiler", 2)),
                50, T0);
        when(compiler.compile(eq(SRC), eq(items), any())).thenReturn(card);

        service.restore(NEW, SRC, null, DEFAULT);

        // 新任务描述缺省为"从 … 恢复"
        verify(taskApplicationService).createTask(eq(NEW), eq("从 " + SRC + " 恢复"), eq(DEFAULT));

        // 活状态复制 4 条，每条 taskId 换新、id 置空待重生成、createdAt/source 保留
        ArgumentCaptor<StateItem> captor = ArgumentCaptor.forClass(StateItem.class);
        verify(taskApplicationService, org.mockito.Mockito.times(4))
                .recordEvent(eq(NEW), captor.capture(), eq(DEFAULT));
        assertThat(captor.getAllValues())
                .allSatisfy(item -> {
                    assertThat(item.taskId()).isEqualTo(NEW);
                    assertThat(item.id()).isNull();
                    assertThat(item.createdAt()).isEqualTo(T0);
                });
        assertThat(captor.getAllValues())
                .extracting(StateItem::source)
                .containsExactly("user", "user", "tool", "model");
    }

    @Test
    @DisplayName("restore：显式 description 优先于缺省值")
    void restore_uses_explicit_description() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 0, Task.TaskStatus.ACTIVE)));
        when(taskStore.findById(NEW)).thenReturn(Optional.empty());
        when(stateItemStore.findByTaskId(SRC)).thenReturn(List.of());
        when(compiler.compile(eq(SRC), anyList(), any()))
                .thenReturn(new StateCard(SRC, 0, List.of(), List.of(), List.of(),
                        List.of(), List.of(), 1, T0));
        when(taskApplicationService.createTask(NEW, "新会话", DEFAULT))
                .thenReturn(task(NEW, 0, Task.TaskStatus.ACTIVE));

        service.restore(NEW, SRC, "新会话", DEFAULT);

        verify(taskApplicationService).createTask(NEW, "新会话", DEFAULT);
        verify(taskApplicationService, never()).recordEvent(anyString(), any(), anyString());
    }

    /* ---------- handoff ---------- */

    @Test
    @DisplayName("handoff：ownerAgent 更新为接手方、handoffCount +1，返回全量卡并落库")
    void handoff_transfers_owner_and_returns_card() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 5, Task.TaskStatus.ACTIVE)));
        List<StateItem> items = List.of();
        when(stateItemStore.findByTaskId(SRC)).thenReturn(items);
        StateCard card = new StateCard(SRC, 5, List.of(), List.of(), List.of(),
                List.of(), List.of(), 10, T0);
        when(compiler.compile(eq(SRC), eq(items), any())).thenReturn(card);

        StateCard result = service.handoff(SRC, DEFAULT, "agent-b");

        assertThat(result).isSameAs(card);
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(captor.capture());
        assertThat(captor.getValue().ownerAgent()).isEqualTo("agent-b");
        assertThat(captor.getValue().handoffCount()).isEqualTo(1);
        verify(stateCardVersionStore).save(card);
    }

    @Test
    @DisplayName("handoff：任务不存在抛 TaskNotFoundException，零副作用")
    void handoff_missing_task_throws() {
        when(taskStore.findByIdInTenant("no-such", DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handoff("no-such", DEFAULT, "agent-b"))
                .isInstanceOf(TaskNotFoundException.class);

        verifyNoInteractions(stateItemStore, compiler, stateCardVersionStore);
    }

    @Test
    @DisplayName("handoff：toAgent 空白直接拒绝，连任务都不查")
    void handoff_blank_to_agent_rejected() {
        assertThatThrownBy(() -> service.handoff(SRC, DEFAULT, "  "))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(taskStore, stateItemStore, compiler, stateCardVersionStore);
    }

    /* ---------- replay / versions ---------- */

    @Test
    @DisplayName("replay：显式版本走 store.find，取不到抛 CardNotFoundException")
    void replay_missing_version_throws() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 9, Task.TaskStatus.ACTIVE)));
        when(stateCardVersionStore.find(SRC, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay(SRC, DEFAULT, 5L))
                .isInstanceOf(CardNotFoundException.class)
                .hasMessageContaining("5");
    }

    @Test
    @DisplayName("replay：version 缺省取最新持久化的一版")
    void replay_without_version_uses_latest() {
        when(taskStore.findByIdInTenant(SRC, DEFAULT))
                .thenReturn(Optional.of(task(SRC, 9, Task.TaskStatus.ACTIVE)));
        StateCard card = new StateCard(SRC, 9, List.of(), List.of(), List.of(),
                List.of(), List.of(), 10, T0);
        when(stateCardVersionStore.findLatest(SRC)).thenReturn(Optional.of(card));

        StateCard result = service.replay(SRC, DEFAULT, null);

        assertThat(result).isSameAs(card);
        verify(stateCardVersionStore, never()).find(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("replay：任务本身不存在抛 TaskNotFoundException")
    void replay_missing_task_throws() {
        when(taskStore.findByIdInTenant("no-such", DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replay("no-such", DEFAULT, null))
                .isInstanceOf(TaskNotFoundException.class);

        verifyNoInteractions(stateCardVersionStore);
    }
}
