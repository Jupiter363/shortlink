package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import java.time.LocalDateTime;
import java.util.Objects;

public record RiskPolicySyncOutbox(
        Long id,
        String outboxId,
        String policyKey,
        String policyId,
        long policyVersion,
        RiskPolicySyncOperation operation,
        String redisValueJson,
        String expectedRedisValue,
        RiskPolicySyncOutboxStatus status,
        int attemptCount,
        LocalDateTime nextRetryTime,
        String ownerToken,
        LocalDateTime leaseUntil,
        String lastError,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public RiskPolicySyncOutbox {
        outboxId = requireText(outboxId, "outboxId");
        policyKey = requireText(policyKey, "policyKey");
        policyId = requireText(policyId, "policyId");
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        operation = Objects.requireNonNull(operation, "operation must not be null");
        redisValueJson = valueOrEmpty(redisValueJson);
        expectedRedisValue = valueOrEmpty(expectedRedisValue);
        status = Objects.requireNonNull(status, "status must not be null");
        attemptCount = Math.max(0, attemptCount);
        ownerToken = valueOrEmpty(ownerToken);
        lastError = valueOrEmpty(lastError);
    }

    public RiskPolicySyncOutbox withOutboxId(String replacementOutboxId) {
        return new RiskPolicySyncOutbox(
                id,
                replacementOutboxId,
                policyKey,
                policyId,
                policyVersion,
                operation,
                redisValueJson,
                expectedRedisValue,
                status,
                attemptCount,
                nextRetryTime,
                ownerToken,
                leaseUntil,
                lastError,
                createTime,
                updateTime
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
