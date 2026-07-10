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
        String latestAgentSummary,
        String batchId
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
        batchId = valueOrEmpty(batchId);
    }

    public ShortLinkRiskProfile(
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
        this(
                gid,
                domain,
                shortUri,
                fullShortUrl,
                profileWindowStart,
                profileWindowEnd,
                metrics,
                anomalyScore,
                riskScore,
                riskLevel,
                reasonCodes,
                watchStatus,
                latestPolicyActions,
                latestAgentSummary,
                legacyBatchId(profileWindowEnd)
        );
    }

    public ShortLinkRiskProfile withBatchId(String newBatchId) {
        return new ShortLinkRiskProfile(
                gid,
                domain,
                shortUri,
                fullShortUrl,
                profileWindowStart,
                profileWindowEnd,
                metrics,
                anomalyScore,
                riskScore,
                riskLevel,
                reasonCodes,
                watchStatus,
                latestPolicyActions,
                latestAgentSummary,
                newBatchId
        );
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String legacyBatchId(LocalDateTime profileWindowEnd) {
        return "legacy:" + valueOrEmpty(profileWindowEnd == null ? null : profileWindowEnd.toString());
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
