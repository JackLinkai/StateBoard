package io.asc.application;

/** 任务 ID 已被占用（创建/恢复时撞上已存在的任务）。 */
public class TaskAlreadyExistsException extends RuntimeException {

    public TaskAlreadyExistsException(String taskId) {
        super("task already exists: " + taskId);
    }
}
