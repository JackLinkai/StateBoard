package io.asc.client;

import io.asc.domain.Budget;
import io.asc.domain.StateCard;
import io.asc.domain.Task;

import java.time.Instant;

/**
 * ASC Java SDK 接口：见 {@link AscHttpClient}。
 * 面向接口编程，测试可替换为 mock 或 fake。
 */
public interface AscClient {

    Task createTask(String taskId, String description);

    Task restoreTask(String newTaskId, String restoreFrom, String description);

    /** level：HARD / SOFT，null 走服务端默认 HARD。 */
    void addConstraint(String taskId, String text, String level);

    /** expiresAt null 表示永不过期；source 建议 tool:xxx。 */
    void recordFact(String taskId, String key, String value, Instant expiresAt, String source);

    void recordTodo(String taskId, String text, String source);

    StateCard getStateCard(String taskId, Budget budget);

    StateCard snapshot(String taskId);

    /** version null 取最新持久化的一版。 */
    StateCard replay(String taskId, Long version);

    StateCard handoff(String taskId, String toAgent);
}
