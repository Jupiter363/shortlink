package com.jupiter.shortlink.agent.harness.security;

import java.util.Map;

/** Trusted transport metadata only; never constructed from model arguments or free-text gids. */
public record AgentPrincipal(String tenantId, String username, long authVersion, boolean system) {
    public AgentPrincipal {
        if (username == null || !username.matches("[A-Za-z0-9_-]{3,64}"))
            throw new IllegalArgumentException("Trusted username is required");
        if (!system
                && (tenantId == null || !tenantId.matches("[1-9][0-9]{0,18}") || authVersion < 1)) {
            throw new IllegalArgumentException("Trusted tenant and authVersion are required");
        }
    }

    public static AgentPrincipal system(String username) {
        return new AgentPrincipal("", username, 0, true);
    }

    public Map<String, Object> toState() {
        return Map.of(
                "tenantId",
                tenantId,
                "username",
                username,
                "authVersion",
                authVersion,
                "system",
                system);
    }

    public static AgentPrincipal fromState(Object value) {
        if (value instanceof AgentPrincipal principal) return principal;
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return null;
        Object version = map.get("authVersion");
        return new AgentPrincipal(
                String.valueOf(map.get("tenantId")),
                String.valueOf(map.get("username")),
                version instanceof Number number
                        ? number.longValue()
                        : Long.parseLong(String.valueOf(version)),
                Boolean.TRUE.equals(map.get("system")));
    }
}
