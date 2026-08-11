package com.nageoffer.shortlink.admin.authority.identity.model;

import java.time.Instant;
import java.util.Set;

public record AgentTokenPrincipal(
        String subject,
        String username,
        String tenantId,
        String sessionId,
        long grantVersion,
        Set<String> scopes,
        String tokenId,
        String parentTokenId,
        Instant issuedAt,
        Instant expiresAt
) {

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
