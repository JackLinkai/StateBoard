package io.asc.storage;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateCard;
import io.asc.domain.StateItem;
import io.asc.domain.Task;
import io.asc.domain.Todo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StateItemStore 集成测试：Testcontainers 起一个真实 Postgres，
 * 走 domain → entity → jsonb → entity → domain 全链路。
 *
 * <p>@ServiceConnection 让 Spring Boot 自动用容器地址替换 application.yml 里的 datasource；
 * 容器里的库是全新的，启动时 Flyway 会执行 V1__init.sql 建表，再经过 ddl-auto=validate 校验实体映射。
 *
 * <p>disabledWithoutDocker = true：本机没有 Docker 时跳过（不失败），
 * 装了 Docker 或在 CI 上会正常执行。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StateItemStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private StateItemStore stateItemStore;

    @Autowired
    private TaskStore taskStore;

    @Autowired
    private StateCardVersionStore stateCardVersionStore;

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void save_fact_then_read_back_equals() {
        String taskId = "tc-fact-001";
        taskStore.save(new Task(taskId, "集成测试：存取 Fact", Task.TaskStatus.ACTIVE, NOW, NOW, 0));

        Fact fact = new Fact("f-001", taskId, "shanghai_sales_last_7d", "120",
                NOW, Instant.parse("2026-11-05T00:00:00Z"), "tool:query_sales", 1);

        stateItemStore.save(fact);

        Optional<StateItem> loaded = stateItemStore.findById("f-001");
        assertThat(loaded).contains(fact);
    }

    @Test
    void save_fact_without_ttl_then_read_back_equals() {
        String taskId = "tc-fact-002";
        taskStore.save(new Task(taskId, "集成测试：无 TTL Fact", Task.TaskStatus.ACTIVE, NOW, NOW, 0));

        Fact fact = new Fact("f-002", taskId, "city_resolved", "上海",
                NOW, null, "model", 2);
        stateItemStore.save(fact);

        assertThat(stateItemStore.findById("f-002")).contains(fact);
    }

    @Test
    void findByTaskId_returns_items_in_insert_order() {
        String taskId = "tc-order-001";
        taskStore.save(new Task(taskId, "集成测试：顺序", Task.TaskStatus.ACTIVE, NOW, NOW, 0));

        Fact first = new Fact("f-101", taskId, "step", "1", NOW, null, "tool:a", 3);
        Todo second = new Todo("t-101", taskId, "杭州数据待查", Todo.TodoStatus.OPEN,
                NOW.plusSeconds(1), "model", 4);
        Fact third = new Fact("f-102", taskId, "step", "2", NOW.plusSeconds(2), null, "tool:a", 5);

        stateItemStore.save(first);
        stateItemStore.save(second);
        stateItemStore.save(third);

        List<StateItem> items = stateItemStore.findByTaskId(taskId);
        assertThat(items).containsExactly(first, second, third);
    }

    @Test
    void save_card_version_then_read_back_equals() {
        String taskId = "tc-card-001";
        taskStore.save(new Task(taskId, "集成测试：状态卡版本", Task.TaskStatus.ACTIVE, NOW, NOW, 2));

        StateCard card = new StateCard(taskId, 2,
                List.of(new Constraint("c-001", taskId, "只允许查询最近30天数据",
                        Constraint.ConstraintLevel.HARD, NOW, "user", 1)),
                List.of(),
                List.of(new Fact("f-201", taskId, "shanghai_sales", "120",
                        NOW, null, "tool", 2)),
                List.of(new Todo("t-201", taskId, "杭州数据待查",
                        Todo.TodoStatus.OPEN, NOW, "model", 2)),
                List.of(new InvalidatedItem("i-201", taskId, "SUPERSEDED", "f-202",
                        NOW, "compiler", 1)),
                42, NOW);

        stateCardVersionStore.save(card);

        assertThat(stateCardVersionStore.find(taskId, 2)).contains(card);
    }
}
