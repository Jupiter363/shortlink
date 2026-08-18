package com.jupiter.shortlink.agent.harness.runtime;

public record AgentRunRequest(String sessionId, String agentType, String username, String message) {
}
