package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

public record CampaignAnalysisGraphRequest(
        String sessionId,
        String username,
        String message,
        String traceId,
        com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal,
        String requestKey) {
    public CampaignAnalysisGraphRequest(String sessionId, String username, String message, String traceId,
                                        com.jupiter.shortlink.agent.harness.security.AgentPrincipal principal) {
        this(sessionId, username, message, traceId, principal, null);
    }

    public CampaignAnalysisGraphRequest(
            String sessionId, String username, String message, String traceId) {
        this(sessionId, username, message, traceId, null, null);
    }
}
