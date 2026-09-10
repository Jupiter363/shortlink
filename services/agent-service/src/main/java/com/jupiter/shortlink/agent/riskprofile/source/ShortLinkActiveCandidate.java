package com.jupiter.shortlink.agent.riskprofile.source;

import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;

import java.util.Map;

public record ShortLinkActiveCandidate(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        Long pv,
        Long uv,
        Long uip,
        String tenantId,
        Long linkId,
        Map<String, Object> meta) {
    public ShortLinkActiveCandidate(
            String gid, String domain, String shortUri, String fullShortUrl) {
        this(gid, domain, shortUri, fullShortUrl, null, null, null, null, null, Map.of());
    }

    public ShortLinkActiveCandidate(
            String gid,
            String domain,
            String shortUri,
            String fullShortUrl,
            Number pv,
            Number uv,
            Number uip) {
        this(
                gid,
                domain,
                shortUri,
                fullShortUrl,
                count(pv),
                count(uv),
                count(uip),
                null,
                null,
                Map.of());
    }

    private static Long count(Number value) {
        return value == null ? null : StatsEvidence.number(value);
    }
}
