package com.nageoffer.shortlink.agent.riskpolicy.service;

import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;

import java.util.Objects;

public record RiskPolicyActivationResult(
        RiskPolicy policy,
        RiskPolicySyncStatus syncStatus
) {

    public RiskPolicyActivationResult {
        policy = Objects.requireNonNull(policy, "policy must not be null");
        syncStatus = Objects.requireNonNull(syncStatus, "syncStatus must not be null");
    }
}
