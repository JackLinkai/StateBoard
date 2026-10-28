package io.asc.domain;

import java.time.Instant;

/**
 * 失效项：被覆盖、过期、冲突、拒绝的旧状态。
 *
 * <p>设计要点：
 * <ul>
 *   <li>不存旧值本身。旧值留在 event_log 里可查，状态卡里只放摘要，
 *       避免上下文膨胀。</li>
 *   <li>supersededBy 存新状态的 id，让模型知道“旧值被谁替代了”，
 *       而不是只看到“旧值失效了”却不知道现在该用什么。</li>
 * </ul>
 */
public record InvalidatedItem(

        /* 状态唯一标识。冲突消解时用来说明“旧事实 f1 作废、由 f3 替代”；回放和审计时能定位到具体某一条。 */
        String id,

        /* 属于哪个任务。ASC 支持多任务并行、跨任务恢复，所有状态必须能按任务隔离。 */
        String taskId,

        /* 作废原因，取值见 Reason 枚举：SUPERSEDED（被同 key 的新事实覆盖）、EXPIRED（超过 TTL）、CONFLICT（与已有事实冲突）、REJECTED（被人工或校验拒绝）。让 Agent 知道这条旧状态为什么不能再用了。 */
        String reason,

        /* 被替代时指向的新状态 id，nullable。只有 reason = SUPERSEDED 时才有值，用于追溯“旧销量 100 已被新销量 120 覆盖”这类替代关系。 */
        String supersededBy,

        /* 创建时间。用途：排序（裁剪时优先保留新的）、TTL 计算、审计。 */
        Instant createdAt,

        /* 来源，约定值：user（用户输入）、tool:xxx（工具返回）、model（模型声明）、human（人工审批）。作废项同样要能追溯“这条旧状态当初是谁写进去的”。 */
        String source,

        /* 写入时的任务版本号，每写一次状态，任务版本 +1。用途：乐观锁（防并发写冲突）、回放（按版本号取历史状态卡）。 */
        long version
) implements StateItem {

    /**
     * 作废原因的约定取值。编译层产出 InvalidatedItem 时必须归入其中一类，便于统计和按原因做策略处理。
     */
    public enum Reason {

        /**
         * 被同 key 的新事实覆盖
         */
        SUPERSEDED,

        /**
         * TTL 到期
         */
        EXPIRED,

        /**
         * 和硬约束冲突，无法自动消解，需要人工处理
         */
        CONFLICT,

        /**
         * 人工或策略拒绝写入
         */
        REJECTED
    }
}
