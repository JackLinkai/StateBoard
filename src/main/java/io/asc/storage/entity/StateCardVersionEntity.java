package io.asc.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;

/**
 * state_card_version 表的 JPA 实体，对应 domain.StateCard 的历史版本。
 *
 * <p>主键是 (task_id, version) 复合键，用 @IdClass 建模：
 * card 每编译一次版本号 +1，回放和审计按 (taskId, version) 精确取某一版状态卡。
 * card 列存 StateCard 序列化后的 JSON（jsonb），estimated_tokens 单独成列方便统计。
 */
@Entity
@Table(name = "state_card_version")
@IdClass(StateCardVersionEntity.PK.class)
@Getter
@Setter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor
public class StateCardVersionEntity {

    /** 属于哪个任务，复合键之一，外键 task(task_id)。 */
    @Id
    @Column(name = "task_id", nullable = false)
    private String taskId;

    /** 状态卡版本号，复合键之二。 */
    @Id
    @Column(name = "version", nullable = false)
    private long version;

    /** StateCard 序列化后的 JSON，映射 jsonb 列。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "card", nullable = false, columnDefinition = "jsonb")
    private String card;

    /** 该版本状态卡的估算 token 数。 */
    @Column(name = "estimated_tokens", nullable = false)
    private int estimatedTokens;

    /** 编译完成时间。 */
    @Column(name = "compiled_at", nullable = false)
    private Instant compiledAt;

    /**
     * 复合主键 (taskId, version)。JPA 要求：public、可序列化、实现 equals/hashCode。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class PK implements Serializable {

        private String taskId;

        private long version;
    }
}
