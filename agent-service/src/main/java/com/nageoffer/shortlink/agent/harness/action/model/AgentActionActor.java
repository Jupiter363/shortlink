package com.nageoffer.shortlink.agent.harness.action.model;

public record AgentActionActor(
        String username,
        String userId,
        String realName,
        String expectedGid
) {
}
