package io.asc.application;

import io.asc.domain.*;
import io.asc.storage.StateItemStore;
import io.asc.storage.TaskStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 阶段 1 的临时状态卡组装：只分组，不裁剪、不冲突消解。
 *
 * <p>阶段 2 会把这段逻辑迁移到 core.StateCompiler，
 * 这里只保留“从 storage 取数 + 组装”的薄壳。
 */
@Service
public class StateCardQueryService {

    private final TaskStore taskStore;
    private final StateItemStore stateItemStore;

    public StateCardQueryService(TaskStore taskStore, StateItemStore stateItemStore) {
        this.taskStore = taskStore;
        this.stateItemStore = stateItemStore;
    }

    public StateCard getStateCard(String taskId) {
        taskStore.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        List<StateItem> items = stateItemStore.findByTaskId(taskId);

        List<Constraint> hard = items.stream()
                .filter(Constraint.class::isInstance)
                .map(Constraint.class::cast)
                .filter(c -> c.level() == Constraint.ConstraintLevel.HARD)
                .sorted(Comparator.comparing(StateItem::createdAt))
                .toList();

        List<Constraint> soft = items.stream()
                .filter(Constraint.class::isInstance)
                .map(Constraint.class::cast)
                .filter(c -> c.level() == Constraint.ConstraintLevel.SOFT)
                .sorted(Comparator.comparing(StateItem::createdAt))
                .toList();

        List<Fact> facts = items.stream()
                .filter(Fact.class::isInstance)
                .map(Fact.class::cast)
                .sorted(Comparator.comparing(StateItem::createdAt))
                .toList();

        List<Todo> todos = items.stream()
                .filter(Todo.class::isInstance)
                .map(Todo.class::cast)
                .filter(t -> t.status() == Todo.TodoStatus.OPEN)
                .sorted(Comparator.comparing(StateItem::createdAt))
                .toList();

        List<InvalidatedItem> invalidated = items.stream()
                .filter(InvalidatedItem.class::isInstance)
                .map(InvalidatedItem.class::cast)
                .sorted(Comparator.comparing(StateItem::createdAt))
                .toList();

        long version = items.stream().mapToLong(StateItem::version).max().orElse(0L);
        int tokens = estimateTokens(hard, soft, facts, todos, invalidated);

        return new StateCard(taskId, version, hard, soft, facts, todos, invalidated, tokens, Instant.now());
    }

    /** 极简估算：所有文本字符数 / 4。阶段 2 换成 TokenEstimator。 */
    private int estimateTokens(List<Constraint> hard, List<Constraint> soft,
                               List<Fact> facts, List<Todo> todos,
                               List<InvalidatedItem> invalidated) {
        int chars = 0;
        chars += hard.stream().mapToInt(c -> c.text().length()).sum();
        chars += soft.stream().mapToInt(c -> c.text().length()).sum();
        chars += facts.stream().mapToInt(f -> f.key().length() + f.value().length()).sum();
        chars += todos.stream().mapToInt(t -> t.text().length()).sum();
        chars += invalidated.size() * 20;
        return Math.max(1, chars / 4);
    }
}