package com.nageoffer.shortlink.agent.securityriskagent.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProfileRiskAnalysisContext(
        String gid,
        GroupRiskProfile groupProfile,
        List<ShortLinkRiskProfile> shortLinkProfiles
) {

    public ProfileRiskAnalysisContext {
        gid = gid == null ? "" : gid;
        shortLinkProfiles = shortLinkProfiles == null ? List.of() : List.copyOf(shortLinkProfiles);
    }

    public static ProfileRiskAnalysisContext empty() {
        return new ProfileRiskAnalysisContext("", null, List.of());
    }

    public boolean isEmpty() {
        return gid.isBlank() || (groupProfile == null && shortLinkProfiles.isEmpty());
    }

    public Map<String, Object> toDataSource() {
        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "risk_profile");
        dataSource.put("gid", gid);
        if (groupProfile != null) {
            dataSource.put("groupProfile", groupProfileMap(groupProfile));
        }
        dataSource.put("shortLinkProfiles", shortLinkProfiles.stream()
                .map(this::shortLinkMap)
                .toList());
        return dataSource;
    }

    public Map<String, Object> toToolExecution() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", "risk_profile_context");
        execution.put("arguments", Map.of("gid", gid));
        execution.put("success", true);
        execution.put("data", toDataSource());
        return execution;
    }

    public List<Object> riskCards() {
        return shortLinkProfiles.stream()
                .filter(profile -> profile.riskScore() >= 40)
                .map(this::riskCard)
                .map(card -> (Object) card)
                .toList();
    }

    private Map<String, Object> riskCard(ShortLinkRiskProfile profile) {
        Map<String, Object> card = shortLinkMap(profile);
        card.put("type", "risk_profile_short_link");
        card.put("title", "Risk profile short link");
        card.put("riskLevel", profile.riskLevel().name().toLowerCase());
        return card;
    }

    private Map<String, Object> groupProfileMap(GroupRiskProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("gid", profile.gid());
        value.put("profileWindowStart", profile.profileWindowStart() == null ? "" : profile.profileWindowStart().toString());
        value.put("profileWindowEnd", profile.profileWindowEnd() == null ? "" : profile.profileWindowEnd().toString());
        value.put("totalShortLinksScanned", profile.totalShortLinksScanned());
        value.put("lowRiskCount", profile.lowRiskCount());
        value.put("mediumRiskCount", profile.mediumRiskCount());
        value.put("highRiskCount", profile.highRiskCount());
        value.put("watchingCount", profile.watchingCount());
        value.put("disabledCount", profile.disabledCount());
        value.put("avgRiskScore", profile.avgRiskScore());
        value.put("maxRiskScore", profile.maxRiskScore());
        value.put("groupRiskScore", profile.groupRiskScore());
        value.put("groupRiskLevel", profile.groupRiskLevel().name());
        value.put("groupReasonCodes", profile.groupReasonCodes().stream().map(RiskReasonCode::name).toList());
        value.put("riskTrend7d", profile.riskTrend7d().stream().map(this::trendMap).toList());
        value.put("agentSummary", profile.agentSummary());
        return value;
    }

    private Map<String, Object> trendMap(RiskTrendPoint point) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("date", point.date().toString());
        value.put("riskScore", point.riskScore());
        value.put("riskLevel", point.riskLevel().name());
        return value;
    }

    private Map<String, Object> shortLinkMap(ShortLinkRiskProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("gid", profile.gid());
        value.put("domain", profile.domain());
        value.put("shortUri", profile.shortUri());
        value.put("fullShortUrl", profile.fullShortUrl());
        value.put("profileWindowStart", profile.profileWindowStart() == null ? "" : profile.profileWindowStart().toString());
        value.put("profileWindowEnd", profile.profileWindowEnd() == null ? "" : profile.profileWindowEnd().toString());
        value.put("riskScore", profile.riskScore());
        value.put("riskLevel", profile.riskLevel().name());
        value.put("reasonCodes", profile.reasonCodes().stream().map(RiskReasonCode::name).sorted().toList());
        value.put("metrics", metricsMap(profile.metrics()));
        value.put("watchStatus", profile.watchStatus().name());
        value.put("latestPolicyActions", profile.latestPolicyActions());
        value.put("latestAgentSummary", profile.latestAgentSummary());
        return value;
    }

    private Map<String, Object> metricsMap(ShortLinkRiskMetrics metrics) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("pv2h", metrics.pv2h());
        value.put("uv2h", metrics.uv2h());
        value.put("pv24h", metrics.pv24h());
        value.put("uv24h", metrics.uv24h());
        value.put("pv7d", metrics.pv7d());
        value.put("uv7d", metrics.uv7d());
        putIfNotNull(value, "pvGrowth2hVs24hAvg", metrics.pvGrowth2hVs24hAvg());
        putIfNotNull(value, "topIpShare", metrics.topIpShare());
        putIfNotNull(value, "topRegionShare", metrics.topRegionShare());
        putIfNotNull(value, "topDeviceShare", metrics.topDeviceShare());
        putIfNotNull(value, "topBrowserShare", metrics.topBrowserShare());
        putIfNotNull(value, "pvPerUv", metrics.pvPerUv());
        putIfNotNull(value, "peakHourShare", metrics.peakHourShare());
        putIfNotNull(value, "repeatVisitRatio", metrics.repeatVisitRatio());
        return value;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
