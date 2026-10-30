package io.asc.application;

import io.asc.core.conflict.ConflictDetectedException;
import io.asc.core.conflict.ConflictStrategy;
import io.asc.core.conflict.ConflictStrategyProperties;
import io.asc.domain.Budget;
import io.asc.domain.CardView;
import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
 *
 * <p>REJECT 策略下的写入拦截是本类唯一的规则例外：同 key 已存在
 * 值不同的未过期事实时，recordFact 直接抛 {@link ConflictDetectedException}
 * （409），从源头保证同一 key 只有一个值；同值重写视为幂等，放行。
 */
@Service
public class TaskApplicationServiceImpl implements TaskApplicationService {

    private final TaskStore taskStore;
    private final StateItemStore stateItemStore;
    private final StateCardQueryService stateCardQueryService;
    private final ConflictStrategyProperties conflictProperties;

    public TaskApplicationServiceImpl(TaskStore taskStore, StateItemStore stateItemStore,
                                      StateCardQueryService stateCardQueryService,
                                      ConflictStrategyProperties conflictProperties) {
        this.taskStore = taskStore;
        this.stateItemStore = stateItemStore;
        this.stateCardQueryService = stateCardQueryService;
        this.conflictProperties = conflictProperties;
    }

    @Override
    @Transactional
    public Task createTask(String taskId, String description, String tenantId) {
        Instant now = Instant.now();
        return taskStore.save(new Task(taskId, description, Task.TaskStatus.ACTIVE,
                now, now, 0, null, 0, Task.normalizeTenant(tenantId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Task> listTasks(String tenantId) {
        return taskStore.findAllInTenant(Task.normalizeTenant(tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public Task getTask(String taskId, String tenantId) {
        return requireTask(taskId, tenantId);
    }

    @Override
    @Transactional
    public void addConstraint(String taskId, String tenantId, String text,
                              Constraint.ConstraintLevel level) {
        // 约束写入与普通事件是同一条编排流程，复用 recordEvent
        recordEvent(taskId, new Constraint(null, taskId, text,
                level == null ? Constraint.ConstraintLevel.HARD : level,
                null, "user", 0), tenantId);
    }

    @Override
    @Transactional
    public void recordFact(String taskId, String tenantId, String key, String value,
                           Instant expiresAt, String source) {
        // 租户校验先于冲突检查：跨租户探测得到 404 而不是 409
        Task task = requireTask(taskId, tenantId);
        if (conflictProperties.get() == ConflictStrategy.REJECT) {
            rejectOnConflictingFact(taskId, key, value);
        }
        recordEvent(task.taskId(), new Fact(null, taskId, key, value, null, expiresAt,
                sourceOrDefault(source, "tool"), 0), tenantId);
    }

    /** REJECT 策略写入拦截：同 key 已有值不同的未过期事实 → 409；同值重写放行。 */
    private void rejectOnConflictingFact(String taskId, String key, String value) {
        Instant now = Instant.now();
        boolean conflict = stateItemStore.findByTaskId(taskId).stream()
                .filter(Fact.class::isInstance)
                .map(Fact.class::cast)
                .filter(f -> f.key().equals(key))
                .filter(f -> !f.isExpired(now))
                .anyMatch(f -> !f.value().equals(value));
        if (conflict) {
            throw new ConflictDetectedException(taskId, key);
        }
    }

    @Override
    @Transactional
    public void recordTodo(String taskId, String tenantId, String text, String source) {
        recordEvent(taskId, new Todo(null, taskId, text, Todo.TodoStatus.OPEN, null,
                sourceOrDefault(source, "user"), 0), tenantId);
    }

    @Override
    @Transactional
    public void updateTodoStatus(String taskId, String tenantId, String todoId, Todo.TodoStatus status) {
        Task task = requireTask(taskId, tenantId);
        if (!(stateItemStore.findById(todoId).orElse(null) instanceof Todo todo)
                || !todo.taskId().equals(taskId)) {
            throw new TaskNotFoundException(taskId);
        }

        long newVersion = task.version() + 1;
        stateItemStore.save(new Todo(todoId, taskId, todo.text(), status,
                todo.createdAt(), todo.source(), newVersion));
        taskStore.save(new Task(taskId, task.description(), task.status(),
                task.createdAt(), Instant.now(), newVersion,
                task.ownerAgent(), task.handoffCount(), task.tenantId()));
    }

    @Override
    @Transactional
    public StateCard getStateCard(String taskId, String tenantId, Budget budget, CardView view) {
        // 注意不能 readOnly：内层 StateCardQueryService 会把编译出的全量卡持久化到
        // state_card_version（读时固化正式版本）。外层只读事务会让 Hibernate 进入
        // FlushMode.MANUAL，静默吞掉那次 INSERT（曾经导致版本列表永远只有快照/交接那一版）。
        return stateCardQueryService.getStateCard(taskId, tenantId, budget, view);
    }

    @Override
    @Transactional
    public void recordEvent(String taskId, StateItem item) {
        recordEvent(taskId, item, Task.DEFAULT_TENANT);
    }

    @Override
    @Transactional
    public void recordEvent(String taskId, StateItem item, String tenantId) {
        Task task = requireTask(taskId, tenantId);
        long newVersion = task.version() + 1;

        StateItem normalized = normalize(item, taskId, newVersion);

        stateItemStore.save(normalized);
        taskStore.save(new Task(task.taskId(), task.description(), task.status(),
                task.createdAt(), Instant.now(), newVersion,
                task.ownerAgent(), task.handoffCount(), task.tenantId()));
    }

    /* ---------- 内部步骤 ---------- */

    /**
     * 查任务并校验租户：不存在<b>或租户不匹配</b>都抛 TaskNotFoundException——
     * 跨租户探测与其他租户的任务得到同一个 404，不泄露存在性。
     */
    private Task requireTask(String taskId, String tenantId) {
        return taskStore.findByIdInTenant(taskId, Task.normalizeTenant(tenantId))
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

    private String sourceOrDefault(String source, String fallback) {
        return source != null && !source.isBlank() ? source : fallback;
    }
}
