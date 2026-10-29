package io.asc.application;

import io.asc.domain.Constraint;
import io.asc.domain.StateItem;
import io.asc.domain.Task;

/**
 * 任务编排的用例接口：web / MCP 等入口层只看这里，不直接摸 storage。
 *
 * <p>application 层只做编排（查任务 → 存状态 → 更新任务），
 * 不包含业务规则——约束校验、冲突消解、TTL 判定都在 domain 和 core 层。
 *
 * <p>三个方法的共同语义：
 * <ol>
 *   <li>查 Task 是否存在，不存在抛 {@link TaskNotFoundException}；</li>
 *   <li>调 {@link StateItemStore#save} 落库；</li>
 *   <li>任务版本号 +1、刷新 updatedAt。</li>
 * </ol>
 * 写入的状态项会以路径上的 taskId 为准归一化，缺 id / createdAt 时自动补齐，
 * version 统一由服务端分配（写入时的任务版本），调用方传什么都会被覆盖。
 */
public interface TaskApplicationService {

    /**
     * 创建任务，初始状态 ACTIVE、版本号 0。
     *
     * @return 创建后的任务（domain 视图）
     */
    Task createTask(String taskId, String description);

    /**
     * 写入一条硬约束（addConstraint 是 recordEvent 的约束专用入口）。
     *
     * @throws TaskNotFoundException 任务不存在时
     */
    void addConstraint(String taskId, Constraint constraint);

    /**
     * 记录一条状态事件：工具返回（Fact）、待办（Todo）、
     * 覆盖/过期标记（InvalidatedItem）都走这个。
     *
     * @throws TaskNotFoundException 任务不存在时
     */
    void recordEvent(String taskId, StateItem item);
}
