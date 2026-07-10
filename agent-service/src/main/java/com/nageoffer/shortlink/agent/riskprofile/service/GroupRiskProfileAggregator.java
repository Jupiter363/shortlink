package com.nageoffer.shortlink.agent.riskprofile.service;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class GroupRiskProfileAggregator {

    private static final int TOP_RISK_SHORT_LINK_LIMIT = 10;

    private static final int TOP_REASON_CODE_LIMIT = 5;

    public GroupRiskProfile aggregate(
            String gid,
            List<ShortLinkRiskProfile> profiles,
            List<RiskTrendPoint> historyTrend
    ) {
        List<ShortLinkRiskProfile> safeProfiles = profiles == null ? List.of() : List.copyOf(profiles);
        int total = safeProfiles.size();
        int lowCount = countByLevel(safeProfiles, RiskLevel.LOW);
        int mediumCount = countByLevel(safeProfiles, RiskLevel.MEDIUM);
        int highCount = countByLevel(safeProfiles, RiskLevel.HIGH);
        double avgRiskScore = safeProfiles.stream()
                .mapToInt(ShortLinkRiskProfile::riskScore)
                .average()
                .orElse(0D);
        int maxRiskScore = safeProfiles.stream()
                .mapToInt(ShortLinkRiskProfile::riskScore)
                .max()
                .orElse(0);
        int groupRiskScore = Math.max(
                maxRiskScore,
                clampScore((int) Math.round(avgRiskScore + highCount * 5D + mediumCount * 2D))
        );
        return new GroupRiskProfile(
                gid,
                minWindowStart(safeProfiles),
                maxWindowEnd(safeProfiles),
                total,
                lowCount,
                mediumCount,
                highCount,
                watchingCount(safeProfiles),
                disabledCount(safeProfiles),
                avgRiskScore,
                maxRiskScore,
                groupRiskScore,
                RiskLevel.fromScore(groupRiskScore),
                topReasonCodes(safeProfiles),
                topRiskShortLinks(safeProfiles),
                historyTrend == null ? List.of() : List.copyOf(historyTrend),
                "",
                batchId(safeProfiles)
        );
    }

    private String batchId(List<ShortLinkRiskProfile> profiles) {
        return profiles.stream()
                .map(ShortLinkRiskProfile::batchId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private int countByLevel(List<ShortLinkRiskProfile> profiles, RiskLevel riskLevel) {
        return (int) profiles.stream()
                .filter(profile -> profile.riskLevel() == riskLevel)
                .count();
    }

    private int watchingCount(List<ShortLinkRiskProfile> profiles) {
        return (int) profiles.stream()
                .filter(profile -> profile.watchStatus() == RiskWatchStatus.WATCHING)
                .count();
    }

    private int disabledCount(List<ShortLinkRiskProfile> profiles) {
        return (int) profiles.stream()
                .filter(profile -> profile.latestPolicyActions().stream()
                        .anyMatch(action -> "DISABLE_SHORT_LINK".equals(action)))
                .count();
    }

    private List<RiskReasonCode> topReasonCodes(List<ShortLinkRiskProfile> profiles) {
        Map<RiskReasonCode, Integer> frequencies = new EnumMap<>(RiskReasonCode.class);
        profiles.stream()
                .flatMap(profile -> profile.reasonCodes().stream())
                .forEach(reasonCode -> frequencies.merge(reasonCode, 1, Integer::sum));
        return frequencies.entrySet().stream()
                .sorted(Map.Entry.<RiskReasonCode, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> entry.getKey().name()))
                .limit(TOP_REASON_CODE_LIMIT)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<ShortLinkRiskProfile> topRiskShortLinks(List<ShortLinkRiskProfile> profiles) {
        return RiskProfileCandidateSelector.top(profiles, TOP_RISK_SHORT_LINK_LIMIT);
    }

    private LocalDateTime minWindowStart(List<ShortLinkRiskProfile> profiles) {
        return profiles.stream()
                .map(ShortLinkRiskProfile::profileWindowStart)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private LocalDateTime maxWindowEnd(List<ShortLinkRiskProfile> profiles) {
        return profiles.stream()
                .map(ShortLinkRiskProfile::profileWindowEnd)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
