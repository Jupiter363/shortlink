package com.nageoffer.shortlink.agent.riskcenter.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RiskSnapshot(
        RiskTargetType targetType,
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        int riskScore,
        RiskLevel riskLevel,
        List<RiskReasonCode> reasonCodes,
        List<Map<String, Object>> riskCards,
        RiskWatchStatus watchStatus,
        String policyStatus,
        String lastEventId,
        String lastTraceId,
        LocalDateTime lastScanTime
) {

    public RiskSnapshot {
        targetType = targetType == null ? RiskTargetType.SHORT_LINK : targetType;
        gid = valueOrEmpty(gid);
        domain = valueOrEmpty(domain);
        shortUri = valueOrEmpty(shortUri);
        fullShortUrl = valueOrEmpty(fullShortUrl);
        riskScore = clampScore(riskScore);
        riskLevel = riskLevel == null ? RiskLevel.fromScore(riskScore) : riskLevel;
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        riskCards = riskCards == null ? List.of() : List.copyOf(riskCards);
        watchStatus = watchStatus == null ? RiskWatchStatus.NONE : watchStatus;
        policyStatus = valueOrDefault(policyStatus, "NONE");
        lastEventId = valueOrEmpty(lastEventId);
        lastTraceId = valueOrEmpty(lastTraceId);
        lastScanTime = lastScanTime == null ? LocalDateTime.now() : lastScanTime;
    }

    public RiskSnapshot withWatchStatus(RiskWatchStatus nextWatchStatus) {
        return new RiskSnapshot(
                targetType,
                gid,
                domain,
                shortUri,
                fullShortUrl,
                riskScore,
                riskLevel,
                reasonCodes,
                riskCards,
                nextWatchStatus,
                policyStatus,
                lastEventId,
                lastTraceId,
                lastScanTime
        );
    }

    public RiskSnapshot asFalsePositive() {
        return new RiskSnapshot(
                targetType,
                gid,
                domain,
                shortUri,
                fullShortUrl,
                0,
                RiskLevel.LOW,
                List.of(),
                riskCards,
                watchStatus,
                policyStatus,
                lastEventId,
                lastTraceId,
                lastScanTime
        );
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
