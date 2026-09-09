package com.jupiter.shortlink.agent.riskprofile.model;

public record ShortLinkRiskMetrics(
        long pv2h,
        long uv2h,
        long pv24h,
        long uv24h,
        long pv7d,
        long uv7d,
        Double pvGrowth2hVs24hAvg,
        Double topIpShare,
        Double topVisitorShare,
        Double topRegionShare,
        Double topDeviceShare,
        Double topBrowserShare,
        Double pvPerUv,
        Double peakHourShare,
        Double repeatVisitRatio) {}
