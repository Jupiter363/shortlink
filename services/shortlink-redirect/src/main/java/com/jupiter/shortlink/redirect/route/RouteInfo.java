package com.jupiter.shortlink.redirect.route;

public record RouteInfo(
        String domainNorm,
        String shortUri,
        long linkId,
        String tenantId,
        String currentGid,
        String originUrl,
        String routeStatus,
        Long expireAt,
        long routeVersion,
        long ownershipVersion,
        long authorityCheckedAt,
        long validUntil,
        long cacheGeneration) {
    public boolean fresh(long now, long generation) {
        return cacheGeneration == generation && now >= authorityCheckedAt && now < validUntil;
    }

    public boolean active(long now) {
        return "ACTIVE".equals(routeStatus) && (expireAt == null || now < expireAt);
    }

    public String resourceKey() {
        return tenantId + ":" + linkId;
    }

    public String addressKey() {
        return domainNorm + "/" + shortUri;
    }
}
