package io.asc.core.conflict;

import io.asc.domain.Fact;
import io.asc.domain.InvalidatedItem;

import java.time.Instant;
import java.util.List;

/**
 * 事实冲突消解 SPI：同 key 多条活事实时，谁进"已确认事实"格、
 * 谁进"过期/冲突"格，由实现说了算。
 *
 * <p>实现注册为 Spring bean，编译器按
 * {@code asc.compiler.fact-conflict-strategy} 配置选择对应策略。
 * 新增策略 = 实现本接口 + 打上 @Component，无需改动编译器。
 */
public interface FactConflictResolver {

    /** 衍生失效记录的来源标识：由编译器策略生成，而非 user / tool 写入。 */
    String COMPILER_SOURCE = "compiler";

    /** 本实现对应的策略标识。 */
    ConflictStrategy strategy();

    /**
     * 消解同 key 事实冲突。
     *
     * @param taskId          所属任务（失效记录溯源用）
     * @param liveFacts       未过期的活事实（可能有同 key 多条）
     * @param invalidatedSink 失效记录出口：策略衍生出的 InvalidatedItem 追加到这里，
     *                        编译器会把它们与存量失效记录合并进"过期/冲突"格
     * @param now             编译时刻（失效记录的时间戳）
     * @return 进入"已确认事实"格的胜者（无冲突时与 liveFacts 等价）
     */
    List<Fact> resolve(String taskId, List<Fact> liveFacts,
                       List<InvalidatedItem> invalidatedSink, Instant now);
}
