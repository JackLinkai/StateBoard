package io.asc.domain;

import java.time.Instant;

/**
 * 状态项：ASC 中所有运行时状态的任务中某一条状态，是四类之一的父类型：
 * 1. Constraint	    不可覆盖或可覆盖的任务边界。
 * 2. Fact	            带 key、value、可能带 TTL 的可信信息。
 * 3. Todo	            下一步依赖的未完成事项。
 * 4. InvalidatedItem   被覆盖、过期、冲突、拒绝的旧状态。
 *
 * <p>设计要点：
 * <ul>
 *   <li>用 sealed interface 把四类状态封闭起来。新增状态类型必须显式加进 permits，
 *       编译器会强制所有 switch 处理新类型，避免漏改。</li>
 *   <li>五个公共字段是治理和审计的骨架：id 用于溯源，taskId 用于隔离，
 *       createdAt 用于排序和 TTL，source 用于判断可信度，version 用于乐观锁和回放。</li>
 *   <li>这个接口不依赖 Spring、JPA、Jackson，保证领域模型可以被单独测试和复用。</li>
 * </ul>
 */
public sealed interface StateItem
        permits Constraint, Fact, Todo, InvalidatedItem {

    /**
     * 状态唯一标识，作用有两个：一是冲突消解时能说“旧事实 f1 被新事实 f3 替代”，二是回放和审计时能定位到具体某一条。
     */
    String id();

    /**
     * 属于哪个任务，ASC 要支持多任务并行、跨任务恢复，所有状态必须能按任务隔离。
     */
    String taskId();

    /**
     * 创建时间。用途：排序（裁剪时优先保留新的）、TTL 计算、审计。
     */
    Instant createdAt();

    /**
     * 来源，约定值：user（用户输入）、tool:xxx（某个工具返回）、model（模型自己声明的）、human（人工审批写入的），
     * 判断可信度，出问题时能追溯“这条约束是用户说的还是模型猜的”。模型猜的约束可信度低于用户说的。
     */
    String source();

    /**
     * 写入时的任务版本号，每写一次状态，任务版本 +1。用途：乐观锁（防并发写冲突）、回放（按版本号取历史状态卡）。
     */
    long version();
}