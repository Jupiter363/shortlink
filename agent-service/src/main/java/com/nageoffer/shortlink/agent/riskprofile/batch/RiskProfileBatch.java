package com.nageoffer.shortlink.agent.riskprofile.batch;

import java.time.LocalDateTime;
import java.util.List;

public record RiskProfileBatch(
        String batchId,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        RiskProfileBatchStatus status,
        String ownerToken,
        LocalDateTime leaseUntil,
        int scannedCount,
        int generatedCount,
        int failedCount,
        int analysisJobCount,
        List<RiskProfileBatchFailure> failures,
        LocalDateTime startTime,
        LocalDateTime finishTime
) {

    public RiskProfileBatch {
        batchId = valueOrEmpty(batchId);
        status = status == null ? RiskProfileBatchStatus.RUNNING : status;
        ownerToken = valueOrEmpty(ownerToken);
        scannedCount = Math.max(0, scannedCount);
        generatedCount = Math.max(0, generatedCount);
        failedCount = Math.max(0, failedCount);
        analysisJobCount = Math.max(0, analysisJobCount);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
