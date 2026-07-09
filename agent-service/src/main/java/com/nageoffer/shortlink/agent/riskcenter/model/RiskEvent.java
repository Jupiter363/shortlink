package com.nageoffer.shortlink.agent.riskcenter.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskEventSource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RiskEvent(
        String eventId,
        RiskTargetType targetType,
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        int riskScore,
        RiskLevel riskLevel,
        List<RiskReasonCode> reasonCodes,
        Map<String, Object> evidence,
        List<String> recommendedActions,
        String agentSummary,
        String traceId,
        String sessionId,
        RiskEventSource source,
        LocalDateTime eventTime
) {

    public RiskEvent {
        eventId = valueOrEmpty(eventId);
        targetType = targetType == null ? RiskTargetType.SHORT_LINK : targetType;
        gid = valueOrEmpty(gid);
        domain = valueOrEmpty(domain);
        shortUri = valueOrEmpty(shortUri);
        fullShortUrl = valueOrEmpty(fullShortUrl);
        riskScore = clampScore(riskScore);
        riskLevel = riskLevel == null ? RiskLevel.fromScore(riskScore) : riskLevel;
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        agentSummary = valueOrEmpty(agentSummary);
        traceId = valueOrEmpty(traceId);
        sessionId = valueOrEmpty(sessionId);
        source = source == null ? RiskEventSource.PROFILE_BATCH : source;
        eventTime = eventTime == null ? LocalDateTime.now() : eventTime;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
