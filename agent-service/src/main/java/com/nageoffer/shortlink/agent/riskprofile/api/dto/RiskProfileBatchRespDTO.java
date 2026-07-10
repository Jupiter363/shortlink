package com.nageoffer.shortlink.agent.riskprofile.api.dto;

import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record RiskProfileBatchRespDTO(
        String batchId,
        Instant batchTime,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        RiskProfileBatchStatus status,
        int scannedShortLinks,
        int generatedProfiles,
        int scannedCount,
        int generatedCount,
        int failedCount,
        int analysisJobCount,
        List<RiskProfileBatchFailure> failures,
        LocalDateTime startTime,
        LocalDateTime finishTime
) {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public static RiskProfileBatchRespDTO from(RiskProfileBatch batch) {
        return new RiskProfileBatchRespDTO(
                batch.batchId(),
                batch.windowEnd() == null ? null : batch.windowEnd().atZone(BUSINESS_ZONE).toInstant(),
                batch.windowStart(),
                batch.windowEnd(),
                batch.status(),
                batch.scannedCount(),
                batch.generatedCount(),
                batch.scannedCount(),
                batch.generatedCount(),
                batch.failedCount(),
                batch.analysisJobCount(),
                batch.failures(),
                batch.startTime(),
                batch.finishTime()
        );
    }
}
