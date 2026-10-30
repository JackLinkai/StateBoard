package io.asc.core.conflict;

import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LATEST_WINS（默认）：同 key 的活事实按 createdAt 升序排（并列比 version），
 * 最后一条获胜；其余转 InvalidatedItem(SUPERSEDED)，supersededBy 指向胜者——
 * Agent 能看到"销量 100 已被 120 覆盖"的完整演变链。
 */
@Component
public class LatestWinsConflictResolver implements FactConflictResolver {

    @Override
    public ConflictStrategy strategy() {
        return ConflictStrategy.LATEST_WINS;
    }

    @Override
    public List<Fact> resolve(String taskId, List<Fact> liveFacts,
                              List<InvalidatedItem> invalidatedSink, Instant now) {
        Map<String, List<Fact>> byKey = groupByKey(liveFacts);
        List<Fact> winners = new ArrayList<>();
        for (List<Fact> group : byKey.values()) {
            group.sort(Comparator.comparing(Fact::createdAt).thenComparingLong(Fact::version));
            Fact winner = group.getLast();
            winners.add(winner);
            for (int i = 0; i < group.size() - 1; i++) {
                Fact loser = group.get(i);
                invalidatedSink.add(new InvalidatedItem(loser.id(), taskId,
                        InvalidatedItem.Reason.SUPERSEDED.name(), winner.id(),
                        now, COMPILER_SOURCE, loser.version()));
            }
        }
        return winners;
    }

    /** 按 key 分组，保持首次出现顺序（卡的组序稳定）。 */
    static Map<String, List<Fact>> groupByKey(List<Fact> liveFacts) {
        Map<String, List<Fact>> byKey = new LinkedHashMap<>();
        for (Fact fact : liveFacts) {
            byKey.computeIfAbsent(fact.key(), k -> new ArrayList<>()).add(fact);
        }
        return byKey;
    }
}
