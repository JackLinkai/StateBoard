package io.asc.domain;

import java.time.Instant;

/**
 * 状态卡版本摘要：回放面板 / 审计列表用的轻量视图。
 *
 * <p>列版本时不反序列化整张卡（卡 JSON 可能很大），
 * 只取 state_card_version 表里单独成列的三个字段。
 *
 * @param taskId          所属任务
 * @param version         版本号（写入时的任务版本）
 * @param estimatedTokens 该版本全量卡的 token 估算
 * @param compiledAt      编译（持久化）时刻
 */
public record CardVersionSummary(
        String taskId,
        long version,
        int estimatedTokens,
        Instant compiledAt
) {
}
