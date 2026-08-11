package com.nageoffer.shortlink.admin.authority.session;

import java.time.Instant;
import java.util.Set;

public record AgentSessionGrant(
        Long id,
        String sessionId,
        String tenantId,
        String ownerUserId,
        String ownerUsername,
        String agentType,
        Set<String> scopes,
        AgentSessionGrantStatus status,
        long grantVersion,
        String latestTokenId,
        Instant latestTokenExpiresAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
