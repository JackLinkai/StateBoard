package io.asc.application;

import io.asc.domain.Budget;
import io.asc.domain.CardView;
import io.asc.domain.Constraint;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;

import java.time.Instant;
import java.util.List;

/**
 * 任务编排的用例接口：web / MCP 等入口层只看这里，不直接摸 storage。
 *
 * <p>application 层只做编排（查任务 → 存状态 → 更新任务），
 * 不包含业务规则——约束校验、冲突消解、TTL 判定都在 domain 和 core 层。
 *
 * <p><b>多租户（v0.6）</b>：所有方法带 tenantId 参数（入口层从 X-ASC-Tenant
 * 头解析，缺省 "default"）。跨租户访问一律按任务不存在处理——
 * 抛 {@link TaskNotFoundException}，不泄露其他租户的任务存在性。
 *
 * <p>写入类方法（addConstraint / recordFact / recordTodo / recordEvent）的共同语义：
 * <ol>
 *   <li>查 Task 是否存在且属于该租户，否则抛 {@link TaskNotFoundException}；</li>
 *   <li>调 {@link StateItemStore#save} 落库；</li>
 *   <li>任务版本号 +1、刷新 updatedAt。</li>
 * </ol>
 * 写入的状态项会以路径上的 taskId 为准归一化，缺 id / createdAt 时自动补齐，
 * version 统一由服务端分配（写入时的任务版本），调用方传什么都会被覆盖。
 */
public interface TaskApplicationService {

    /**
     * 创建任务，初始状态 ACTIVE、版本号 0，归属 tenantId。
     *
     * @return 创建后的任务（domain 视图）
     */
    Task createTask(String taskId, String description, String tenantId);

    /**
     * 某租户的任务列表，最近更新的排前面（面板 / 运维视角）。
     */
    List<Task> listTasks(String tenantId);

    /**
     * 取单个任务（校验租户）。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    Task getTask(String taskId, String tenantId);

    /**
     * 写入一条约束，level 为 null 时按 HARD 处理，source 缺省为 user。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    void addConstraint(String taskId, String tenantId, String text, Constraint.ConstraintLevel level);

    /**
     * 写入一条事实（通常是工具返回），source 缺省为 tool。
     * expiresAt 为 null 表示永不过期。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    void recordFact(String taskId, String tenantId, String key, String value,
                    Instant expiresAt, String source);

    /**
     * 写入一条待办，初始状态 OPEN，source 缺省为 user。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    void recordTodo(String taskId, String tenantId, String text, String source);

    /**
     * 待办状态流转（OPEN → DONE / CANCELLED，也允许改回 OPEN）：
     * 按 id 就地更新该待办的 status，其余字段原样保留，
     * 任务版本 +1——这是一次正常的状态写入，回放与缓存语义与其他写入一致。
     *
     * <p>编译器只把 OPEN 待办放进状态卡，DONE / CANCELLED 从此不再注入；
     * 原始记录保留在库里，回放历史版本时仍能看到它当时是 OPEN。
     *
     * @throws TaskNotFoundException 任务（或不属于该租户）、待办不存在时
     */
    void updateTodoStatus(String taskId, String tenantId, String todoId, Todo.TodoStatus status);

    /**
     * 记录一条通用状态事件：四类 StateItem 都能走这个入口，
     * 是 addConstraint / recordFact / recordTodo 的底层编排方法。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    void recordEvent(String taskId, StateItem item);

    /**
     * 同 {@link #recordEvent(String, StateItem)}，显式指定租户（校验归属）。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    void recordEvent(String taskId, StateItem item, String tenantId);

    /**
     * 获取某任务的当前状态卡。
     *
     * <p>裁剪分两步：<b>view 决定格子可见性</b>（权限维度，null 按 FULL），
     * <b>budget 决定 token 上限</b>（容量维度，null 用默认预算）——
     * 先 view 裁格，再 budget 裁条数，不可见的格子不占预算。
     * 全量卡会持久化到 state_card_version，作为回放与审计的正式版本。
     *
     * @throws TaskNotFoundException 任务不存在或不属于该租户时
     */
    StateCard getStateCard(String taskId, String tenantId, Budget budget, CardView view);
}
