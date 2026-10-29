package io.asc.application;

import io.asc.core.StateCardCompiler;
import io.asc.domain.Budget;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.storage.HotStateCache;
import io.asc.storage.StateCardVersionStore;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 状态卡查询：从 storage 取数，交给 core 的 {@link StateCardCompiler} 编译。
 *
 * <p>流程：校验任务存在 → 取全量状态项（Redis 热缓存优先，miss 才查
 * PostgreSQL）→ 编译<b>全量卡</b>并持久化到 state_card_version（回放/审计
 * 的正式版本）→ 按预算裁剪出本次返回的视图。
 * budget 为 null 时使用配置项 asc.compiler.default-budget-tokens。
 * 这里只做编排，规则（TTL、冲突消解、裁剪）都在编译器里；
 * 缓存的是原始状态项（key 带任务版本号），编译每次都跑，TTL 判定不失真。
 */
@Service
public class StateCardQueryService {

    private final TaskStore taskStore;
    private final StateItemStore stateItemStore;
    private final StateCardCompiler compiler;
    private final StateCardVersionStore stateCardVersionStore;
    private final HotStateCache hotStateCache;
    private final int defaultBudgetTokens;

    public StateCardQueryService(TaskStore taskStore,
                                 StateItemStore stateItemStore,
                                 StateCardCompiler compiler,
                                 StateCardVersionStore stateCardVersionStore,
                                 HotStateCache hotStateCache,
                                 @Value("${asc.compiler.default-budget-tokens:1200}") int defaultBudgetTokens) {
        this.taskStore = taskStore;
        this.stateItemStore = stateItemStore;
        this.compiler = compiler;
        this.stateCardVersionStore = stateCardVersionStore;
        this.hotStateCache = hotStateCache;
        this.defaultBudgetTokens = defaultBudgetTokens;
    }

    /**
     * 获取某任务的当前状态卡（按预算裁剪后的视图；全量卡已持久化）。
     *
     * @throws TaskNotFoundException 任务不存在时
     */
    @Transactional
    public StateCard getStateCard(String taskId, Budget budget) {
        long version = taskStore.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId))
                .version();

        List<StateItem> items = hotStateCache.get(taskId, version)
                .orElseGet(() -> {
                    List<StateItem> fresh = stateItemStore.findByTaskId(taskId);
                    hotStateCache.put(taskId, version, fresh);
                    return fresh;
                });

        StateCard full = compiler.compile(taskId, items, Instant.now());
        stateCardVersionStore.save(full);

        int budgetTokens = budget != null ? budget.tokens() : defaultBudgetTokens;
        return compiler.trim(full, budgetTokens);
    }
}
