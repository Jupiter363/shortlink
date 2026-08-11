package com.nageoffer.shortlink.admin.authority.session.persistence;

import java.time.Instant;

public record AgentTokenRevocation(
        String tokenId,
        String sessionId,
        String tenantId,
        long grantVersion,
        String reason,
        Instant revokedAt,
        Instant expiresAt
) {
}
