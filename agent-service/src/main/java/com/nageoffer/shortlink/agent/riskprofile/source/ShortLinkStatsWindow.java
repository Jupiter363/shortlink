package com.nageoffer.shortlink.agent.riskprofile.source;

import java.time.Instant;

public record ShortLinkStatsWindow(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        Instant startTime,
        Instant endTime,
        Integer pv,
        Integer uv,
        Integer uip,
        Double topIpShare,
        Double topVisitorShare,
        Double topRegionShare,
        Double topDeviceShare,
        Double topBrowserShare,
        Double peakHourShare,
        Double repeatVisitRatio
) {
}
