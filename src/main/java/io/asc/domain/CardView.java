package io.asc.domain;

import java.util.Locale;

/**
 * 状态卡的权限视图（v0.6）：按消费方裁剪格子的可见性。
 *
 * <p>与 {@link Budget} 互补：view 决定"哪些格子可被这个受众看到"（权限维度），
 * budget 决定"最多注入多少 token"（容量维度）。应用顺序：先 view 裁格，
 * 再 budget 裁条数——不可见的格子不占预算。
 *
 * <ul>
 *   <li><b>full</b>（默认）：四格全量，与 v0.5 之前的行为一致；</li>
 *   <li><b>lean</b>：只保留"必须遵守 / 已确认事实 / 待办"——给 token 敏感的
 *       执行型 Agent 注入，不携带软约束与失效记录；</li>
 *   <li><b>audit</b>：只保留"过期/冲突 / 软约束"——给审计与回看场景，
 *       看演变链与被降级的信息，不携带执行态。</li>
 * </ul>
 */
public enum CardView {

    FULL,
    LEAN,
    AUDIT;

    /**
     * 大小写不敏感解析，null / 空白视为未指定（返回 null，按 FULL 处理）。
     *
     * @throws IllegalArgumentException 视图名不存在时
     */
    public static CardView parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CardView.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown view: " + value
                    + ", expected full, lean or audit");
        }
    }
}
