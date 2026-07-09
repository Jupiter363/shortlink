package com.nageoffer.shortlink.agent.riskcommon.model;

public enum RiskPolicyAction {
    DISABLE_SHORT_LINK(true),
    BLOCK_IP(true),
    LIMIT_RATE(false),
    LIMIT_TIME_WINDOW(true);

    private final boolean requiresManualReview;

    RiskPolicyAction(boolean requiresManualReview) {
        this.requiresManualReview = requiresManualReview;
    }

    public boolean requiresManualReview() {
        return requiresManualReview;
    }
}
