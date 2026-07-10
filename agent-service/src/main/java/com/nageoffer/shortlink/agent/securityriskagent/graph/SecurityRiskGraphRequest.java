package com.nageoffer.shortlink.agent.securityriskagent.graph;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskAnalysisInput;

public record SecurityRiskGraphRequest(
        String sessionId,
        String username,
        String message,
        String traceId,
        RiskAnalysisInput analysisInput
) {

    public SecurityRiskGraphRequest(String sessionId, String username, String message, String traceId) {
        this(sessionId, username, message, traceId, null);
    }

    public boolean isBatchExecution() {
        return analysisInput != null;
    }
}
