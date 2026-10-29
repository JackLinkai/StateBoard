package io.asc.client;

import io.asc.domain.Budget;
import io.asc.domain.StateCard;
import io.asc.domain.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Java SDK 客户端测试：MockRestServiceServer 绑定 RestClient.Builder，
 * 校验每个方法命中的 HTTP 端点、请求体、错误响应的解析。
 */
class AscHttpClientTest {

    private MockRestServiceServer server;
    private AscClient client;
    private final ObjectMapper om = new ObjectMapper();

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://asc.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AscHttpClient(builder, om);
    }

    private static final String CARD_JSON = """
            {"taskId":"task-1","version":7,
             "hardConstraints":[{"id":"c1","taskId":"task-1","text":"只查30天",
               "level":"HARD","createdAt":"2026-10-29T12:00:00Z","source":"user","version":1}],
             "softConstraints":[],"verifiedFacts":[],"openTodos":[],"invalidated":[],
             "estimatedTokens":42,"compiledAt":"2026-10-29T12:00:00Z"}
            """;

    @Test
    @DisplayName("createTask：POST /v1/tasks，响应反序列化为 Task")
    void create_task() {
        server.expect(requestTo("http://asc.test/v1/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.restoreFrom").doesNotExist())
                .andRespond(withSuccess("""
                        {"taskId":"task-1","description":"d","status":"ACTIVE",
                         "createdAt":"%s","updatedAt":"%s","version":0,
                         "ownerAgent":null,"handoffCount":0}
                        """.formatted(NOW, NOW), MediaType.APPLICATION_JSON));

        Task task = client.createTask("task-1", "d");

        assertThat(task.taskId()).isEqualTo("task-1");
        server.verify();
    }

    @Test
    @DisplayName("restoreTask：POST /v1/tasks，带 restoreFrom 字段")
    void restore_task() {
        server.expect(requestTo("http://asc.test/v1/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.taskId").value("task-2"))
                .andExpect(jsonPath("$.restoreFrom").value("task-1"))
                .andRespond(withSuccess("""
                        {"taskId":"task-2","description":"恢复","status":"ACTIVE",
                         "createdAt":"%s","updatedAt":"%s","version":4,
                         "ownerAgent":null,"handoffCount":0}
                        """.formatted(NOW, NOW), MediaType.APPLICATION_JSON));

        Task task = client.restoreTask("task-2", "task-1", "恢复");

        assertThat(task.version()).isEqualTo(4);
        server.verify();
    }

    @Test
    @DisplayName("addConstraint / recordFact / recordTodo：POST 到对应端点，body 字段正确")
    void write_endpoints() {
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/constraints"))
                .andExpect(jsonPath("$.text").value("只查30天"))
                .andExpect(jsonPath("$.level").value("HARD"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/facts"))
                .andExpect(jsonPath("$.key").value("sales"))
                .andExpect(jsonPath("$.value").value("120"))
                .andExpect(jsonPath("$.source").value("tool:query_sales"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/todos"))
                .andExpect(jsonPath("$.text").value("杭州待查"))
                .andRespond(withSuccess());

        client.addConstraint("task-1", "只查30天", "HARD");
        client.recordFact("task-1", "sales", "120", null, "tool:query_sales");
        client.recordTodo("task-1", "杭州待查", "model");

        server.verify();
    }

    @Test
    @DisplayName("getStateCard：budget 档位转小写进 query 参数")
    void get_state_card() {
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/state-card?budget=small"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(CARD_JSON, MediaType.APPLICATION_JSON));

        StateCard card = client.getStateCard("task-1", Budget.SMALL);

        assertThat(card.version()).isEqualTo(7);
        assertThat(card.hardConstraints()).hasSize(1);
        assertThat(card.toPrompt()).contains("只查30天");
        server.verify();
    }

    @Test
    @DisplayName("getStateCard：budget 为 null 时 query 参数缺省")
    void get_state_card_without_budget() {
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/state-card"))
                .andRespond(withSuccess(CARD_JSON, MediaType.APPLICATION_JSON));

        client.getStateCard("task-1", null);

        server.verify();
    }

    @Test
    @DisplayName("snapshot / handoff 走 POST，replay 带 version 查询参数")
    void lifecycle_endpoints() {
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/snapshot"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(CARD_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/handoff"))
                .andExpect(jsonPath("$.toAgent").value("agent-b"))
                .andRespond(withSuccess(CARD_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/replay?version=5"))
                .andRespond(withSuccess(CARD_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://asc.test/v1/tasks/task-1/replay"))
                .andRespond(withSuccess(CARD_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.snapshot("task-1").taskId()).isEqualTo("task-1");
        assertThat(client.handoff("task-1", "agent-b").taskId()).isEqualTo("task-1");
        assertThat(client.replay("task-1", 5L).version()).isEqualTo(7);
        assertThat(client.replay("task-1", null).version()).isEqualTo(7);
        server.verify();
    }

    @Test
    @DisplayName("4xx ErrorResponse 解析为 AscApiException，含业务错误码")
    void error_response_mapped() {
        server.expect(requestTo("http://asc.test/v1/tasks/no-such/state-card"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"TASK_NOT_FOUND","message":"task not found: no-such",
                                 "traceId":"t-1"}
                                """));

        assertThatThrownBy(() -> client.getStateCard("no-such", null))
                .isInstanceOf(AscApiException.class)
                .hasMessageContaining("TASK_NOT_FOUND")
                .hasMessageContaining("no-such")
                .satisfies(e -> assertThat(((AscApiException) e).statusCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("非 JSON 错误体也抛 AscApiException（HTTP_xxx 兜底码）")
    void non_json_error_mapped() {
        server.expect(requestTo("http://asc.test/v1/tasks/x/snapshot"))
                .andRespond(withBadRequest().body("plain error"));

        assertThatThrownBy(() -> client.snapshot("x"))
                .isInstanceOf(AscApiException.class)
                .hasMessageContaining("HTTP_400");
    }
}
