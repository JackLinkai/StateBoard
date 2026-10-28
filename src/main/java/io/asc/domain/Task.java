package io.asc.domain;

import java.time.Instant;

/**
 * 任务：一次 Agent 执行的上下文容器。
 *
 * <p>所有状态、快照、事件都挂在 taskId 下。
 * status 用于控制“还能不能写状态”：
 * ACTIVE 可写，SNAPSHOTTED 可恢复但一般不继续写，CLOSED 已关闭。
 */
public record Task(

        /* 任务唯一标识。所有状态、快照、事件都挂在它下面。 */
        String taskId,

        /* 任务描述，给人看的。比如“检查上海最近30天销量”。 */
        String description,

        /* 任务状态。 */
        TaskStatus status,

        /* 创建时间。 */
        Instant createdAt,

        /* 最后更新时间。updatedAt 在每次写状态时刷新。 */
        Instant updatedAt
) {

    /**
     * 任务状态。
     */
    public enum TaskStatus {

        /**
         * 进行中，可写状态。
         */
        ACTIVE,

        /**
         * 已生成快照，可恢复，但一般不继续写。
         */
        SNAPSHOTTED,
        
        /**
         * 已关闭。
         */
        CLOSED
    }
}