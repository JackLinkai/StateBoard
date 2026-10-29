package io.asc.application;

/**
 * 请求的历史状态卡不存在：要么该版本从未持久化过（持久化功能上线前的版本），
 * 要么任务压根没有任何已持久化的状态卡。
 */
public class CardNotFoundException extends RuntimeException {

    public CardNotFoundException(String taskId, Long version) {
        super("no state card persisted for task " + taskId
                + (version != null ? " at version " + version : " (latest)"));
    }
}
