package com.nageoffer.shortlink.agent.riskpolicy.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskSensitiveDataGuard;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

public record RiskPolicyActivationCommand(
        String policyId,
        String idempotencyKey,
        RiskPolicyAction action,
        String gid,
        String domain,
        String shortUri,
        String ipHash,
        String policyPayloadJson,
        RiskPolicySource source,
        String executor,
        String reason,
        String traceId,
        String eventId,
        LocalDateTime expireTime
) {

    private static final Pattern IP_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final RiskSensitiveDataGuard SENSITIVE_DATA_GUARD = new RiskSensitiveDataGuard();

    public RiskPolicyActivationCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        ipHash = ipHash == null ? "" : ipHash;
        if (action == RiskPolicyAction.BLOCK_IP && !IP_HASH_PATTERN.matcher(ipHash).matches()) {
            throw new IllegalArgumentException("ipHash must be a lowercase SHA-256 value");
        }
        for (String value : new String[]{
                policyId,
                idempotencyKey,
                gid,
                domain,
                shortUri,
                policyPayloadJson,
                executor,
                reason,
                traceId,
                eventId
        }) {
            SENSITIVE_DATA_GUARD.requireSafe(value);
        }
    }

    public static RiskPolicyActivationCommand shortLink(
            String policyId,
            String idempotencyKey,
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
                idempotencyKey,
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
            String idempotencyKey,
            String gid,
            String ipHash,
            String policyPayloadJson,
            RiskPolicySource source,
            String executor,
            String reason,
            String traceId,
            String eventId
    ) {
        return new RiskPolicyActivationCommand(
                policyId,
                idempotencyKey,
                RiskPolicyAction.BLOCK_IP,
                gid,
                "",
                "",
                ipHash,
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
