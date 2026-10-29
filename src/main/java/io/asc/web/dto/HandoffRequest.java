package io.asc.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 多 Agent 交接请求。
 *
 * @param toAgent 接手方 Agent 标识，必填
 */
public record HandoffRequest(
        @NotBlank(message = "toAgent must not be blank")
        String toAgent
) {}
