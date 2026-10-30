package io.asc.core.conflict;

/**
 * REJECT 策略下检测到同 key 事实冲突：
 * 写入侧触发时映射为 409 CONFLICT_DETECTED，编译侧兜底触发时同理。
 */
public class ConflictDetectedException extends RuntimeException {

    private final String taskId;
    private final String factKey;

    public ConflictDetectedException(String taskId, String factKey) {
        super("conflicting facts for key \"" + factKey + "\" in task " + taskId
                + " under REJECT strategy; resolve the conflict (overwrite or delete) before writing");
        this.taskId = taskId;
        this.factKey = factKey;
    }

    public String taskId() {
        return taskId;
    }

    public String factKey() {
        return factKey;
    }
}
