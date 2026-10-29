package io.asc.application;

/**
 * 任务不存在。后续 web 层把它映射成 404。
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("task not found: " + taskId);
    }
}
