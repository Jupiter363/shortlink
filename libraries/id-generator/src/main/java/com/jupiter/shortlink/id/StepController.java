package com.jupiter.shortlink.id;

/**
 * Consumption feedback derived from Leaf's duration-based doubling/halving; guarded by the
 * generator lock.
 */
final class StepController {
    private StepPolicy policy;
    private int step;
    private int direction;
    private int samples;
    private long lastChange;
    private boolean changed;
    private long changes;

    StepController(StepPolicy policy) {
        configure(policy);
    }

    void configure(StepPolicy replacement) {
        policy = replacement;
        step = replacement.initialStep();
        direction = 0;
        samples = 0;
        changed = false;
    }

    int currentStep() {
        return step;
    }

    long changes() {
        return changes;
    }

    void consumed(long elapsedNanos, long nowNanos, long actualSize) {
        // A shortened final segment is not a demand observation.
        if (!policy.enabled() || actualSize < policy.minStep()) return;
        int observation =
                elapsedNanos < policy.fastConsumption().toNanos()
                        ? 1
                        : elapsedNanos > policy.slowConsumption().toNanos() ? -1 : 0;
        if (observation == 0) {
            direction = 0;
            samples = 0;
            return;
        }
        samples = observation == direction ? Math.min(samples + 1, policy.consecutiveSamples()) : 1;
        direction = observation;
        if (samples < policy.consecutiveSamples()
                || (changed && nowNanos - lastChange < policy.cooldown().toNanos())) return;
        int replacement =
                observation > 0
                        ? Math.min(policy.maxStep(), step * 2)
                        : Math.max(policy.minStep(), step / 2);
        if (replacement != step) {
            step = replacement;
            lastChange = nowNanos;
            changed = true;
            changes++;
        }
        samples = 0;
    }
}
