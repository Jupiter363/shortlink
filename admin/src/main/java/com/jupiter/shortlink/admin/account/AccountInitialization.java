package com.jupiter.shortlink.admin.account;

public record AccountInitialization(
        long tenantId,
        String username,
        String commandId,
        String state,
        String groupId,
        int attempts,
        long nextAttemptAt,
        long claimVersion,
        String lastError) {}
