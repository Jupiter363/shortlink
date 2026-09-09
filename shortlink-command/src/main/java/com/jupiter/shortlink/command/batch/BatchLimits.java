package com.jupiter.shortlink.command.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Hard resource limits; raising them requires a matching capacity review. */
@Component
public record BatchLimits(
        long tenantRows,
        int tenantJobs,
        int validationJobs,
        long validationBytes,
        long maxInputBytes,
        int maxRows,
        int chunkRows,
        int workers,
        long leaseMillis,
        int maxAttempts) {
    public BatchLimits(
            @Value("${shortlink.batch.tenant-rows:1000000}") long tenantRows,
            @Value("${shortlink.batch.tenant-jobs:8}") int tenantJobs,
            @Value("${shortlink.batch.validation-jobs:2}") int validationJobs,
            @Value("${shortlink.batch.validation-bytes:268435456}") long validationBytes,
            @Value("${shortlink.batch.max-input-bytes:67108864}") long maxInputBytes,
            @Value("${shortlink.batch.max-rows:1000000}") int maxRows,
            @Value("${shortlink.batch.chunk-rows:200}") int chunkRows,
            @Value("${shortlink.batch.workers:4}") int workers,
            @Value("${shortlink.batch.lease-millis:30000}") long leaseMillis,
            @Value("${shortlink.batch.max-attempts:8}") int maxAttempts) {
        if (tenantRows < 1
                || tenantJobs < 1
                || tenantJobs > 64
                || validationJobs < 1
                || validationJobs > tenantJobs
                || maxInputBytes < 1024
                || maxInputBytes > 268435456L
                || validationBytes < maxInputBytes
                || maxRows < 50001
                || maxRows > 5000000
                || chunkRows < 1
                || chunkRows > 500
                || workers < 1
                || workers > 32
                || leaseMillis < 5000
                || leaseMillis > 300000
                || maxAttempts < 1
                || maxAttempts > 32)
            throw new IllegalArgumentException("Invalid bounded batch configuration");
        this.tenantRows = tenantRows;
        this.tenantJobs = tenantJobs;
        this.validationJobs = validationJobs;
        this.validationBytes = validationBytes;
        this.maxInputBytes = maxInputBytes;
        this.maxRows = maxRows;
        this.chunkRows = chunkRows;
        this.workers = workers;
        this.leaseMillis = leaseMillis;
        this.maxAttempts = maxAttempts;
    }
}
