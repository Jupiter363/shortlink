package com.jupiter.shortlink.admin.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Durable intents remain until the external side effect is acknowledged; all retries are
 * idempotent.
 */
@Component
public final class AccountRecoveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AccountRecoveryWorker.class);
    private final AccountIntentRepository intents;
    private final DefaultGroupCommandClient command;
    private final AccountSessionStore sessions;
    private final Clock clock;

    @Autowired
    public AccountRecoveryWorker(
            AccountIntentRepository intents,
            DefaultGroupCommandClient command,
            AccountSessionStore sessions) {
        this(intents, command, sessions, Clock.systemUTC());
    }

    public AccountRecoveryWorker(
            AccountIntentRepository intents,
            DefaultGroupCommandClient command,
            AccountSessionStore sessions,
            Clock clock) {
        this.intents = intents;
        this.command = command;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${shortlink.account.recovery-delay-ms:2000}")
    public void recover() {
        // Revocation is processed first and independent of Command availability.
        try {
            for (AccountIntentRepository.SessionCleanup item : intents.findCleanup(64)) {
                try {
                    sessions.revokeBefore(item.username(), item.authVersion());
                    intents.completeCleanup(item);
                } catch (RuntimeException unavailable) {
                    LOG.warn("Account session cleanup pending; retrying from durable intent");
                    break;
                }
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("Account cleanup store unavailable");
        }
        try {
            for (AccountInitialization item : intents.findDue(clock.millis(), 8)) recover(item);
        } catch (RuntimeException unavailable) {
            LOG.warn("Account initialization store unavailable");
        }
    }

    public void recover(AccountInitialization item) {
        long now = clock.millis();
        if (!intents.claim(item, now, now + 30_000)) return;
        try {
            String gid = command.initialize(item);
            intents.complete(item, gid, clock.millis());
        } catch (RuntimeException unavailable) {
            long retryDelay = Math.min(3_600_000L, 2_000L << Math.min(item.attempts(), 10));
            // Persist a bounded error code, never HTTP bodies, credentials, PII or stack text.
            intents.failed(
                    item, "COMMAND_UNAVAILABLE", clock.millis() + retryDelay, clock.millis());
        }
    }
}
