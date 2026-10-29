package io.asc.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * task 表的 JPA 实体，对应 domain.Task。
 *
 * <p>为什么 domain 和 entity 要分开：domain.Task 是不可变 record，不依赖任何框架，
 * 可以被 core、application 层自由使用；这个类只在 storage 层内部存在，
 * 负责和 Hibernate、数据库打交道。两边通过 TaskConverter 互转。
 *
 * <p>status、version 存原生列（TEXT / BIGINT），枚举等值的转换交给 converter，
 * 避免 Hibernate 以 @Enumerated 序列化枚举时把顺序变更埋成坑。
 */
@Entity
@Table(name = "task")
@Getter
@Setter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor
public class TaskEntity {

    /** 任务唯一标识，主键，对应 task.task_id。 */
    @Id
    @Column(name = "task_id")
    private String taskId;

    /** 任务描述。 */
    @Column(name = "description")
    private String description;

    /** 任务状态：ACTIVE | SNAPSHOTTED | CLOSED，对应 Task.TaskStatus 的 name()。 */
    @Column(name = "status", nullable = false)
    private String status;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最后更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 任务版本号，每写入一条状态 +1，见 domain.Task.version。 */
    @Column(name = "version", nullable = false)
    private long version;
}
