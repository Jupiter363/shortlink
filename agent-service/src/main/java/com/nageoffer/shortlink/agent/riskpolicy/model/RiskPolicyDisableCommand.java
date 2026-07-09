package com.nageoffer.shortlink.agent.riskpolicy.model;

public record RiskPolicyDisableCommand(
        String policyId,
        String executor,
        String reason,
        String traceId
) {
}
