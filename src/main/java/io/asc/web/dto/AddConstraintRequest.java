package io.asc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 写约束请求。
 * level 可空，默认 HARD；传 SOFT 时必须是枚举合法值。
 */
public record AddConstraintRequest(
        @NotBlank(message = "constraint text must not be blank")
        String text,

        String level
) {}