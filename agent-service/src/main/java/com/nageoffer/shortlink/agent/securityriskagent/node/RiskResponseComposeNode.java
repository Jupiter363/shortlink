package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RiskResponseComposeNode {

    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";
    private static final String LLM_EXPLANATION_NODE = "llm_explanation";
    private static final String RISK_ACTION_PROPOSAL_NODE = "risk_action_proposal";
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
                state.value("pendingActionViews", List.of()),
                state.value("toolExecutions", List.of()),
                state.value("llmDataSource", Map.of()),
                state.value("profileRiskDataSource", Map.of()),
                state.value("activatedPolicies", List.of())
        );
    }

    public Map<String, Object> compose(List<Object> cards, List<Map<String, Object>> toolExecutions, Map<String, Object> llmDataSource) {
        return compose(cards, List.of(), toolExecutions, llmDataSource, Map.of(), List.of());
    }

    public Map<String, Object> compose(
            List<Object> cards,
            List<Map<String, Object>> toolExecutions,
            Map<String, Object> llmDataSource,
            Map<String, Object> profileRiskDataSource,
            List<Object> activatedPolicies
    ) {
        return compose(
                cards,
                List.of(),
                toolExecutions,
                llmDataSource,
                profileRiskDataSource,
                activatedPolicies
        );
    }

    public Map<String, Object> compose(
            List<Object> cards,
            List<AgentPendingActionView> pendingActionViews,
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
                RISK_ACTION_PROPOSAL_NODE,
                "risk_auto_action",
                RESPONSE_COMPOSE_NODE
        );
        return Map.of(
                "cards", sanitize(cards),
                "pendingActions", pendingActionViews == null
                        ? List.of()
                        : List.copyOf(pendingActionViews),
                "toolCalls", sanitize(toolExecutions),
                "dataSources", sanitize(dataSources(llmDataSource, profileRiskDataSource, activatedPolicies, toolExecutions, nodes)),
                "visitedNodes", nodes
        );
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
