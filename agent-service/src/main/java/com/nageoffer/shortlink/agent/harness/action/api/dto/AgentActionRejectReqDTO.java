package com.nageoffer.shortlink.agent.harness.action.api.dto;

public record AgentActionRejectReqDTO(
        String expectedGid,
        long expectedVersion,
        String reason,
        String reviewAction
) {
}
