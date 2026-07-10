package com.nageoffer.shortlink.agent.riskanalysis.job;

import java.time.LocalDateTime;

public record RiskAnalysisJob(
        String jobId,
        String batchId,
        String gid,
        String graphName,
        String graphVersion,
        RiskAnalysisJobStatus status,
        int attemptCount,
        LocalDateTime nextRetryTime,
        String ownerToken,
        LocalDateTime leaseUntil,
        String sessionId,
        String traceId,
        String errorSummary,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public RiskAnalysisJob {
        jobId = valueOrEmpty(jobId);
        batchId = valueOrEmpty(batchId);
        gid = valueOrEmpty(gid);
        graphName = valueOrEmpty(graphName);
        graphVersion = valueOrEmpty(graphVersion);
        status = status == null ? RiskAnalysisJobStatus.PENDING : status;
        attemptCount = Math.max(0, attemptCount);
        ownerToken = valueOrEmpty(ownerToken);
        sessionId = valueOrEmpty(sessionId);
        traceId = valueOrEmpty(traceId);
        errorSummary = valueOrEmpty(errorSummary);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
