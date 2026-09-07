package com.jupiter.shortlink.admin.account;

/** User-visible recovery state; database claims are not part of this view. */
public record AccountInitializationStatus(
        String state, String groupId, Long nextRetryAt, String reason) {
    public static AccountInitializationStatus from(AccountInitialization initialization) {
        return new AccountInitializationStatus(
                initialization.state(),
                initialization.groupId(),
                "READY".equals(initialization.state()) ? null : initialization.nextAttemptAt(),
                initialization.lastError());
    }
}
