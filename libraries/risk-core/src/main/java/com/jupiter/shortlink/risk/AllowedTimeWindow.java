package com.jupiter.shortlink.risk;

/** A normalized half-open daily interval. Overnight intervals must be split by the authority. */
public record AllowedTimeWindow(int startSecond, int endSecond) {
    public AllowedTimeWindow {
        if (startSecond < 0 || endSecond > 86_400 || startSecond >= endSecond) {
            throw new IllegalArgumentException("Invalid half-open time window");
        }
    }

    public boolean contains(int secondOfDay) {
        return secondOfDay >= startSecond && secondOfDay < endSecond;
    }
}
