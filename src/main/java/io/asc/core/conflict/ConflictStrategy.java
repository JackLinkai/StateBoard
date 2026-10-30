package io.asc.core.conflict;

/**
 * 事实冲突消解策略（README 配置项 asc.compiler.fact-conflict-strategy）。
 *
 * <ul>
 *   <li><b>LATEST_WINS</b>：同 key 的活事实取最新一条进卡，旧条转 SUPERSEDED——
 *       默认策略，对应"销量 100 被 120 覆盖"；</li>
 *   <li><b>MANUAL</b>：冲突不自动裁决。同 key 出现多条活事实时，
 *       该 key 全部候选都不进"已确认事实"格，统一转 CONFLICT 等
 *       人工裁决后重新写入权威值——宁可缺不可错；</li>
 *   <li><b>REJECT</b>：写入时就拒绝冲突（同 key 已有值不同的事实 → 409），
 *       从源头保证同一 key 只有一个值；编译时若仍见同 key 多条
 *       （如历史脏数据、运行期切策略）则抛 {@link ConflictDetectedException} 兜底。</li>
 * </ul>
 */
public enum ConflictStrategy {
    LATEST_WINS,
    MANUAL,
    REJECT;

    /** 解析配置字符串（大小写不敏感）；非法值启动即失败。 */
    public static ConflictStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return LATEST_WINS;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid fact-conflict-strategy: " + value
                            + ", expected LATEST_WINS, MANUAL or REJECT");
        }
    }
}
