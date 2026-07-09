package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RiskResponseComposeNode {

    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";
    private static final String LLM_EXPLANATION_NODE = "llm_explanation";
    private static final String RESPONSE_COMPOSE_NODE = "response_compose";

    private final String graphName;

    private final String graphVersion;

    private final SecurityRiskSanitizer sanitizer;

    public RiskResponseComposeNode(String graphName, String graphVersion, SecurityRiskSanitizer sanitizer) {
        this.graphName = graphName;
        this.graphVersion = graphVersion;
        this.sanitizer = sanitizer;
    }

    public Map<String, Object> apply(OverAllState state) {
        return compose(
                state.value("riskCards", List.of()),
                state.value("toolExecutions", List.of()),
                state.value("llmDataSource", Map.of())
        );
    }

    public Map<String, Object> compose(List<Object> cards, List<Map<String, Object>> toolExecutions, Map<String, Object> llmDataSource) {
        List<String> nodes = List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE, LLM_EXPLANATION_NODE, RESPONSE_COMPOSE_NODE);
        return Map.of(
                "cards", sanitize(cards),
                "pendingActions", pendingActions(cards),
                "toolCalls", sanitize(toolExecutions),
                "dataSources", sanitize(dataSources(llmDataSource, toolExecutions, nodes)),
                "visitedNodes", nodes
        );
    }

    private List<Object> pendingActions(List<Object> cards) {
        if (hasHighRiskCard(cards)) {
            return List.of(Map.of(
                    "type", "review_security_risk",
                    "title", "Review high risk traffic signal",
                    "status", "pending_confirmation"
            ));
        }
        return List.of();
    }

    private boolean hasHighRiskCard(List<Object> cards) {
        return cards.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(card -> "high".equalsIgnoreCase(String.valueOf(card.get("riskLevel"))));
    }

    private List<Object> dataSources(Map<String, Object> llmDataSource, List<Map<String, Object>> toolExecutions, List<String> nodes) {
        List<Object> dataSources = new ArrayList<>();
        dataSources.add(Map.of(
                "type", "graph",
                "name", graphName,
                "version", graphVersion,
                "nodes", nodes
        ));
        if (llmDataSource != null && !llmDataSource.isEmpty()) {
            dataSources.add(llmDataSource);
        }
        if (toolExecutions != null && !toolExecutions.isEmpty()) {
            dataSources.add(Map.of(
                    "type", "tool",
                    "executions", sanitize(toolExecutions)
            ));
        }
        return dataSources;
    }

    private Object sanitize(Object value) {
        return sanitizer.sanitizeObject(value);
    }
}
