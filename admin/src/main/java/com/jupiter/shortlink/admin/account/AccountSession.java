package com.jupiter.shortlink.admin.account;

/** The entire session wire contract. Never add credentials or persistence entities. */
public record AccountSession(long tenantId, String username, long authVersion, long expiresAt) {
    public AccountSession {
        if (tenantId <= 0
                || username == null
                || username.isBlank()
                || authVersion <= 0
                || expiresAt <= 0) {
            throw new IllegalArgumentException("Invalid session principal");
        }
    }
}
