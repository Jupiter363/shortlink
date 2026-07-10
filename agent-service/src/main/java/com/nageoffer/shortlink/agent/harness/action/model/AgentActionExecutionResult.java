package com.nageoffer.shortlink.agent.harness.action.model;

import java.util.Map;

public record AgentActionExecutionResult(Map<String, Object> result) {

    public AgentActionExecutionResult {
        result = AgentPendingActionView.immutableMap(result);
    }

    public static AgentActionExecutionResult empty() {
        return new AgentActionExecutionResult(Map.of());
    }
}
