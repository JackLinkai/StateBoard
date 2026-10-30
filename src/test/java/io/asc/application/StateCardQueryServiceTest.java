package io.asc.application;

import io.asc.core.StateCardCompiler;
import io.asc.domain.Budget;
import io.asc.domain.Fact;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.storage.HotStateCache;
import io.asc.storage.StateCardVersionStore;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 状态卡查询的纯单元测试：这里是薄壳（校验任务 → 热缓存/DB 取数 → 编译 →
 * 持久化 → 裁剪），规则语义已由 core 层锁定，本测试只锁编排行为：
 * 缓存命中跳过 DB、未命中回填缓存、全量卡持久化、预算档位解析。
 */
@ExtendWith(MockitoExtension.class)
class StateCardQueryServiceTest {

    @Mock
    private TaskStore taskStore;

    @Mock
    private StateItemStore stateItemStore;

    @Mock
    private StateCardCompiler compiler;

    @Mock
    private StateCardVersionStore stateCardVersionStore;

    @Mock
    private HotStateCache hotStateCache;

    private StateCardQueryService service;

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final String DEFAULT = Task.DEFAULT_TENANT;
    private static final Task TASK = new Task("task-1", "d", Task.TaskStatus.ACTIVE, NOW, NOW, 3);

    @BeforeEach
    void setUp() {
        // 手动构造以便注入默认预算（@Value 不适用于 Mockito）
        service = new StateCardQueryService(taskStore, stateItemStore,
                compiler, stateCardVersionStore, hotStateCache, 1200);
    }

    @Test
    @DisplayName("任务不存在抛 TaskNotFoundException，零副作用")
    void missing_task_throws() {
        when(taskStore.findByIdInTenant("no-such", DEFAULT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStateCard("no-such", DEFAULT, null, null))
                .isInstanceOf(TaskNotFoundException.class);

        verifyNoInteractions(stateItemStore, compiler, stateCardVersionStore, hotStateCache);
    }

    @Test
    @DisplayName("缓存未命中：查 DB → 回填缓存 → 编译 → 全量卡落库 → 视图裁剪 → 按预算裁剪")
    void cache_miss_fetches_db_and_backfills() {
        when(taskStore.findByIdInTenant("task-1", DEFAULT)).thenReturn(Optional.of(TASK));
        when(hotStateCache.get("task-1", 3)).thenReturn(Optional.empty());
        List<StateItem> items = List.of(new Fact("f1", "task-1", "k", "v", NOW, null, "tool", 1));
        when(stateItemStore.findByTaskId("task-1")).thenReturn(items);
        StateCard full = new StateCard("task-1", 3, List.of(), List.of(), List.of(),
                List.of(), List.of(), 800, NOW);
        when(compiler.compile(eq("task-1"), eq(items), any())).thenReturn(full);
        when(compiler.withView(full, null)).thenReturn(full);
        StateCard trimmed = new StateCard("task-1", 3, List.of(), List.of(), List.of(),
                List.of(), List.of(), 300, NOW);
        when(compiler.trim(full, Budget.SMALL.tokens())).thenReturn(trimmed);

        StateCard result = service.getStateCard("task-1", DEFAULT, Budget.SMALL, null);

        assertThat(result).isSameAs(trimmed);
        verify(hotStateCache).put("task-1", 3, items);
        verify(stateCardVersionStore).save(full);   // 持久化的是全量卡
    }

    @Test
    @DisplayName("缓存命中：跳过 DB 查询，仍然编译（TTL 判定不失真）并落库")
    void cache_hit_skips_db_but_still_compiles() {
        when(taskStore.findByIdInTenant("task-1", DEFAULT)).thenReturn(Optional.of(TASK));
        List<StateItem> cached = List.of(new Fact("f1", "task-1", "k", "v", NOW, null, "tool", 1));
        when(hotStateCache.get("task-1", 3)).thenReturn(Optional.of(cached));
        StateCard full = new StateCard("task-1", 3, List.of(), List.of(), List.of(),
                List.of(), List.of(), 100, NOW);
        when(compiler.compile(eq("task-1"), eq(cached), any())).thenReturn(full);
        when(compiler.withView(full, null)).thenReturn(full);
        when(compiler.trim(eq(full), anyInt())).thenReturn(full);

        StateCard result = service.getStateCard("task-1", DEFAULT, null, null);

        assertThat(result).isSameAs(full);
        verify(stateItemStore, never()).findByTaskId(any());
        verify(hotStateCache, never()).put(any(), org.mockito.ArgumentMatchers.anyLong(), anyList());
        verify(stateCardVersionStore).save(full);
    }

    @Test
    @DisplayName("跨租户访问：按任务不存在处理，零副作用")
    void cross_tenant_access_treated_as_missing() {
        when(taskStore.findByIdInTenant("task-1", "tenant-b")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStateCard("task-1", "tenant-b", null, null))
                .isInstanceOf(TaskNotFoundException.class);

        verifyNoInteractions(stateItemStore, compiler, stateCardVersionStore, hotStateCache);
    }
}
