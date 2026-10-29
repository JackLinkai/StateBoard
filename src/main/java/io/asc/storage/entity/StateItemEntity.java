package io.asc.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * state_item 表的 JPA 实体，对应 domain.StateItem 四类状态。
 *
 * <p>四类状态共用一张表的设计：
 * <ul>
 *   <li>type 列区分 CONSTRAINT | FACT | TODO | INVALIDATED，取值来自 converter 的约定；</li>
 *   <li>各类型自己的字段（Fact.key/value/expiresAt、Constraint.text/level 等）
 *       序列化成 JSON 存进 payload 列，公共字段（id、task_id、source、version、created_at）
 *       单独成列，方便 SQL 检索和索引；</li>
 *   <li>payload 用 @JdbcTypeCode(SqlTypes.JSON) 映射 PostgreSQL 的 jsonb，
 *       序列化/反序列化由 StateItemConverter 负责，entity 只持有 JSON 字符串。</li>
 * </ul>
 */
@Entity
@Table(name = "state_item")
@Getter
@Setter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor
public class StateItemEntity {

    /** 状态唯一标识，主键，对应 state_item.id。 */
    @Id
    @Column(name = "id")
    private String id;

    /** 属于哪个任务，外键 task(task_id)。 */
    @Column(name = "task_id", nullable = false)
    private String taskId;

    /** 状态类型：CONSTRAINT | FACT | TODO | INVALIDATED。 */
    @Column(name = "type", nullable = false)
    private String type;

    /** 各类型自有字段序列化后的 JSON，映射 jsonb 列。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** 来源：user | tool:xxx | model | human。 */
    @Column(name = "source", nullable = false)
    private String source;

    /** 写入时的任务版本号。 */
    @Column(name = "version", nullable = false)
    private long version;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
