package com.jupiter.shortlink.contract;

import java.io.Serializable;

/** decisionId is issued with EventIdentity.bind using this record's exact occurredAt. */
public record GatewayRequestEventV1(
        String decisionId,
        int schemaVersion,
        long occurredAt,
        String producerInstanceId,
        RequestSource source,
        DecisionStage stage,
        String method,
        int status,
        String reason,
        String tenantId,
        Long linkId,
        String domainNorm,
        String shortUri,
        Long policyRevision,
        String requestId,
        String traceId)
        implements Serializable {
    public boolean businessDenied() {
        return source == RequestSource.REDIRECT
                && stage == DecisionStage.BUSINESS
                && (status == 403 || status == 429)
                && tenantId != null
                && linkId != null;
    }
}
