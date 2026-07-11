package com.nageoffer.shortlink.agent.harness.action.api.dto;

public record AgentActionConfirmReqDTO(
        String expectedGid,
        long expectedVersion,
        String note
) {
}
