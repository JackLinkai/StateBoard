package io.asc.client;

import io.asc.domain.Budget;
import io.asc.domain.StateCard;
import io.asc.domain.Task;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * ASC Java SDK：对 REST API 的薄封装，给 Spring AI / LangChain4j 等
 * Java Agent 框架在 LLM 调用前后接入 ASC 用。
 *
 * <p>典型用法：
 * <pre>{@code
 * AscClient asc = AscHttpClient.create("http://localhost:8080");
 * asc.addConstraint(taskId, "只允许查询最近30天数据", "HARD");
 * asc.recordFact(taskId, "shanghai_sales", "120", null, "tool:query_sales");
 * StateCard card = asc.getStateCard(taskId, Budget.SMALL);
 * String prompt = card.toPrompt() + "\n\n用户输入：" + userInput;
 * }</pre>
 *
 * <p>服务端返回 4xx/5xx 时抛 {@link AscApiException}（含业务错误码）。
 * 线程安全，可全局单例复用。
 */
public final class AscHttpClient implements AscClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** 工厂方法：指向 ASC 服务地址，如 http://localhost:8080。 */
    public static AscHttpClient create(String baseUrl) {
        return new AscHttpClient(RestClient.builder().baseUrl(baseUrl),
                new ObjectMapper());
    }

    /** 包内构造：测试注入 MockRestServiceServer 绑定的 builder。 */
    AscHttpClient(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = builder
                .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError,
                        this::mapError)
                .build();
    }

    @Override
    public Task createTask(String taskId, String description) {
        return post("/v1/tasks", new CreateTaskReq(taskId, description, null), Task.class);
    }

    @Override
    public Task restoreTask(String newTaskId, String restoreFrom, String description) {
        return post("/v1/tasks", new CreateTaskReq(newTaskId, description, restoreFrom), Task.class);
    }

    @Override
    public void addConstraint(String taskId, String text, String level) {
        postVoid("/v1/tasks/" + taskId + "/constraints", new AddConstraintReq(text, level));
    }

    @Override
    public void recordFact(String taskId, String key, String value,
                           Instant expiresAt, String source) {
        postVoid("/v1/tasks/" + taskId + "/facts",
                new RecordFactReq(key, value, expiresAt, source));
    }

    @Override
    public void recordTodo(String taskId, String text, String source) {
        postVoid("/v1/tasks/" + taskId + "/todos", new RecordTodoReq(text, source));
    }

    @Override
    public void updateTodo(String taskId, String todoId, String status) {
        restClient.patch()
                .uri("/v1/tasks/{id}/todos/{todoId}", taskId, todoId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(new UpdateTodoReq(status)))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public StateCard getStateCard(String taskId, Budget budget) {
        return restClient.get()
                .uri(b -> {
                    org.springframework.web.util.UriBuilder ub =
                            b.path("/v1/tasks/{id}/state-card");
                    if (budget != null) {
                        ub = ub.queryParam("budget", budget.name().toLowerCase());
                    }
                    return ub.build(taskId);
                })
                .retrieve()
                .body(StateCard.class);
    }

    @Override
    public StateCard snapshot(String taskId) {
        return restClient.post()
                .uri("/v1/tasks/{id}/snapshot", taskId)
                .retrieve()
                .body(StateCard.class);
    }

    @Override
    public StateCard replay(String taskId, Long version) {
        return restClient.get()
                .uri(b -> {
                    org.springframework.web.util.UriBuilder ub =
                            b.path("/v1/tasks/{id}/replay");
                    if (version != null) {
                        ub = ub.queryParam("version", version);
                    }
                    return ub.build(taskId);
                })
                .retrieve()
                .body(StateCard.class);
    }

    @Override
    public StateCard handoff(String taskId, String toAgent) {
        return post("/v1/tasks/" + taskId + "/handoff", new HandoffReq(toAgent), StateCard.class);
    }

    /* ---------- 内部 ---------- */

    private <T> T post(String uri, Object body, Class<T> type) {
        return restClient.post()
                .uri(uri)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(type);
    }

    private void postVoid(String uri, Object body) {
        restClient.post()
                .uri(uri)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity();
    }

    /** 把 ErrorResponse JSON 翻译成 AscApiException。 */
    private void mapError(org.springframework.http.HttpRequest request,
                          org.springframework.http.client.ClientHttpResponse response)
            throws java.io.IOException {
        int status = response.getStatusCode().value();
        String raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        String code = "HTTP_" + status;
        String message = raw.isBlank() ? response.getStatusText() : raw;
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.hasNonNull("code") && node.get("code").isString()) {
                code = node.get("code").asString();
            }
            if (node.hasNonNull("message") && node.get("message").isString()) {
                message = node.get("message").asString();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，保留原文
        }
        throw new AscApiException(status, code, message);
    }

    /* ---------- 请求体 record（private，映射 web 层 DTO） ---------- */

    private record CreateTaskReq(String taskId, String description, String restoreFrom) {}

    private record AddConstraintReq(String text, String level) {}

    private record RecordFactReq(String key, String value, Instant expiresAt, String source) {}

    private record RecordTodoReq(String text, String source) {}

    private record UpdateTodoReq(String status) {}

    private record HandoffReq(String toAgent) {}
}
