package io.asc.application;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * TaskApplicationService 的默认实现：只编排，不做业务规则。
 *
 * <p>流程固定三步：requireTask（不存在抛 TaskNotFoundException）→
 * StateItemStore.save → 任务版本 +1、updatedAt 刷新。写状态和更新任务在同一个事务里，
 * 不会出现“状态存了但任务版本没涨”的中间态。
 *
 * <p>归一化规则（调用方传值一律以服务端为准）：
 * <ul>
 *   <li>taskId 以路径参数为准（record 里自带的会被覆盖）；</li>
 *   <li>version = 写入时的任务版本（旧值 +1）；</li>
 *   <li>id 缺失时生成 UUID，createdAt 缺失时取当前时间；</li>
 *   <li>text/level/key/value 等业务字段原样透传，校验是 domain 的事
 *       （比如 Constraint 的空白 text 在构造 record 时就会被拒绝）。</li>
 * </ul>
 */
@Service
public class TaskApplicationServiceImpl implements TaskApplicationService {

    private final TaskStore taskStore;
    private final StateItemStore stateItemStore;

    public TaskApplicationServiceImpl(TaskStore taskStore, StateItemStore stateItemStore) {
        this.taskStore = taskStore;
        this.stateItemStore = stateItemStore;
    }

    @Override
    @Transactional
    public Task createTask(String taskId, String description) {
        Instant now = Instant.now();
        return taskStore.save(new Task(taskId, description, Task.TaskStatus.ACTIVE, now, now, 0));
    }

    @Override
    @Transactional
    public void addConstraint(String taskId, Constraint constraint) {
        // 约束写入与普通事件是同一条编排流程，复用 recordEvent
        recordEvent(taskId, constraint);
    }

    @Override
    @Transactional
    public void recordEvent(String taskId, StateItem item) {
        Task task = requireTask(taskId);
        long newVersion = task.version() + 1;

        StateItem normalized = normalize(item, taskId, newVersion);

        stateItemStore.save(normalized);
        taskStore.save(new Task(task.taskId(), task.description(), task.status(),
                task.createdAt(), Instant.now(), newVersion));
    }

    /* ---------- 内部步骤 ---------- */

    /** 查任务，不存在抛 TaskNotFoundException。 */
    private Task requireTask(String taskId) {
        return taskStore.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * 按具体类型重建状态项：强制 taskId / version，补齐 id / createdAt。
     * StateItem 是 sealed interface，switch 穷尽四类，
     * 新增状态类型时编译器会强制在这里补 case。
     */
    private StateItem normalize(StateItem item, String taskId, long newVersion) {
        String id = idOrDefault(item.id());
        Instant createdAt = createdAtOrDefault(item.createdAt());
        return switch (item) {
            case Constraint c -> new Constraint(
                    id, taskId, c.text(), c.level(), createdAt, c.source(), newVersion);
            case Fact f -> new Fact(
                    id, taskId, f.key(), f.value(), createdAt, f.expiresAt(), f.source(), newVersion);
            case Todo t -> new Todo(
                    id, taskId, t.text(), t.status(), createdAt, t.source(), newVersion);
            case InvalidatedItem i -> new InvalidatedItem(
                    id, taskId, i.reason(), i.supersededBy(), createdAt, i.source(), newVersion);
        };
    }

    private String idOrDefault(String id) {
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    private Instant createdAtOrDefault(Instant createdAt) {
        return createdAt != null ? createdAt : Instant.now();
    }
}
