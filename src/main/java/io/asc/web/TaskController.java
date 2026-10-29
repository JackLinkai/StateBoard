package io.asc.web;

import io.asc.application.TaskApplicationService;
import io.asc.domain.Constraint;
import io.asc.domain.StateCard;
import io.asc.domain.Task;
import io.asc.web.dto.AddConstraintRequest;
import io.asc.web.dto.CreateTaskRequest;
import io.asc.web.dto.RecordFactRequest;
import io.asc.web.dto.RecordTodoRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

    public TaskController(TaskApplicationService service) {
        this.service = service;
    }

    /** 创建任务。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Task createTask(@RequestBody @Valid CreateTaskRequest req) {
        return service.createTask(req.taskId(), req.description());
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

    /**
     * 拿状态卡。
     * 阶段 1 先忽略 budget 参数，返回全部状态，不做裁剪。
     */
    @GetMapping("/{taskId}/state-card")
    public StateCard getStateCard(@PathVariable String taskId) {
        return service.getStateCard(taskId);
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