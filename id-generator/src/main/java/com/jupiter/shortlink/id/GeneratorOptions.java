package com.jupiter.shortlink.id;

import java.time.Duration;
import java.util.Objects;

public record GeneratorOptions(
        StepPolicy stepPolicy,
        int prefetchRemainingPercent,
        int maxReservationSize,
        int maxRangesPerReservation,
        Duration requestTimeout,
        Duration lockTimeout,
        Duration closeTimeout,
        int refillAttempts,
        Duration retryBackoff) {
    public GeneratorOptions {
        Objects.requireNonNull(stepPolicy);
        if (prefetchRemainingPercent < 1
                || prefetchRemainingPercent > 99
                || maxReservationSize < 1
                || maxReservationSize > 1_000_000
                || maxRangesPerReservation < 1
                || maxRangesPerReservation > 10_000
                || refillAttempts < 1
                || refillAttempts > 10) {
            throw new IllegalArgumentException("Invalid generator resource limit");
        }
        positive(requestTimeout, "requestTimeout");
        positive(lockTimeout, "lockTimeout");
        positive(closeTimeout, "closeTimeout");
        positive(retryBackoff, "retryBackoff");
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(name + " must be in (0, 5 minutes]");
        }
        value.toNanos();
    }

    public static GeneratorOptions defaults() {
        return new GeneratorOptions(
                StepPolicy.defaults(),
                20,
                50_000,
                128,
                Duration.ofSeconds(3),
                Duration.ofMillis(100),
                Duration.ofSeconds(3),
                3,
                Duration.ofMillis(50));
    }
}
