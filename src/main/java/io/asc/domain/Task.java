package io.asc.domain;

import java.time.Instant;

/**
 * 任务：一次 Agent 执行的上下文容器。
 *
 * <p>所有状态、快照、事件都挂在 taskId 下。
 * status 用于控制“还能不能写状态”：
 * ACTIVE 可写，SNAPSHOTTED 可恢复但一般不继续写，CLOSED 已关闭。
 * tenantId 标识任务归属的租户，实现数据隔离（v0.6）。
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
        Instant updatedAt,

        /* 任务版本号，每写入一条状态 +1，创建时为 0。
           写入状态项时同步记进该条 StateItem.version；
           是后续乐观锁（防并发写冲突）和状态卡按版本回放的依据。 */
        long version,

        /* 当前持有该任务的 Agent 标识。null 表示还没经过交接
           （单 Agent 会话或调用方没报身份）。交接只是"换人"，状态卡不变。 */
        String ownerAgent,

        /* 交接次数，每次 handoff +1。审计用：一个任务被来回交了几手。 */
        long handoffCount,

        /* 任务归属租户。请求不带 X-ASC-Tenant 头时落 "default"；
           跨租户读写一律按任务不存在处理（404），不泄露存在性。 */
        String tenantId
) {

    /** 缺省租户：不携带 X-ASC-Tenant 头的请求都落在它下面。 */
    public static final String DEFAULT_TENANT = "default";

    /** 租户归一化：null / 空白归缺省租户，其余去空白。入口层统一调用。 */
    public static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.trim();
    }

    /**
     * 便捷构造：未经过交接、归属缺省租户的任务（ownerAgent=null、handoffCount=0）。
     */
    public Task(String taskId, String description, TaskStatus status,
                Instant createdAt, Instant updatedAt, long version) {
        this(taskId, description, status, createdAt, updatedAt, version,
                null, 0, DEFAULT_TENANT);
    }

    /**
     * 便捷构造：未经过交接的任务（ownerAgent=null、handoffCount=0），指定租户。
     */
    public Task(String taskId, String description, TaskStatus status,
                Instant createdAt, Instant updatedAt, long version, String tenantId) {
        this(taskId, description, status, createdAt, updatedAt, version,
                null, 0, tenantId);
    }

    /**
     * 便捷构造：归属缺省租户、经过交接的任务（测试与内部组装用）。
     */
    public Task(String taskId, String description, TaskStatus status,
                Instant createdAt, Instant updatedAt, long version,
                String ownerAgent, long handoffCount) {
        this(taskId, description, status, createdAt, updatedAt, version,
                ownerAgent, handoffCount, DEFAULT_TENANT);
    }

    /** 归一化：null / 空白租户一律归到缺省租户。 */
    public Task {
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = DEFAULT_TENANT;
        }
    }

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