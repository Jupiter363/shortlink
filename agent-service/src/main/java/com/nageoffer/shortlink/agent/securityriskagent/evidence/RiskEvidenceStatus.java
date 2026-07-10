package com.nageoffer.shortlink.agent.securityriskagent.evidence;

public enum RiskEvidenceStatus {

    NOT_REQUESTED,

    AVAILABLE,

    NO_DATA,

    SOURCE_FAILURE;

    public static RiskEvidenceStatus from(Object value, RiskEvidenceStatus fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
