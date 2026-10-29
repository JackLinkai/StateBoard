package io.asc.web;

import io.asc.application.TaskApplicationService;
import io.asc.application.TaskLifecycleService;
import io.asc.domain.Budget;
import io.asc.domain.CardVersionSummary;
import io.asc.domain.Constraint;
import io.asc.domain.StateCard;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import io.asc.web.dto.AddConstraintRequest;
import io.asc.web.dto.CreateTaskRequest;
import io.asc.web.dto.HandoffRequest;
import io.asc.web.dto.RecordFactRequest;
import io.asc.web.dto.RecordTodoRequest;
import io.asc.web.dto.UpdateTodoStatusRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务与状态的 HTTP 入口。
 *
 * <p>这一层只做三件事：接收请求、校验格式、调 application 服务。
 * 不写业务规则，不直接碰 storage。
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

    private final TaskApplicationService service;
    private final TaskLifecycleService lifecycleService;

    public TaskController(TaskApplicationService service,
                          TaskLifecycleService lifecycleService) {
        this.service = service;
        this.lifecycleService = lifecycleService;
    }

    /** 全量任务列表，最近更新的排前面（面板 / 运维视角）。 */
    @GetMapping
    public List<Task> listTasks() {
        return service.listTasks();
    }

    /** 创建任务；带 restoreFrom 时按源任务的当前有效状态恢复出新任务。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Task createTask(@RequestBody @Valid CreateTaskRequest req) {
        if (req.restoreFrom() != null && !req.restoreFrom().isBlank()) {
            return lifecycleService.restore(req.taskId(), req.restoreFrom(), req.description());
        }
        return service.createTask(req.taskId(), req.description());
    }

    /** 结束任务并生成快照：编译全量卡落库，任务转为 SNAPSHOTTED。 */
    @PostMapping("/{taskId}/snapshot")
    public StateCard snapshot(@PathVariable String taskId) {
        return lifecycleService.snapshot(taskId);
    }

    /** 版本回放：取历史上某一刻持久化的状态卡；version 缺省取最新持久化的一版。 */
    @GetMapping("/{taskId}/replay")
    public StateCard replay(@PathVariable String taskId,
                            @RequestParam(required = false) Long version) {
        return lifecycleService.replay(taskId, version);
    }

    /** 多 Agent 交接：移交给 toAgent，返回当前全量状态卡给接手方直接注入上下文。 */
    @PostMapping("/{taskId}/handoff")
    public StateCard handoff(@PathVariable String taskId,
                             @RequestBody @Valid HandoffRequest req) {
        return lifecycleService.handoff(taskId, req.toAgent());
    }

    /** 写约束。level 可空，默认 HARD。 */
    @PostMapping("/{taskId}/constraints")
    @ResponseStatus(HttpStatus.CREATED)
    public void addConstraint(@PathVariable String taskId,
                              @RequestBody @Valid AddConstraintRequest req) {
        Constraint.ConstraintLevel level = parseLevel(req.level());
        service.addConstraint(taskId, req.text(), level);
    }

    /** 写事实。 */
    @PostMapping("/{taskId}/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordFact(@PathVariable String taskId,
                           @RequestBody @Valid RecordFactRequest req) {
        String source = req.source() == null || req.source().isBlank() ? "tool" : req.source();
        service.recordFact(taskId, req.key(), req.value(), req.expiresAt(), source);
    }

    /** 写待办。 */
    @PostMapping("/{taskId}/todos")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordTodo(@PathVariable String taskId,
                           @RequestBody @Valid RecordTodoRequest req) {
        String source = req.source() == null || req.source().isBlank() ? "user" : req.source();
        service.recordTodo(taskId, req.text(), source);
    }

    /** 待办状态流转：OPEN → DONE / CANCELLED（也可改回 OPEN）。 */
    @PatchMapping("/{taskId}/todos/{todoId}")
    public void updateTodoStatus(@PathVariable String taskId,
                                 @PathVariable String todoId,
                                 @RequestBody @Valid UpdateTodoStatusRequest req) {
        service.updateTodoStatus(taskId, todoId, parseTodoStatus(req.status()));
    }

    /** 列出某任务全部已持久化的版本摘要（回放面板 / 审计用），版本号升序。 */
    @GetMapping("/{taskId}/versions")
    public List<CardVersionSummary> listVersions(@PathVariable String taskId) {
        return lifecycleService.listVersions(taskId);
    }

    /**
     * 拿状态卡。
     *
     * @param budget 预算档位（small/medium/large，大小写不敏感），
     *               缺省用配置的 asc.compiler.default-budget-tokens
     */
    @GetMapping("/{taskId}/state-card")
    public StateCard getStateCard(@PathVariable String taskId,
                                  @RequestParam(required = false) String budget) {
        return service.getStateCard(taskId, Budget.parse(budget));
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

    private Todo.TodoStatus parseTodoStatus(String status) {
        try {
            return Todo.TodoStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid status: " + status + ", expected OPEN, DONE or CANCELLED");
        }
    }
}