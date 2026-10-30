package io.asc.client;

import io.asc.domain.Budget;
import io.asc.domain.CardView;
import io.asc.domain.StateCard;
import io.asc.domain.Task;

import java.time.Instant;
import java.util.List;

/**
 * ASC Java SDK 接口：见 {@link AscHttpClient}。
 * 面向接口编程，测试可替换为 mock 或 fake。
 */
public interface AscClient {

    Task createTask(String taskId, String description);

    Task restoreTask(String newTaskId, String restoreFrom, String description);

    /** 全量任务列表，最近更新的排前面。 */
    List<Task> listTasks();

    /** 取单个任务；任务不存在抛 AscApiException(TASK_NOT_FOUND)。 */
    Task getTask(String taskId);

    /** 关闭任务（幂等）：置 CLOSED，快照与回放保留。 */
    Task closeTask(String taskId);

    /** level：HARD / SOFT，null 走服务端默认 HARD。 */
    void addConstraint(String taskId, String text, String level);

    /** expiresAt null 表示永不过期；source 建议 tool:xxx。 */
    void recordFact(String taskId, String key, String value, Instant expiresAt, String source);

    void recordTodo(String taskId, String text, String source);

    /** 待办状态流转：status 取 OPEN / DONE / CANCELLED。 */
    void updateTodo(String taskId, String todoId, String status);

    StateCard getStateCard(String taskId, Budget budget);

    /**
     * 同 {@link #getStateCard(String, Budget)}，显式指定权限视图
     * （full / lean / audit）：view 决定格子可见性，budget 决定 token 上限。
     */
    StateCard getStateCard(String taskId, Budget budget, CardView view);

    StateCard snapshot(String taskId);

    /** version null 取最新持久化的一版。 */
    StateCard replay(String taskId, Long version);

    StateCard handoff(String taskId, String toAgent);
}
