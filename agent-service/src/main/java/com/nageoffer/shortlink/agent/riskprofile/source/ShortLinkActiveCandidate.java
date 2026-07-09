package com.nageoffer.shortlink.agent.riskprofile.source;

public record ShortLinkActiveCandidate(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        Integer pv,
        Integer uv,
        Integer uip
) {

    public ShortLinkActiveCandidate(String gid, String domain, String shortUri, String fullShortUrl) {
        this(gid, domain, shortUri, fullShortUrl, null, null, null);
    }
}
