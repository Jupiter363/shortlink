package com.jupiter.shortlink.id;

import java.time.Duration;
import java.util.Objects;

/**
 * The DataSource must separately bound pool acquisition/connect time and must not be
 * transaction-aware.
 */
public record JdbcOptions(
        String expectedCatalog,
        Duration statementTimeout,
        Duration networkTimeout,
        int lockWaitSeconds) {
    public JdbcOptions {
        if (expectedCatalog == null || expectedCatalog.isBlank()) {
            throw new IllegalArgumentException("Explicit expected database catalog required");
        }
        Objects.requireNonNull(statementTimeout);
        Objects.requireNonNull(networkTimeout);
        if (statementTimeout.isZero()
                || statementTimeout.isNegative()
                || networkTimeout.compareTo(statementTimeout) < 0
                || networkTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || lockWaitSeconds < 1
                || lockWaitSeconds > 60) {
            throw new IllegalArgumentException("Invalid JDBC deadline configuration");
        }
    }

    public static JdbcOptions defaults() {
        return new JdbcOptions("ds_0", Duration.ofSeconds(2), Duration.ofSeconds(3), 2);
    }
}
