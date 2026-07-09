package com.nageoffer.shortlink.agent.campaignanalysisagent.graph;

public record CampaignAnalysisGraphRequest(
        String sessionId,
        String username,
        String message,
        String traceId
) {
}
