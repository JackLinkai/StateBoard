package io.asc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 待办状态流转请求。
 * status 必填，取值 OPEN | DONE | CANCELLED（大小写不敏感）。
 */
public record UpdateTodoStatusRequest(
        @NotBlank(message = "status must not be blank")
        String status
) {}
