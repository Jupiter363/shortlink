package com.nageoffer.shortlink.agent.agent.graph;

public record CampaignAnalysisGraphRequest(
        String sessionId,
        String username,
        String message,
        String traceId
) {
}
