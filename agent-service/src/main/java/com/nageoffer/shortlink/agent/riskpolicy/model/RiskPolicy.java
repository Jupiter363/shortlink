package com.nageoffer.shortlink.agent.riskpolicy.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;

import java.time.LocalDateTime;

public record RiskPolicy(
        String policyId,
        String policyKey,
        RiskPolicyAction action,
        RiskTargetType targetType,
        String gid,
        String domain,
        String shortUri,
        String ipHash,
        String policyPayloadJson,
        RiskPolicyStatus status,
        LocalDateTime effectiveTime,
        LocalDateTime expireTime,
        RiskPolicySource source,
        String traceId,
        String eventId
) {

    public RiskPolicy withStatus(RiskPolicyStatus nextStatus) {
        return new RiskPolicy(
                policyId,
                policyKey,
                action,
                targetType,
                gid,
                domain,
                shortUri,
                ipHash,
                policyPayloadJson,
                nextStatus,
                effectiveTime,
                expireTime,
                source,
                traceId,
                eventId
        );
    }

    public static RiskPolicy shortLinkPolicy(
            String policyId,
            String policyKey,
            RiskPolicyAction action,
            String gid,
            String domain,
            String shortUri,
            String policyPayloadJson,
            RiskPolicySource source,
            String traceId,
            String eventId
    ) {
        return shortLinkPolicy(policyId, policyKey, action, gid, domain, shortUri, policyPayloadJson, source, traceId, eventId, null);
    }

    public static RiskPolicy shortLinkPolicy(
            String policyId,
            String policyKey,
            RiskPolicyAction action,
            String gid,
            String domain,
            String shortUri,
            String policyPayloadJson,
            RiskPolicySource source,
            String traceId,
            String eventId,
            LocalDateTime expireTime
    ) {
        return new RiskPolicy(
                policyId,
                policyKey,
                action,
                RiskTargetType.SHORT_LINK,
                gid,
                domain,
                shortUri,
                "",
                policyPayloadJson,
                RiskPolicyStatus.ACTIVE,
                LocalDateTime.now(),
                expireTime,
                source,
                traceId,
                eventId
        );
    }

    public static RiskPolicy ipPolicy(
            String policyId,
            String policyKey,
            String gid,
            String ipHash,
            String policyPayloadJson,
            RiskPolicySource source,
            String traceId,
            String eventId,
            LocalDateTime expireTime
    ) {
        return new RiskPolicy(
                policyId,
                policyKey,
                RiskPolicyAction.BLOCK_IP,
                RiskTargetType.SHORT_LINK,
                gid,
                "",
                "",
                ipHash,
                policyPayloadJson,
                RiskPolicyStatus.ACTIVE,
                LocalDateTime.now(),
                expireTime,
                source,
                traceId,
                eventId
        );
    }
}
