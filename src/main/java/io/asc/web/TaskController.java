package io.asc.web;

import io.asc.application.TaskApplicationService;
import io.asc.application.TaskLifecycleService;
import io.asc.domain.Budget;
import io.asc.domain.CardView;
import io.asc.domain.Constraint;
import io.asc.domain.StateCard;
import io.asc.domain.Task;
import io.asc.web.dto.AddConstraintRequest;
import io.asc.web.dto.CreateTaskRequest;
import io.asc.web.dto.HandoffRequest;
import io.asc.web.dto.RecordFactRequest;
import io.asc.web.dto.RecordTodoRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务与状态的 HTTP 入口。
 *
 * <p>这一层只做三件事：接收请求、校验格式、调 application 服务。
 * 不写业务规则，不直接碰 storage。
 *
 * <p><b>多租户（v0.6）</b>：租户从 {@code X-ASC-Tenant} 请求头解析，
 * 缺省 {@code default}。跨租户访问一律 404（与任务不存在同响应，防探测）。
 * <b>权限视图</b>：GET state-card 可带 {@code view=full|lean|audit}，
 * 决定格子可见性（budget 决定 token 上限）。
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

    /** 租户请求头：多租户隔离的唯一凭据（v0.6；正式鉴权在长期规划）。 */
    static final String TENANT_HEADER = "X-ASC-Tenant";

    private final TaskApplicationService service;
    private final TaskLifecycleService lifecycleService;

    public TaskController(TaskApplicationService service,
                          TaskLifecycleService lifecycleService) {
        this.service = service;
        this.lifecycleService = lifecycleService;
    }

    /** 创建任务；带 restoreFrom 时按源任务的当前有效状态恢复出新任务（继承源任务租户）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Task createTask(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                           @RequestBody @Valid CreateTaskRequest req) {
        if (req.restoreFrom() != null && !req.restoreFrom().isBlank()) {
            return lifecycleService.restore(req.taskId(), req.restoreFrom(),
                    req.description(), tenant);
        }
        return service.createTask(req.taskId(), req.description(), tenant);
    }

    /** 某租户的任务列表，最近更新的排前面（面板 / 运维视角）。 */
    @GetMapping
    public List<Task> listTasks(@RequestHeader(value = TENANT_HEADER, required = false) String tenant) {
        return service.listTasks(tenant);
    }

    /** 取单个任务（校验租户）。 */
    @GetMapping("/{taskId}")
    public Task getTask(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                        @PathVariable String taskId) {
        return service.getTask(taskId, tenant);
    }

    /** 关闭任务（幂等）：置 CLOSED，快照与回放保留。 */
    @PostMapping("/{taskId}/close")
    public Task closeTask(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                          @PathVariable String taskId) {
        return lifecycleService.close(taskId, tenant);
    }

    /** 结束任务并生成快照：编译全量卡落库，任务转为 SNAPSHOTTED。 */
    @PostMapping("/{taskId}/snapshot")
    public StateCard snapshot(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                              @PathVariable String taskId) {
        return lifecycleService.snapshot(taskId, tenant);
    }

    /** 版本回放：取历史上某一刻持久化的状态卡；version 缺省取最新持久化的一版。 */
    @GetMapping("/{taskId}/replay")
    public StateCard replay(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                            @PathVariable String taskId,
                            @RequestParam(required = false) Long version) {
        return lifecycleService.replay(taskId, tenant, version);
    }

    /** 某任务全部已持久化的状态卡版本摘要（回放面板 / 审计）。 */
    @GetMapping("/{taskId}/versions")
    public List<io.asc.domain.CardVersionSummary> listVersions(
            @RequestHeader(value = TENANT_HEADER, required = false) String tenant,
            @PathVariable String taskId) {
        return lifecycleService.listVersions(taskId, tenant);
    }

    /** 多 Agent 交接：移交给 toAgent，返回当前全量状态卡给接手方直接注入上下文。 */
    @PostMapping("/{taskId}/handoff")
    public StateCard handoff(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                             @PathVariable String taskId,
                             @RequestBody @Valid HandoffRequest req) {
        return lifecycleService.handoff(taskId, tenant, req.toAgent());
    }

    /** 写约束。level 可空，默认 HARD。 */
    @PostMapping("/{taskId}/constraints")
    @ResponseStatus(HttpStatus.CREATED)
    public void addConstraint(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                              @PathVariable String taskId,
                              @RequestBody @Valid AddConstraintRequest req) {
        Constraint.ConstraintLevel level = parseLevel(req.level());
        service.addConstraint(taskId, tenant, req.text(), level);
    }

    /** 写事实。 */
    @PostMapping("/{taskId}/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordFact(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                           @PathVariable String taskId,
                           @RequestBody @Valid RecordFactRequest req) {
        String source = req.source() == null || req.source().isBlank() ? "tool" : req.source();
        service.recordFact(taskId, tenant, req.key(), req.value(), req.expiresAt(), source);
    }

    /** 写待办。 */
    @PostMapping("/{taskId}/todos")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordTodo(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                           @PathVariable String taskId,
                           @RequestBody @Valid RecordTodoRequest req) {
        String source = req.source() == null || req.source().isBlank() ? "user" : req.source();
        service.recordTodo(taskId, tenant, req.text(), source);
    }

    /** 待办状态流转：status 必填（OPEN / DONE / CANCELLED，大小写不敏感）。 */
    @PatchMapping("/{taskId}/todos/{todoId}")
    public void updateTodo(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                           @PathVariable String taskId,
                           @PathVariable String todoId,
                           @RequestBody @Valid io.asc.web.dto.UpdateTodoStatusRequest req) {
        service.updateTodoStatus(taskId, tenant, todoId,
                io.asc.domain.Todo.TodoStatus.valueOf(req.status().toUpperCase()));
    }

    /**
     * 拿状态卡。
     *
     * @param budget 预算档位（small/medium/large，大小写不敏感），
     *               缺省用配置的 asc.compiler.default-budget-tokens
     * @param view   权限视图（full/lean/audit，大小写不敏感），缺省 full
     */
    @GetMapping("/{taskId}/state-card")
    public StateCard getStateCard(@RequestHeader(value = TENANT_HEADER, required = false) String tenant,
                                  @PathVariable String taskId,
                                  @RequestParam(required = false) String budget,
                                  @RequestParam(required = false) String view) {
        return service.getStateCard(taskId, tenant,
                Budget.parse(budget), CardView.parse(view));
    }

    private Constraint.ConstraintLevel parseLevel(String level) {
        if (level == null || level.isBlank()) {
            return Constraint.ConstraintLevel.HARD;
        }
        try {
            return Constraint.ConstraintLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid level: " + level + ", expected HARD or SOFT");
        }
    }
}
