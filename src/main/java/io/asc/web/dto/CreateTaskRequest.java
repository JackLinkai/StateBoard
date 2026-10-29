package io.asc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建任务请求。
 * taskId 由调用方指定，方便测试和恢复时按 ID 引用。
 * restoreFrom 可选：给出时按该任务的当前有效状态恢复出新任务（跨会话恢复）。
 */
public record CreateTaskRequest(
        @NotBlank(message = "taskId must not be blank")
        String taskId,

        String description,

        String restoreFrom
) {}