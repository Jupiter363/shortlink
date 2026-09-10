package com.jupiter.shortlink.agent.securityriskagent.graph;

import com.jupiter.shortlink.agent.securityriskagent.model.RiskAnalysisInput;

public record SecurityRiskGraphRequest(
        String sessionId,
        String username,
        String message,
        String traceId,
        RiskAnalysisInput analysisInput,
        com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal) {

    public SecurityRiskGraphRequest(
            String sessionId, String username, String message, String traceId) {
        this(sessionId, username, message, traceId, null, null);
    }

    public SecurityRiskGraphRequest(
            String sessionId,
            String username,
            String message,
            String traceId,
            RiskAnalysisInput analysisInput) {
        this(sessionId, username, message, traceId, analysisInput, null);
    }

    public boolean isBatchExecution() {
        return analysisInput != null;
    }
}
