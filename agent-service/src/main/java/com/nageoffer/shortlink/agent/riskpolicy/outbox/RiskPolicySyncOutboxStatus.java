package com.nageoffer.shortlink.agent.riskpolicy.outbox;

public enum RiskPolicySyncOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    SKIPPED,
    DEAD
}
