package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record RiskFeatureSnapshot(
        String sourceTool,
        Map<String, Object> arguments,
        Map<String, Object> stats
) {

    public RiskFeatureSnapshot {
        arguments = arguments == null ? Map.of() : new LinkedHashMap<>(arguments);
        stats = stats == null ? Map.of() : new LinkedHashMap<>(stats);
    }
}
