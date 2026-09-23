package com.jupiter.shortlink.agent.harness.runtime;

public record AgentRunRequest(
        String sessionId,
        String agentType,
        String username,
        String message,
        com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal,
        String requestKey) {
    public AgentRunRequest(String sessionId, String agentType, String username, String message,
                           com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal) {
        this(sessionId, agentType, username, message, principal, null);
    }

    public AgentRunRequest(String sessionId, String agentType, String username, String message) {
        this(sessionId, agentType, username, message, null, null);
    }
}
