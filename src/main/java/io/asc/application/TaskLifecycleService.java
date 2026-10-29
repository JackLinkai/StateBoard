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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 任务生命周期用例：快照、跨会话恢复、版本回放（README v0.2 治理能力）。
 *
 * <p>三个用例共用一个铁律：<b>状态卡是唯一可信载体</b>。
 * 快照把"此刻的全量卡"固化成正式版本；恢复把源任务的活状态复制成新任务；
 * 回放取出历史上某一刻被持久化的那张卡。
 *
 * <ul>
 *   <li><b>快照</b>：编译全量卡 → 持久化到 state_card_version →
 *       任务转 SNAPSHOTTED。幂等：重复快照覆盖同版本卡；</li>
 *   <li><b>恢复</b>：新任务 ID 必须未被占用；从源任务的<b>当前</b>有效状态
 *       （编译后的硬/软约束、未过期事实、OPEN 待办）复制出状态项——
 *       ID 重新生成、版本号由新任务重新分配，失效历史不带走
 *       （新会话从干净的四格开始，旧冲突记录属于旧会话的审计）；
 *       事实的过期判定在恢复时刻重算，快照时还活着、恢复时已过期的不会被复制；</li>
 *   <li><b>回放</b>：version 给定取那一版，缺省取最新持久化版本；
 *       该版本从未持久化过则抛 {@link CardNotFoundException}。</li>
 * </ul>
 */
@Service
public class TaskLifecycleService {

    private final TaskStore taskStore;
    private final StateItemStore stateItemStore;
    private final StateCardCompiler compiler;
    private final StateCardVersionStore stateCardVersionStore;
    private final TaskApplicationService taskApplicationService;

    public TaskLifecycleService(TaskStore taskStore,
                                StateItemStore stateItemStore,
                                StateCardCompiler compiler,
                                StateCardVersionStore stateCardVersionStore,
                                TaskApplicationService taskApplicationService) {
        this.taskStore = taskStore;
        this.stateItemStore = stateItemStore;
        this.compiler = compiler;
        this.stateCardVersionStore = stateCardVersionStore;
        this.taskApplicationService = taskApplicationService;
    }

    /**
     * 结束任务并生成快照：编译全量卡落库，任务转为 SNAPSHOTTED。
     *
     * @return 固化下来的全量状态卡
     * @throws TaskNotFoundException 任务不存在时
     */
    @Transactional
    public StateCard snapshot(String taskId) {
        Task task = taskStore.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        Instant now = Instant.now();
        StateCard card = compiler.compile(taskId, stateItemStore.findByTaskId(taskId), now);
        stateCardVersionStore.save(card);
        taskStore.save(new Task(task.taskId(), task.description(),
                Task.TaskStatus.SNAPSHOTTED, task.createdAt(), now, task.version()));
        return card;
    }

    /**
     * 按源任务的当前有效状态创建新任务（跨会话恢复）。
     *
     * @param newTaskId   新任务 ID，必须未被占用
     * @param restoreFrom 源任务 ID，必须已存在
     * @param description 新任务描述，空则缺省为 "从 {restoreFrom} 恢复"
     * @return 新任务（此时活状态已复制完成）
     * @throws TaskNotFoundException      源任务不存在时
     * @throws TaskAlreadyExistsException 新任务 ID 已被占用时
     */
    @Transactional
    public Task restore(String newTaskId, String restoreFrom, String description) {
        taskStore.findById(restoreFrom)
                .orElseThrow(() -> new TaskNotFoundException(restoreFrom));
        if (taskStore.findById(newTaskId).isPresent()) {
            throw new TaskAlreadyExistsException(newTaskId);
        }

        Task created = taskApplicationService.createTask(newTaskId,
                description != null && !description.isBlank()
                        ? description
                        : "从 " + restoreFrom + " 恢复");

        StateCard live = compiler.compile(restoreFrom,
                stateItemStore.findByTaskId(restoreFrom), Instant.now());
        live.hardConstraints().forEach(c -> copyItem(newTaskId, c));
        live.softConstraints().forEach(c -> copyItem(newTaskId, c));
        live.verifiedFacts().forEach(c -> copyItem(newTaskId, c));
        live.openTodos().forEach(c -> copyItem(newTaskId, c));

        return taskStore.findById(newTaskId).orElse(created);
    }

    /**
     * 版本回放：取历史上某一刻持久化的状态卡。
     *
     * @param version 目标版本；null 取最新持久化的一版
     * @throws TaskNotFoundException      任务不存在时
     * @throws CardNotFoundException 该版本从未持久化过时
     */
    @Transactional(readOnly = true)
    public StateCard replay(String taskId, Long version) {
        taskStore.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        Optional<StateCard> card = version != null
                ? stateCardVersionStore.find(taskId, version)
                : stateCardVersionStore.findLatest(taskId);
        return card.orElseThrow(() -> new CardNotFoundException(taskId, version));
    }

    /**
     * 多 Agent 交接：把任务移交给另一个 Agent，并返回当前全量状态卡——
     * 接手方拿卡即接管，不需要翻聊天记录。
     *
     * <p>交接只是"换人"：ownerAgent 更新、handoffCount +1，任务和状态卡原样不动
     * （README 的痛点是"交接靠传整段历史"，这里的答案是"交接即拿卡"）。
     *
     * @param toAgent 接手方 Agent 标识，必填
     * @return 交接时刻的全量状态卡，给接手方直接注入上下文
     * @throws TaskNotFoundException    任务不存在时
     * @throws IllegalArgumentException toAgent 为空时
     */
    @Transactional
    public StateCard handoff(String taskId, String toAgent) {
        if (toAgent == null || toAgent.isBlank()) {
            throw new IllegalArgumentException("toAgent must not be blank");
        }
        Task task = taskStore.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        Instant now = Instant.now();
        taskStore.save(new Task(task.taskId(), task.description(), task.status(),
                task.createdAt(), now, task.version(), toAgent.trim(),
                task.handoffCount() + 1));

        StateCard card = compiler.compile(taskId, stateItemStore.findByTaskId(taskId), now);
        stateCardVersionStore.save(card);
        return card;
    }

    /**
     * 把一条活状态复制到新任务：ID 重新生成、taskId 换新、
     * createdAt / source 保留（溯源"这条信息是什么时候、谁带来的"），
     * version 由 recordEvent 按新任务的版本序列重新分配。
     */
    private void copyItem(String newTaskId, StateItem item) {
        switch (item) {
            case Constraint c -> taskApplicationService.recordEvent(newTaskId,
                    new Constraint(null, newTaskId, c.text(), c.level(),
                            c.createdAt(), c.source(), 0));
            case Fact f -> taskApplicationService.recordEvent(newTaskId,
                    new Fact(null, newTaskId, f.key(), f.value(),
                            f.createdAt(), f.expiresAt(), f.source(), 0));
            case Todo t -> taskApplicationService.recordEvent(newTaskId,
                    new Todo(null, newTaskId, t.text(), Todo.TodoStatus.OPEN,
                            t.createdAt(), t.source(), 0));
            case InvalidatedItem i -> {
                // 失效历史不跨会话携带：它描述的是旧会话内部的冲突演变
            }
        }
    }
}
