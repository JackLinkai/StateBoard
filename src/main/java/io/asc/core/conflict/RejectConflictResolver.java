package io.asc.core.conflict;

import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REJECT：写入侧拦截为主（application 层在 REJECT 策略下拒绝同 key 异值写入），
 * 本实现是编译侧兜底——按理不该再见到同 key 多条；
 * 若见到（历史脏数据、运行期切换策略），直接抛
 * {@link ConflictDetectedException}，绝不悄悄挑一个值注入 Agent。
 */
@Component
public class RejectConflictResolver implements FactConflictResolver {

    @Override
    public ConflictStrategy strategy() {
        return ConflictStrategy.REJECT;
    }

    @Override
    public List<Fact> resolve(String taskId, List<Fact> liveFacts,
                              List<InvalidatedItem> invalidatedSink, Instant now) {
        Map<String, List<Fact>> byKey = LatestWinsConflictResolver.groupByKey(liveFacts);
        for (Map.Entry<String, List<Fact>> entry : byKey.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new ConflictDetectedException(taskId, entry.getKey());
            }
        }
        return new ArrayList<>(liveFacts);
    }
}
