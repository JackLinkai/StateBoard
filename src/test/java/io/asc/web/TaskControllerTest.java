package io.asc.web;

import io.asc.application.TaskApplicationService;
import io.asc.application.TaskNotFoundException;
import io.asc.domain.Budget;
import io.asc.domain.Constraint;
import io.asc.domain.StateCard;
import io.asc.domain.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * web 层切片测试：只装配 TaskController + 全局异常处理，service 用 Mockito 替身。
 *
 * <p>验证 HTTP 契约：状态码（201/200/400/404）、错误码结构、
 * DTO 校验、默认值透传。业务编排逻辑由 application 层单测覆盖。
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskApplicationService service;

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    @DisplayName("POST /v1/tasks：201，返回 domain Task 的 JSON")
    void create_task_returns_201() throws Exception {
        when(service.createTask("task-1", "check sales")).thenReturn(
                new Task("task-1", "check sales", Task.TaskStatus.ACTIVE, NOW, NOW, 0));

        mockMvc.perform(post("/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"task-1\",\"description\":\"check sales\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("POST /{id}/constraints：201，level 传 HARD 透传给 service")
    void add_constraint_returns_201() throws Exception {
        mockMvc.perform(post("/v1/tasks/task-1/constraints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"only last 30 days\",\"level\":\"HARD\"}"))
                .andExpect(status().isCreated());

        verify(service).addConstraint(eq("task-1"), eq("only last 30 days"),
                eq(Constraint.ConstraintLevel.HARD));
    }

    @Test
    @DisplayName("POST /{id}/constraints：text 空白 → 400 VALIDATION_FAILED")
    void add_constraint_blank_text_returns_400() throws Exception {
        mockMvc.perform(post("/v1/tasks/task-1/constraints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /{id}/constraints：level 非法 → 400 INVALID_ARGUMENT")
    void add_constraint_invalid_level_returns_400() throws Exception {
        mockMvc.perform(post("/v1/tasks/task-1/constraints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\",\"level\":\"WRONG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("任务不存在 → 404 TASK_NOT_FOUND")
    void missing_task_returns_404() throws Exception {
        Mockito.doThrow(new TaskNotFoundException("task-x"))
                .when(service).addConstraint(eq("task-x"), eq("t"), eq(Constraint.ConstraintLevel.HARD));

        mockMvc.perform(post("/v1/tasks/task-x/constraints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"t\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /{id}/facts：缺省字段透传（source 默认 tool，expiresAt 为 null）")
    void record_fact_defaults() throws Exception {
        mockMvc.perform(post("/v1/tasks/task-1/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"k\",\"value\":\"v\"}"))
                .andExpect(status().isCreated());

        verify(service).recordFact(eq("task-1"), eq("k"), eq("v"), isNull(), eq("tool"));
    }

    @Test
    @DisplayName("POST /{id}/facts：缺 key → 400 VALIDATION_FAILED")
    void record_fact_missing_key_returns_400() throws Exception {
        mockMvc.perform(post("/v1/tasks/task-1/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"v\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /{id}/todos：201")
    void record_todo_returns_201() throws Exception {
        mockMvc.perform(post("/v1/tasks/task-1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hangzhou pending\"}"))
                .andExpect(status().isCreated());

        verify(service).recordTodo(eq("task-1"), eq("hangzhou pending"), eq("user"));
    }

    @Test
    @DisplayName("GET /{id}/state-card：200，返回状态卡 JSON")
    void get_state_card_returns_200() throws Exception {
        when(service.getStateCard(eq("task-1"), isNull())).thenReturn(new StateCard(
                "task-1", 7, List.of(), List.of(), List.of(), List.of(), List.of(), 10, NOW));

        mockMvc.perform(get("/v1/tasks/task-1/state-card"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.hardConstraints").isArray())
                .andExpect(jsonPath("$.estimatedTokens").value(10));
    }

    @Test
    @DisplayName("GET /{id}/state-card?budget=small：档位解析为 Budget.SMALL 透传")
    void get_state_card_with_budget() throws Exception {
        when(service.getStateCard(eq("task-1"), eq(Budget.SMALL))).thenReturn(new StateCard(
                "task-1", 7, List.of(), List.of(), List.of(), List.of(), List.of(), 10, NOW));

        mockMvc.perform(get("/v1/tasks/task-1/state-card").param("budget", "small"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-1"));
    }

    @Test
    @DisplayName("GET /{id}/state-card?budget=huge：非法档位 → 400 INVALID_ARGUMENT")
    void get_state_card_with_unknown_budget_returns_400() throws Exception {
        mockMvc.perform(get("/v1/tasks/task-1/state-card").param("budget", "huge"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
