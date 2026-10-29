package io.asc.application;

import io.asc.core.StateCardCompiler;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 状态卡查询的纯单元测试：这里是薄壳（校验任务 → 取数 → 委托编译器），
 * 规则语义已由 core 层的 StateCardCompilerTest 锁定，本测试只锁编排行为。
 */
@ExtendWith(MockitoExtension.class)
class StateCardQueryServiceTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private StateItemStore stateItemStore;

    @Mock
    private StateCardCompiler compiler;

    @InjectMocks
    private StateCardQueryService service;

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    @DisplayName("任务不存在抛 TaskNotFoundException，且不取状态项")
    void missing_task_throws() {
        when(taskStore.findById("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStateCard("no-such"))
                .isInstanceOf(TaskNotFoundException.class);

        verifyNoInteractions(stateItemStore, compiler);
    }

    @Test
    @DisplayName("取全量状态项后委托编译器，返回编译结果")
    void delegates_to_compiler() {
        when(taskStore.findById("task-1")).thenReturn(Optional.of(
                new Task("task-1", "d", Task.TaskStatus.ACTIVE, NOW, NOW, 0)));
        List<StateItem> items = List.of();
        when(stateItemStore.findByTaskId("task-1")).thenReturn(items);
        StateCard card = new StateCard("task-1", 3, List.of(), List.of(), List.of(),
                List.of(), List.of(), 1, NOW);
        when(compiler.compile(eq("task-1"), eq(items), any())).thenReturn(card);

        StateCard result = service.getStateCard("task-1");

        assertThat(result).isSameAs(card);
    }
}
