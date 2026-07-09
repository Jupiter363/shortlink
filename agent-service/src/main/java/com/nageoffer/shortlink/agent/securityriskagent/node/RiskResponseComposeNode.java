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
                state.value("llmDataSource", Map.of()),
                state.value("profileRiskDataSource", Map.of()),
                state.value("activatedPolicies", List.of())
        );
    }

    public Map<String, Object> compose(List<Object> cards, List<Map<String, Object>> toolExecutions, Map<String, Object> llmDataSource) {
        return compose(cards, toolExecutions, llmDataSource, Map.of(), List.of());
    }

    public Map<String, Object> compose(
            List<Object> cards,
            List<Map<String, Object>> toolExecutions,
            Map<String, Object> llmDataSource,
            Map<String, Object> profileRiskDataSource,
            List<Object> activatedPolicies
    ) {
        List<String> nodes = List.of(
                INTAKE_NODE,
                "profile_candidate_load",
                RISK_TOOL_PLANNING_NODE,
                RISK_SCORING_NODE,
                LLM_EXPLANATION_NODE,
                "risk_event_persist",
                "risk_auto_action",
                RESPONSE_COMPOSE_NODE
        );
        return Map.of(
                "cards", sanitize(cards),
                "pendingActions", pendingActions(cards, activatedPolicies),
                "toolCalls", sanitize(toolExecutions),
                "dataSources", sanitize(dataSources(llmDataSource, profileRiskDataSource, activatedPolicies, toolExecutions, nodes)),
                "visitedNodes", nodes
        );
    }

    private List<Object> pendingActions(List<Object> cards, List<Object> activatedPolicies) {
        List<Object> pendingActions = new ArrayList<>();
        if (activatedPolicies != null && !activatedPolicies.isEmpty()) {
            pendingActions.add(Map.of(
                    "type", "auto_limit_rate",
                    "title", "Auto LIMIT_RATE policy activated",
                    "status", "executed",
                    "policies", sanitize(activatedPolicies)
            ));
        }
        if (hasHighRiskCard(cards)) {
            pendingActions.add(Map.of(
                    "type", "review_security_risk",
                    "title", "Review high risk traffic signal",
                    "status", "pending_confirmation"
            ));
        }
        return pendingActions;
    }

    private boolean hasHighRiskCard(List<Object> cards) {
        return cards.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(card -> "high".equalsIgnoreCase(String.valueOf(card.get("riskLevel"))));
    }

    private List<Object> dataSources(
            Map<String, Object> llmDataSource,
            Map<String, Object> profileRiskDataSource,
            List<Object> activatedPolicies,
            List<Map<String, Object>> toolExecutions,
            List<String> nodes
    ) {
        List<Object> dataSources = new ArrayList<>();
        dataSources.add(Map.of(
                "type", "graph",
                "name", graphName,
                "version", graphVersion,
                "nodes", nodes
        ));
        if (profileRiskDataSource != null && !profileRiskDataSource.isEmpty()) {
            dataSources.add(profileRiskDataSource);
        }
        if (llmDataSource != null && !llmDataSource.isEmpty()) {
            dataSources.add(llmDataSource);
        }
        if (activatedPolicies != null && !activatedPolicies.isEmpty()) {
            dataSources.add(Map.of(
                    "type", "risk_policy",
                    "executions", sanitize(activatedPolicies)
            ));
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
