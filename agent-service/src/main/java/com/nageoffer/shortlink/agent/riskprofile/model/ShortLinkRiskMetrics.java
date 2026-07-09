package com.nageoffer.shortlink.agent.riskprofile.model;

public record ShortLinkRiskMetrics(
        int pv2h,
        int uv2h,
        int pv24h,
        int uv24h,
        int pv7d,
        int uv7d,
        Double pvGrowth2hVs24hAvg,
        Double topIpShare,
        Double topVisitorShare,
        Double topRegionShare,
        Double topDeviceShare,
        Double topBrowserShare,
        Double pvPerUv,
        Double peakHourShare,
        Double repeatVisitRatio
) {
}
