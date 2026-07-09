package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RiskSignal(
        String riskLevel,
        int riskScore,
        String category,
        String reasonCode,
        String signal,
        String sourceTool,
        Map<String, Object> arguments,
        Map<String, Object> metrics,
        Map<String, Object> thresholds,
        Map<String, Object> evidence,
        List<String> recommendedActions
) {

    public RiskSignal {
        arguments = copy(arguments);
        metrics = copy(metrics);
        thresholds = copy(thresholds);
        evidence = copy(evidence);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }

    public Map<String, Object> toCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "risk_signal");
        card.put("title", "Security risk signal");
        card.put("sourceTool", sourceTool);
        card.put("arguments", arguments);
        card.put("severity", "warning");
        card.put("riskScore", riskScore);
        card.put("riskLevel", riskLevel);
        card.put("summary", summary());
        card.put("metrics", metrics);
        card.put("thresholds", thresholds);
        card.put("evidence", evidence);
        card.put("recommendedActions", recommendedActions);
        return card;
    }

    private Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", category);
        summary.put("reasonCode", reasonCode);
        summary.put("signal", signal);
        return summary;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }
}
