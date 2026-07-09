package com.nageoffer.shortlink.agent.riskcenter.api.dto;

public record RiskPolicyDisableReqDTO(
        String reviewer,
        String reason,
        String traceId
) {
}
