package com.jupiter.shortlink.id;

import java.time.Duration;
import java.util.Objects;

public record StepPolicy(
        boolean enabled,
        int initialStep,
        int minStep,
        int maxStep,
        Duration fastConsumption,
        Duration slowConsumption,
        int consecutiveSamples,
        Duration cooldown) {
    public StepPolicy {
        if (minStep < 1 || initialStep < minStep || initialStep > maxStep || maxStep > 1_000_000) {
            throw new IllegalArgumentException("Require 1 <= min <= initial <= max <= 1000000");
        }
        Objects.requireNonNull(fastConsumption);
        Objects.requireNonNull(slowConsumption);
        Objects.requireNonNull(cooldown);
        if (fastConsumption.isNegative()
                || fastConsumption.isZero()
                || slowConsumption.compareTo(fastConsumption) <= 0
                || cooldown.isNegative()
                || consecutiveSamples < 1
                || consecutiveSamples > 100) {
            throw new IllegalArgumentException("Invalid step feedback thresholds");
        }
        fastConsumption.toNanos();
        slowConsumption.toNanos();
        cooldown.toNanos();
    }

    public static StepPolicy defaults() {
        return new StepPolicy(
                false,
                100_000,
                10_000,
                1_000_000,
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                2,
                Duration.ofMinutes(5));
    }
}
