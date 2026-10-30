package io.asc.core.conflict;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 冲突消解策略配置：读 {@code asc.compiler.fact-conflict-strategy}
 * （缺省 LATEST_WINS），配置非法在启动期即失败。
 * 编译器与写入侧校验共用这一份，保证语义一致。
 */
@Component
public class ConflictStrategyProperties {

    private final ConflictStrategy strategy;

    public ConflictStrategyProperties(
            @Value("${asc.compiler.fact-conflict-strategy:LATEST_WINS}") String configured) {
        this.strategy = ConflictStrategy.parse(configured);
    }

    public ConflictStrategy get() {
        return strategy;
    }
}
