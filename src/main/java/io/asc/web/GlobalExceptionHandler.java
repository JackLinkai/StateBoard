package io.asc.web;

import io.asc.application.CardNotFoundException;
import io.asc.application.TaskAlreadyExistsException;
import io.asc.application.TaskNotFoundException;
import io.asc.web.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

/**
 * 全局异常处理：把异常统一转成 ErrorResponse。
 *
 * <p>处理三类：
 * 1. @RequestBody 上的 @Valid 校验失败 → 400
 * 2. @PathVariable / @RequestParam 校验失败 → 400
 * 3. 业务异常 → 404 / 400
 * 4. 兜底 → 500
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** @RequestBody 上的 @Valid 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("validation failed");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    /** @PathVariable / @RequestParam 上的校验失败（Spring 6.1+）。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request parameter validation failed");
    }

    /** @RequestParam 类型转换失败，比如 budget 传了不存在的档位名。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                "invalid value for parameter '" + ex.getName() + "': " + ex.getValue());
    }

    /** 任务不存在。 */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(TaskNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", ex.getMessage());
    }

    /** 任务 ID 已被占用（创建/恢复冲突）。 */
    @ExceptionHandler(TaskAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTaskAlreadyExists(TaskAlreadyExistsException ex) {
        return build(HttpStatus.CONFLICT, "TASK_ALREADY_EXISTS", ex.getMessage());
    }

    /** 请求的历史状态卡不存在（版本未持久化或任务没有卡）。 */
    @ExceptionHandler(CardNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCardNotFound(CardNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", ex.getMessage());
    }

    /** 领域模型抛出的非法参数，比如空白 text。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage());
    }

    /** 兜底：不把堆栈暴露给调用方，但日志里留全量。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("unexpected error, traceId={}", traceId, ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error", traceId);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return build(status, code, message, UUID.randomUUID().toString());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message, String traceId) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, message, traceId));
    }
}