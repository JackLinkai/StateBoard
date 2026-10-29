package io.asc.web.error;

import java.time.Instant;

/**
 * 统一错误响应体。
 * code 给程序判断，message 给人看，traceId 用于日志关联。
 */
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message, String traceId) {
        return new ErrorResponse(code, message, traceId, Instant.now());
    }
}