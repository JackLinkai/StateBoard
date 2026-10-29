package io.asc.client;

/**
 * ASC 服务端返回的业务错误（4xx/5xx，且响应体是 ErrorResponse 结构）。
 *
 * <p>调用方按 code 分支处理：TASK_NOT_FOUND / CARD_NOT_FOUND → 换任务 ID 或先建任务；
 * TASK_ALREADY_EXISTS → 换 ID；VALIDATION_FAILED / INVALID_ARGUMENT → 修正参数。
 */
public class AscApiException extends RuntimeException {

    private final int statusCode;
    private final String code;

    public AscApiException(int statusCode, String code, String message) {
        super("ASC " + statusCode + " [" + code + "]: " + message);
        this.statusCode = statusCode;
        this.code = code;
    }

    /** HTTP 状态码。 */
    public int statusCode() {
        return statusCode;
    }

    /** 业务错误码，如 TASK_NOT_FOUND、INVALID_ARGUMENT。 */
    public String code() {
        return code;
    }
}
