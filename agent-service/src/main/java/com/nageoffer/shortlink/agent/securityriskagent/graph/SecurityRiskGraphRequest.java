package com.nageoffer.shortlink.agent.securityriskagent.graph;

public record SecurityRiskGraphRequest(String sessionId, String username, String message, String traceId) {
}
