package com.nageoffer.shortlink.agent.riskpolicy.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;

import java.time.LocalDateTime;
import java.util.Objects;

public record EffectiveRiskPolicy(
        Long id,
        String policyKey,
        String policyId,
        long policyVersion,
        String gid,
        RiskPolicyAction action,
        RiskPolicyDesiredState desiredState,
        String policyPayloadJson,
        String redisValueJson,
        LocalDateTime effectiveTime,
        LocalDateTime expireTime,
        RiskPolicySyncStatus syncStatus,
        String lastOutboxId,
        String traceId,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {

    public EffectiveRiskPolicy {
        policyKey = requireText(policyKey, "policyKey");
        policyId = requireText(policyId, "policyId");
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }
        gid = valueOrEmpty(gid);
        action = Objects.requireNonNull(action, "action must not be null");
        desiredState = Objects.requireNonNull(desiredState, "desiredState must not be null");
        policyPayloadJson = valueOrEmpty(policyPayloadJson);
        redisValueJson = valueOrEmpty(redisValueJson);
        effectiveTime = Objects.requireNonNull(effectiveTime, "effectiveTime must not be null");
        syncStatus = Objects.requireNonNull(syncStatus, "syncStatus must not be null");
        lastOutboxId = valueOrEmpty(lastOutboxId);
        traceId = valueOrEmpty(traceId);
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
