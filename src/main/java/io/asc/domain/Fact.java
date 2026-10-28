package io.asc.domain;

import java.time.Instant;

/**
 * 事实：工具或人工确认过的可信信息。带 key、value、可能带 TTL 的可信信息。
 *
 * <p>设计要点：
 * <ul>
 *   <li>key 是冲突消解的核心。同一个 key 出现新值时，旧值进 invalidated，
 *       新值进 verifiedFacts。没有 key，就无法判断两条事实是否在说同一件事。</li>
 *   <li>expiresAt 可空。非空表示有 TTL，到点自动失效；null 表示永不过期。</li>
 *   <li>value 先用 String，早期简化。后续可改成 JsonNode 或泛型。</li>
 * </ul>
 */
public record Fact(

        /* 状态唯一标识 */
        String id,

        /* 属于哪个任务 */
        String taskId,

        /* 事实的键。这是冲突消解的核心。同一个 key 出现新值时，旧值进 invalidated，新值进 verifiedFacts。没有 key，就无法判断“这两条事实是不是在说同一件事”。 */
        String key,

        /* 事实的值。TODO：早期简化先用String，后期用泛型 */
        String value,

        /* 创建时间 */
        Instant createdAt,

        /* 过期时间，可空。非空：到点后自动进 invalidated，reason=EXPIRED。null：永不过期 */
        Instant expiresAt,   // nullable

        /* 来源 */
        String source,

        /* 写入时的任务版本号 */
        long version

) implements StateItem {

    /**
     * 过期判断
     *
     * <p>expiresAt 为 null 时永远返回 false，表示永不过期。
     * 编译器在 TTL 阶段调用这个方法，过期项会进入 invalidated，reason=EXPIRED。
     *
     * @param now 当前时间，由调用方传入而不是内部取 Instant.now()，
     *            目的是让测试可以注入固定时间，避免依赖真实时钟。
     * @return 过期返回true
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}