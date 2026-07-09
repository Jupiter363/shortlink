package com.nageoffer.shortlink.agent.riskprofile.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;

import java.time.LocalDateTime;
import java.util.List;

public record GroupRiskProfile(
        String gid,
        LocalDateTime profileWindowStart,
        LocalDateTime profileWindowEnd,
        int totalShortLinksScanned,
        int lowRiskCount,
        int mediumRiskCount,
        int highRiskCount,
        int watchingCount,
        int disabledCount,
        double avgRiskScore,
        int maxRiskScore,
        int groupRiskScore,
        RiskLevel groupRiskLevel,
        List<RiskReasonCode> groupReasonCodes,
        List<ShortLinkRiskProfile> topRiskShortLinks,
        List<RiskTrendPoint> riskTrend7d,
        String agentSummary
) {

    public GroupRiskProfile {
        gid = gid == null ? "" : gid;
        totalShortLinksScanned = nonNegative(totalShortLinksScanned);
        lowRiskCount = nonNegative(lowRiskCount);
        mediumRiskCount = nonNegative(mediumRiskCount);
        highRiskCount = nonNegative(highRiskCount);
        watchingCount = nonNegative(watchingCount);
        disabledCount = nonNegative(disabledCount);
        avgRiskScore = Math.max(0D, Math.min(avgRiskScore, 100D));
        maxRiskScore = clampScore(maxRiskScore);
        groupRiskScore = clampScore(groupRiskScore);
        groupRiskLevel = groupRiskLevel == null ? RiskLevel.fromScore(groupRiskScore) : groupRiskLevel;
        groupReasonCodes = groupReasonCodes == null ? List.of() : List.copyOf(groupReasonCodes);
        topRiskShortLinks = topRiskShortLinks == null ? List.of() : List.copyOf(topRiskShortLinks);
        riskTrend7d = riskTrend7d == null ? List.of() : List.copyOf(riskTrend7d);
        agentSummary = agentSummary == null ? "" : agentSummary;
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
