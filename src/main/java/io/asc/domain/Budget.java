package io.asc.domain;

import java.util.Locale;

/**
 * 状态卡的预算档位：限制一张状态卡注入 Agent 上下文时占用的 token 上限。
 *
 * <p>档位数值是每档允许的最大估算 token 数（见 StateCardCompiler 的估算规则）。
 * 请求不带 budget 参数时，使用配置项 asc.compiler.default-budget-tokens 的值。
 */
public enum Budget {

    /** 小卡：~400 token，适合高频轮次、上下文紧张的场景。 */
    SMALL(400),

    /** 中卡：~800 token。 */
    MEDIUM(800),

    /** 大卡：~1200 token，接近默认预算，信息最全。 */
    LARGE(1200);

    private final int tokens;

    Budget(int tokens) {
        this.tokens = tokens;
    }

    /** 该档位允许的最大估算 token 数。 */
    public int tokens() {
        return tokens;
    }

    /**
     * 大小写不敏感解析，null / 空白视为未指定（返回 null）。
     *
     * @throws IllegalArgumentException 档位名不存在时
     */
    public static Budget parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Budget.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown budget: " + value);
        }
    }
}
