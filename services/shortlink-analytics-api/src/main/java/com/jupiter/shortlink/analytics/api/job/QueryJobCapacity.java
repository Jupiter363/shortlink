package com.jupiter.shortlink.analytics.api.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Separate budgets for execution, retained results and replay identities. All are tenant scoped. */
@Component
public record QueryJobCapacity(
        @Value("${analytics.jobs.capacity.tenant-active:2}") int tenantActive,
        @Value("${analytics.jobs.capacity.global-active:8}") int globalActive,
        @Value("${analytics.jobs.capacity.tenant-results:128}") int tenantResults,
        @Value("${analytics.jobs.capacity.global-results:512}") int globalResults,
        @Value("${analytics.jobs.capacity.result-bytes:1073741824}") long resultBytes,
        @Value("${analytics.jobs.capacity.tenant-identities:2048}") int tenantIdentities,
        @Value("${analytics.jobs.capacity.global-identities:16384}") int globalIdentities,
        @Value("${analytics.jobs.capacity.tenant-identity-bytes:16777216}") long tenantIdentityBytes,
        @Value("${analytics.jobs.capacity.global-identity-bytes:134217728}") long globalIdentityBytes) {
    public QueryJobCapacity {
        if (tenantActive < 1 || globalActive < 1 || tenantResults < 1 || globalResults < 1
                || resultBytes < 1 || tenantIdentities < 1 || globalIdentities < 1
                || tenantIdentityBytes < 1 || globalIdentityBytes < 1)
            throw new IllegalArgumentException("Query capacity budgets must be positive");
    }

    public static QueryJobCapacity defaults() {
        return new QueryJobCapacity(2, 8, 128, 512, 1024L * 1024 * 1024,
                2048, 16384, 16L * 1024 * 1024, 128L * 1024 * 1024);
    }
}
