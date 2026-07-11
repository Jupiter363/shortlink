package com.nageoffer.shortlink.agent.riskpolicy.action;

public record RiskPolicyConfirmedActionResult(
        String policyId,
        String policyKey,
        long policyVersion,
        String policyStatus,
        String syncStatus
) {
}
