package com.nageoffer.shortlink.agent.riskpolicy.action;

import java.util.Objects;

public record RiskPolicyConfirmedActionCommand(
        String actionId,
        String policyId,
        String idempotencyKey,
        RiskPolicyActionPayloadV1 payload,
        String confirmedBy,
        String confirmationNote,
        String traceId,
        String sessionId
) {

    public RiskPolicyConfirmedActionCommand {
        actionId = requireText(actionId, "actionId");
        policyId = requireText(policyId, "policyId");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        payload = Objects.requireNonNull(payload, "payload must not be null");
        confirmedBy = requireText(confirmedBy, "confirmedBy");
        confirmationNote = valueOrEmpty(confirmationNote);
        traceId = valueOrEmpty(traceId);
        sessionId = valueOrEmpty(sessionId);
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
