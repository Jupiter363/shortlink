package com.nageoffer.shortlink.agent.riskcenter.api.dto;

import java.util.List;
import java.util.Map;

public record RiskEventRespDTO(
        String eventId,
        String targetType,
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        int riskScore,
        String riskLevel,
        List<String> reasonCodes,
        Map<String, Object> evidence,
        List<String> recommendedActions,
        String agentSummary,
        String traceId,
        String sessionId,
        String source,
        String eventTime
) {
}
