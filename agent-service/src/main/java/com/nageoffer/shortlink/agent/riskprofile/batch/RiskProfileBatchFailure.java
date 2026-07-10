package com.nageoffer.shortlink.agent.riskprofile.batch;

public record RiskProfileBatchFailure(
        String targetKey,
        String errorCode,
        String message
) {

    public RiskProfileBatchFailure {
        targetKey = valueOrEmpty(targetKey);
        errorCode = valueOrEmpty(errorCode);
        message = valueOrEmpty(message);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
