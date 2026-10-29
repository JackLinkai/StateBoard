package io.asc.storage.converter;

import io.asc.domain.Constraint;
import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import io.asc.domain.StateItem;
import io.asc.domain.Todo;
import io.asc.storage.entity.StateItemEntity;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * domain.StateItem 四类状态与 StateItemEntity（type + JSON payload）的双向转换。
 *
 * <p>职责边界：
 * <ul>
 *   <li>公共字段（id、taskId、source、version、createdAt）单独成列，这里直接搬运；</li>
 *   <li>各类型自有字段序列化进 payload jsonb 列，结构只由本类定义，别处不许猜；</li>
 *   <li>domain → entity 用 switch 模式匹配：StateItem 是 sealed interface，
 *       新增状态类型时编译器会强制在这里补 case，不会漏；</li>
 *   <li>entity → domain 手工从 JsonNode 取字段构造 record，不用 Jackson 反射绑定，
 *       避免给 domain record 加任何 Jackson 注解，保持领域模型纯净。</li>
 * </ul>
 *
 * <p>用 Jackson 3（tools.jackson），与 Spring Boot 4 的默认 JSON 栈一致；
 * 注入的 ObjectMapper 由 Boot 自动配置（JsonMapper）。
 *
 * <p>payload 字段约定（各类型只存自己的部分）：
 * <ul>
 *   <li>CONSTRAINT：text, level</li>
 *   <li>FACT：key, value, expiresAt（可空，ISO-8601 字符串）</li>
 *   <li>TODO：text, status</li>
 *   <li>INVALIDATED：reason, supersededBy（可空）</li>
 * </ul>
 * 可空字段为 null 时直接不写进 payload，读回时用 hasNonNull 判断，
 * 两个方向约定一致，保证 round-trip 后 equals 相等。
 */
@Component
public class StateItemConverter {

    private final ObjectMapper objectMapper;

    public StateItemConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** domain → entity：按具体类型分派，把自有字段序列化进 payload。 */
    public StateItemEntity toEntity(StateItem item) {
        String type = switch (item) {
            case Constraint c -> "CONSTRAINT";
            case Fact f -> "FACT";
            case Todo t -> "TODO";
            case InvalidatedItem i -> "INVALIDATED";
        };

        StateItemEntity entity = new StateItemEntity(
                item.id(),
                item.taskId(),
                type,
                buildPayload(item).toString(),
                item.source(),
                item.version(),
                item.createdAt());
        return entity;
    }

    /** entity → domain：按 type 反序列化 payload，构造对应 record。 */
    public StateItem toDomain(StateItemEntity entity) {
        JsonNode payload = parse(entity.getPayload());
        Instant createdAt = entity.getCreatedAt();
        return switch (entity.getType()) {
            case "CONSTRAINT" -> new Constraint(
                    entity.getId(), entity.getTaskId(),
                    text(payload, "text"),
                    Constraint.ConstraintLevel.valueOf(text(payload, "level")),
                    createdAt, entity.getSource(), entity.getVersion());
            case "FACT" -> new Fact(
                    entity.getId(), entity.getTaskId(),
                    text(payload, "key"),
                    text(payload, "value"),
                    createdAt,
                    instantOrNull(payload, "expiresAt"),
                    entity.getSource(), entity.getVersion());
            case "TODO" -> new Todo(
                    entity.getId(), entity.getTaskId(),
                    text(payload, "text"),
                    Todo.TodoStatus.valueOf(text(payload, "status")),
                    createdAt, entity.getSource(), entity.getVersion());
            case "INVALIDATED" -> new InvalidatedItem(
                    entity.getId(), entity.getTaskId(),
                    text(payload, "reason"),
                    textOrNull(payload, "supersededBy"),
                    createdAt, entity.getSource(), entity.getVersion());
            default -> throw new IllegalStateException(
                    "unknown state item type: " + entity.getType() + ", id=" + entity.getId());
        };
    }

    /* ---------- payload 序列化 ---------- */

    private ObjectNode buildPayload(StateItem item) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        switch (item) {
            case Constraint c -> {
                payload.put("text", c.text());
                payload.put("level", c.level().name());
            }
            case Fact f -> {
                payload.put("key", f.key());
                payload.put("value", f.value());
                if (f.expiresAt() != null) {
                    payload.put("expiresAt", f.expiresAt().toString());
                }
            }
            case Todo t -> {
                payload.put("text", t.text());
                payload.put("status", t.status().name());
            }
            case InvalidatedItem i -> {
                payload.put("reason", i.reason());
                if (i.supersededBy() != null) {
                    payload.put("supersededBy", i.supersededBy());
                }
            }
        }
        return payload;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("corrupted state item payload: " + json, e);
        }
    }

    /* ---------- payload 反序列化的小工具 ---------- */

    private String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalStateException("missing required payload field: " + field);
        }
        return node.asString();
    }

    private String textOrNull(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? payload.get(field).asString() : null;
    }

    private Instant instantOrNull(JsonNode payload, String field) {
        return payload.hasNonNull(field) ? Instant.parse(payload.get(field).asString()) : null;
    }
}
