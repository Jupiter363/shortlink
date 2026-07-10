package com.nageoffer.shortlink.agent.harness.action.model;

public record AgentActionExecutionContext(
        AgentActionActor actor,
        String note
) {
}
