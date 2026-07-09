package com.nageoffer.shortlink.agent.riskprofile.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public record ShortLinkRiskProfile(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        LocalDateTime profileWindowStart,
        LocalDateTime profileWindowEnd,
        ShortLinkRiskMetrics metrics,
        int anomalyScore,
        int riskScore,
        RiskLevel riskLevel,
        Set<RiskReasonCode> reasonCodes,
        RiskWatchStatus watchStatus,
        List<String> latestPolicyActions,
        String latestAgentSummary
) {

    public ShortLinkRiskProfile {
        gid = valueOrEmpty(gid);
        domain = valueOrEmpty(domain);
        shortUri = valueOrEmpty(shortUri);
        fullShortUrl = valueOrEmpty(fullShortUrl);
        anomalyScore = clampScore(anomalyScore);
        riskScore = clampScore(riskScore);
        riskLevel = riskLevel == null ? RiskLevel.fromScore(riskScore) : riskLevel;
        reasonCodes = reasonCodes == null ? Set.of() : Set.copyOf(reasonCodes);
        watchStatus = watchStatus == null ? RiskWatchStatus.NONE : watchStatus;
        latestPolicyActions = latestPolicyActions == null ? List.of() : List.copyOf(latestPolicyActions);
        latestAgentSummary = valueOrEmpty(latestAgentSummary);
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
