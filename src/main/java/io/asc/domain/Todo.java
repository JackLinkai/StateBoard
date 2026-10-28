package io.asc.domain;

import java.time.Instant;

/**
 * 待办：下一步依赖的未完成事项。
 *
 * <p>为什么需要 status 而不是直接删除：
 * 回放时要能看到“这条待办曾经存在过、后来被取消”，
 * 而不是“从来没出现过”。状态迁移比删除更有审计价值。
 */
public record Todo(

        /* 状态唯一标识。待办状态流转（OPEN → DONE/CANCELLED）时按 id 引用；回放和审计时能定位到具体某一条。 */
        String id,

        /* 属于哪个任务。ASC 支持多任务并行、跨任务恢复，所有状态必须能按任务隔离。 */
        String taskId,

        /* 待办内容的自然语言描述，如“杭州数据待查”“库存异常待确认”。直接进状态卡给 Agent 看，提示下一步还有哪些事没做。 */
        String text,

        /* 待办状态，取值见 TodoStatus 枚举。只有 OPEN 会进状态卡的 openTodos，DONE/CANCELLED 由编译层过滤。 */
        TodoStatus status,

        /* 创建时间。用途：排序（裁剪时优先保留新的）、TTL 计算、审计。 */
        Instant createdAt,

        /* 来源，约定值：user（用户输入）、tool:xxx（工具返回）、model（模型声明）、human（人工审批）。待办是谁提出的，影响跨会话恢复时的优先级和可信度。 */
        String source,

        /* 写入时的任务版本号，每写一次状态任务版本 +1。用途：乐观锁（防并发写冲突）、回放（按版本号取历史状态卡）。 */
        long version
) implements StateItem {

    /**
     * 待办状态：OPEN 待处理 / DONE 已完成 / CANCELLED 已取消
     * 只有 OPEN 会出现在状态卡的 openTodos 里。
     */
    public enum TodoStatus {

        /**
         * 还没做，会注入给模型。
         */
        OPEN,

        /**
         * 做完了，不再注入。
         */
        DONE,

        /**
         * 不做了，不再注入，但保留记录。
         */
        CANCELLED
    }
}