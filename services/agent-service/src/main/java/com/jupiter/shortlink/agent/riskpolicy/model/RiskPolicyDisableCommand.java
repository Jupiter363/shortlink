package com.jupiter.shortlink.agent.riskpolicy.model;

public record RiskPolicyDisableCommand(
        String policyId,
        String gid,
        String executor,
        String reason,
        String traceId
) {
}
