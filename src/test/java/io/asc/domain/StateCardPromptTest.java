package io.asc.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StateCard.toPrompt() 渲染测试：四格分区、空格省略、失效项标注替代者。
 */
class StateCardPromptTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final String TASK = "task-1";

    @Test
    @DisplayName("四格全满：按 必须遵守/软约束/事实/待办/失效 顺序渲染")
    void renders_all_sections() {
        StateCard card = new StateCard(TASK, 7,
                List.of(new Constraint("c1", TASK, "只允许查询最近30天数据",
                        Constraint.ConstraintLevel.HARD, NOW, "user", 1)),
                List.of(new Constraint("c2", TASK, "优先上海",
                        Constraint.ConstraintLevel.SOFT, NOW, "user", 2)),
                List.of(new Fact("f1", TASK, "shanghai_sales", "120", NOW, null, "tool", 3)),
                List.of(new Todo("t1", TASK, "杭州数据待查", Todo.TodoStatus.OPEN, NOW, "model", 4)),
                List.of(new InvalidatedItem("f0", TASK, "SUPERSEDED", "f1", NOW, "compiler", 2)),
                88, NOW);

        String prompt = card.toPrompt();

        assertThat(prompt).containsSubsequence(
                "【任务状态卡】task=task-1 v7",
                "【必须遵守】",
                "- 只允许查询最近30天数据",
                "【软约束（尽量遵守）】",
                "- 优先上海",
                "【已确认事实】",
                "- shanghai_sales = 120",
                "【待办】",
                "- 杭州数据待查",
                "【已失效/冲突，勿再使用】",
                "- f0: SUPERSEDED（已被 f1 取代）");
    }

    @Test
    @DisplayName("空格省略：只渲染有内容的分区，失效项无替代者时不写'已被取代'")
    void omits_empty_sections() {
        StateCard card = new StateCard(TASK, 1,
                List.of(new Constraint("c1", TASK, "硬约束", Constraint.ConstraintLevel.HARD,
                        NOW, "user", 1)),
                List.of(), List.of(), List.of(),
                List.of(new InvalidatedItem("f0", TASK, "EXPIRED", null, NOW, "compiler", 1)),
                10, NOW);

        String prompt = card.toPrompt();

        assertThat(prompt)
                .contains("【必须遵守】").contains("- 硬约束")
                .contains("- f0: EXPIRED")
                .doesNotContain("软约束")
                .doesNotContain("已确认事实")
                .doesNotContain("【待办】")
                .doesNotContain("已被");
    }

    @Test
    @DisplayName("空卡只有头部一行")
    void empty_card_has_header_only() {
        StateCard card = new StateCard(TASK, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), 1, NOW);

        assertThat(card.toPrompt()).isEqualTo("【任务状态卡】task=task-1 v0");
    }
}
