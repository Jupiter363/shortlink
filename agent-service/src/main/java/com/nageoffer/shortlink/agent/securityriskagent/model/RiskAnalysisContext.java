package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RiskAnalysisContext(
        String sessionId,
        String username,
        String message,
        List<Map<String, Object>> toolExecutions,
        List<RiskSignal> riskSignals
) {

    public RiskAnalysisContext {
        toolExecutions = copyListOfMaps(toolExecutions);
        riskSignals = riskSignals == null ? List.of() : List.copyOf(riskSignals);
    }

    private static List<Map<String, Object>> copyListOfMaps(List<Map<String, Object>> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream()
                .map(LinkedHashMap::new)
                .map(map -> (Map<String, Object>) map)
                .toList();
    }
}
