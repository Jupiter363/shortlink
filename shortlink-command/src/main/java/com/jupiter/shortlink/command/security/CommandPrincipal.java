package com.jupiter.shortlink.command.security;

public record CommandPrincipal(long tenantId, String username, long authVersion) {
    public CommandPrincipal {
        if (tenantId <= 0
                || username == null
                || username.isBlank()
                || username.length() > 64
                || username.chars().anyMatch(c -> c < 32)
                || authVersion < 1) throw new IllegalArgumentException("Invalid principal");
    }
}
