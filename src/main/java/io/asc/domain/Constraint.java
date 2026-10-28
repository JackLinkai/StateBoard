package io.asc.domain;

import java.time.Instant;

/**
 * 约束：任务中不可违反或可覆盖的边界。
 *
 * <p>分两级：
 * <ul>
 *   <li>HARD：硬约束，不可覆盖，预算裁剪时永远保留。例如“无删除权限”。</li>
 *   <li>SOFT：软约束，可被新约束覆盖，预算紧张时可摘要。例如“用户偏好简洁回答”。</li>
 * </ul>
 * 编译器对两者处理策略不同，所以必须在模型层就区分，而不是靠字符串约定。
 */
public record Constraint(

        /* 状态唯一标识 */
        String id,

        /* 属于哪个任务 */
        String taskId,

        /* 约束内容 */
        String text,

        /* 约束等级 */
        ConstraintLevel level,

        /* 创建时间 */
        Instant createdAt,

        /* 来源 */
        String source,

        /* 写入时的任务版本号 */
        long version

) implements StateItem {

    /**
     * 紧凑构造器：校验业务不变量。
     *
     * <p>为什么在领域模型里校验而不是只靠 DTO 上的 @NotBlank：
     * 约束可能从 HTTP、MQ、测试、内部调用等多个入口进来，
     * 只有领域模型自己能保证“无论从哪来，text 都不能是空白”。
     * DTO 校验管请求格式，这里管业务不变量，两层各守各的边界。
     */
    public Constraint {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("constraint text must not be blank");
        }
    }

    /**
     * 约束等级：硬约束是安全边界，丢了会出事；软约束是偏好，丢了影响体验但不致命。
     * 编译器对两者处理策略不同，所以必须在模型层就区分，而不是靠字符串约定。
     */
    public enum ConstraintLevel {

        /**
         * 不可覆盖、裁剪时永远保留。比如“无删除权限”
         */
        HARD,

        /**
         * 可被新约束覆盖、预算紧张时可摘要。比如“用户偏好简洁回答”
         */
        SOFT
    }
}