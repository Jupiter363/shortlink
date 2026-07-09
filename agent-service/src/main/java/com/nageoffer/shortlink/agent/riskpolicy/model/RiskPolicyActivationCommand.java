package com.nageoffer.shortlink.agent.riskpolicy.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;

import java.time.LocalDateTime;

public record RiskPolicyActivationCommand(
        String policyId,
        RiskPolicyAction action,
        String gid,
        String domain,
        String shortUri,
        String rawIp,
        String policyPayloadJson,
        RiskPolicySource source,
        String executor,
        String reason,
        String traceId,
        String eventId,
        LocalDateTime expireTime
) {

    public static RiskPolicyActivationCommand shortLink(
            String policyId,
            RiskPolicyAction action,
            String gid,
            String domain,
            String shortUri,
            String policyPayloadJson,
            RiskPolicySource source,
            String executor,
            String reason,
            String traceId,
            String eventId
    ) {
        return new RiskPolicyActivationCommand(
                policyId,
                action,
                gid,
                domain,
                shortUri,
                "",
                policyPayloadJson,
                source,
                executor,
                reason,
                traceId,
                eventId,
                null
        );
    }

    public static RiskPolicyActivationCommand blockIp(
            String policyId,
            String gid,
            String rawIp,
            String policyPayloadJson,
            RiskPolicySource source,
            String executor,
            String reason,
            String traceId,
            String eventId
    ) {
        return new RiskPolicyActivationCommand(
                policyId,
                RiskPolicyAction.BLOCK_IP,
                gid,
                "",
                "",
                rawIp,
                policyPayloadJson,
                source,
                executor,
                reason,
                traceId,
                eventId,
                null
        );
    }
}
