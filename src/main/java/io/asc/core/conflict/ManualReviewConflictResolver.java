package io.asc.core.conflict;

import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * MANUAL：冲突不自动裁决。
 *
 * <p>同 key 只有一条活事实 → 正常进卡；出现多条 → <b>该 key 全部候选</b>
 * 都不进"已确认事实"格，统一转 InvalidatedItem(CONFLICT) 等人工裁决。
 * 相比 LATEST_WINS"信最新的"，MANUAL 承认最新未必正确（工具 bug、乱序返回），
 * 宁可让该格暂缺，也不替人做判断。人工确认后重新写入权威值，
 * 下一轮编译自然恢复。
 */
@Component
public class ManualReviewConflictResolver implements FactConflictResolver {

    @Override
    public ConflictStrategy strategy() {
        return ConflictStrategy.MANUAL;
    }

    @Override
    public List<Fact> resolve(String taskId, List<Fact> liveFacts,
                              List<InvalidatedItem> invalidatedSink, Instant now) {
        Map<String, List<Fact>> byKey = LatestWinsConflictResolver.groupByKey(liveFacts);
        List<Fact> winners = new ArrayList<>();
        for (List<Fact> group : byKey.values()) {
            if (group.size() == 1) {
                winners.add(group.getFirst());
                continue;
            }
            group.sort(Comparator.comparing(Fact::createdAt).thenComparingLong(Fact::version));
            for (Fact candidate : group) {
                invalidatedSink.add(new InvalidatedItem(candidate.id(), taskId,
                        InvalidatedItem.Reason.CONFLICT.name(), null,
                        now, COMPILER_SOURCE, candidate.version()));
            }
        }
        return winners;
    }
}
