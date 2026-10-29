package io.asc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 写待办请求。
 */
public record RecordTodoRequest(
        @NotBlank(message = "todo text must not be blank")
        String text,

        String source
) {}