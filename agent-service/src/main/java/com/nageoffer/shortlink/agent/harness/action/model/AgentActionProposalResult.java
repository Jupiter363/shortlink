package com.nageoffer.shortlink.agent.harness.action.model;

import java.util.Objects;

public record AgentActionProposalResult(
        AgentPendingAction action,
        boolean created
) {

    public AgentActionProposalResult {
        action = Objects.requireNonNull(action, "action must not be null");
    }
}
