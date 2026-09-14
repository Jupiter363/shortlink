package com.jupiter.shortlink.redirect.membership;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("shortlink.redirect.membership")
public record RouteMembershipProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1000000") long expectedInsertions,
        @DefaultValue("0.0001") double falsePositiveProbability,
        @DefaultValue("0.001") double maximumFalsePositiveProbability,
        @DefaultValue("33554432") long maximumMemoryBytes,
        @DefaultValue("500") int pageSize,
        @DefaultValue("100") int maximumPagesPerRefresh,
        @DefaultValue("250") long refreshMillis,
        @DefaultValue("10000") long refreshBudgetMillis,
        @DefaultValue("") String snapshotPath,
        @DefaultValue("true") boolean kafkaHintsEnabled) {
    public RouteMembershipProperties {
        if (expectedInsertions < 1
                || expectedInsertions > 1_000_000_000L
                || !(falsePositiveProbability > 0 && falsePositiveProbability < 0.1)
                || !(maximumFalsePositiveProbability >= falsePositiveProbability
                        && maximumFalsePositiveProbability < 0.1)
                || maximumMemoryBytes < 1024
                || pageSize < 1
                || pageSize > 1000
                || maximumPagesPerRefresh < 1
                || maximumPagesPerRefresh > 10000
                || refreshMillis < 50
                || refreshMillis > 500
                || refreshBudgetMillis < 1
                || refreshBudgetMillis > 30000)
            throw new IllegalArgumentException("Invalid bounded membership configuration");
        snapshotPath = snapshotPath == null ? "" : snapshotPath;
        // Live/rebuilding filters, serialization buffers and checksum payload copies may coexist.
        if (estimatedFilterBytes(expectedInsertions, falsePositiveProbability) * 5 + 65536
                > maximumMemoryBytes)
            throw new IllegalArgumentException("Membership filters exceed their memory budget");
    }

    public long filterBytes() {
        return estimatedFilterBytes(expectedInsertions, falsePositiveProbability);
    }

    static long estimatedFilterBytes(long count, double fpp) {
        long bits = (long) Math.ceil(-count * Math.log(fpp) / (Math.log(2) * Math.log(2)));
        return Math.addExact((bits + 63) / 64 * 8, 1024);
    }
}
