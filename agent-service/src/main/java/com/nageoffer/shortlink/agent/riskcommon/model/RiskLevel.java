package com.nageoffer.shortlink.agent.riskcommon.model;

public enum RiskLevel {

    LOW,
    MEDIUM,
    HIGH;

    public static RiskLevel fromScore(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Risk score must be between 0 and 100");
        }
        if (score >= 70) {
            return HIGH;
        }
        if (score >= 40) {
            return MEDIUM;
        }
        return LOW;
    }
}
