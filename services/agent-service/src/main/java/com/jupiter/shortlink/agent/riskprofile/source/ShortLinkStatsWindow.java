package com.jupiter.shortlink.agent.riskprofile.source;

import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;
import com.jupiter.shortlink.agent.riskprofile.model.RiskWindowDimensions;

import java.time.Instant;
import java.util.Map;

public record ShortLinkStatsWindow(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        Instant startTime,
        Instant endTime,
        Long pv,
        Long uv,
        Long uip,
        Double topIpShare,
        Double topVisitorShare,
        Double topRegionShare,
        Double topDeviceShare,
        Double topBrowserShare,
        Double peakHourShare,
        Double repeatVisitRatio,
        String tenantId,
        Long linkId,
        Map<String, Object> meta,
        RiskWindowDimensions dimensions) {
    public ShortLinkStatsWindow(String gid, String domain, String shortUri, String fullShortUrl,
            Instant startTime, Instant endTime, Long pv, Long uv, Long uip, Double topIpShare,
            Double topVisitorShare, Double topRegionShare, Double topDeviceShare, Double topBrowserShare,
            Double peakHourShare, Double repeatVisitRatio, String tenantId, Long linkId, Map<String, Object> meta) {
        this(gid, domain, shortUri, fullShortUrl, startTime, endTime, pv, uv, uip, topIpShare,
                topVisitorShare, topRegionShare, topDeviceShare, topBrowserShare, peakHourShare,
                repeatVisitRatio, tenantId, linkId, meta, null);
    }

    public ShortLinkStatsWindow(
            String gid,
            String domain,
            String shortUri,
            String fullShortUrl,
            Instant startTime,
            Instant endTime,
            Number pv,
            Number uv,
            Number uip,
            Double topIpShare,
            Double topVisitorShare,
            Double topRegionShare,
            Double topDeviceShare,
            Double topBrowserShare,
            Double peakHourShare,
            Double repeatVisitRatio) {
        this(
                gid,
                domain,
                shortUri,
                fullShortUrl,
                startTime,
                endTime,
                count(pv),
                count(uv),
                count(uip),
                topIpShare,
                topVisitorShare,
                topRegionShare,
                topDeviceShare,
                topBrowserShare,
                peakHourShare,
                repeatVisitRatio,
                null,
                null,
                Map.of());
    }

    private static Long count(Number value) {
        return value == null ? null : StatsEvidence.number(value);
    }
}
