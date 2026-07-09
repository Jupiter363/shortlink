package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record RiskEvidence(Map<String, Object> values) {

    public RiskEvidence {
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
    }
}
