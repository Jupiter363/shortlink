package com.nageoffer.shortlink.agent.harness.action.model;

public record AgentActionClaim(
        AgentPendingAction action,
        String executionToken,
        long claimedVersion
) {
}
