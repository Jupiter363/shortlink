package com.nageoffer.shortlink.admin.authority.identity.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record AgentSessionTokenResponse(
        String sessionId,
        String agentType,
        String runtimeUrl,
        String runtimeToken,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant runtimeTokenExpiresAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant sessionExpiresAt,
        long grantVersion
) {
}
