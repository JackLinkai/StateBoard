package io.asc.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 写事实请求。
 * expiresAt 可空，null 表示永不过期。
 * source 可空，默认 tool；工具返回时建议传 tool:xxx。
 */
public record RecordFactRequest(
        @NotBlank(message = "fact key must not be blank")
        String key,

        @NotBlank(message = "fact value must not be blank")
        String value,

        Instant expiresAt,

        String source
) {}