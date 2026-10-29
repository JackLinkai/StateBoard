package io.asc.application;

import io.asc.core.StateCardCompiler;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 状态卡查询：从 storage 取数，交给 core 的 {@link StateCardCompiler} 编译。
 *
 * <p>这里只做编排（校验任务存在 → 取全量状态项 → 编译），
 * 所有规则（TTL 过滤、冲突消解、分组、裁剪）都在编译器里。
 */
@Service
public class StateCardQueryService {

    private final TaskStore taskStore;
    private final StateItemStore stateItemStore;
    private final StateCardCompiler compiler;

    public StateCardQueryService(TaskStore taskStore,
                                 StateItemStore stateItemStore,
                                 StateCardCompiler compiler) {
        this.taskStore = taskStore;
        this.stateItemStore = stateItemStore;
        this.compiler = compiler;
    }

    /**
     * 获取某任务的当前状态卡。
     *
     * @throws TaskNotFoundException 任务不存在时
     */
    @Transactional(readOnly = true)
    public StateCard getStateCard(String taskId) {
        taskStore.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        List<StateItem> items = stateItemStore.findByTaskId(taskId);
        return compiler.compile(taskId, items, Instant.now());
    }
}
