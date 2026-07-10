package com.nageoffer.shortlink.agent.securityriskagent.model;

public record RiskProfileTargetRef(String domain, String shortUri) {

    public RiskProfileTargetRef {
        domain = requireText(domain, "domain");
        shortUri = requireText(shortUri, "shortUri");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
