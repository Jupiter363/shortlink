package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.securityriskagent.rule.SecurityRiskCardFactory;

import java.util.List;
import java.util.Map;

public class RiskScoringNode {

    private static final String INTAKE_NODE = "intake";
    private static final String RISK_TOOL_PLANNING_NODE = "risk_tool_planning";
    private static final String RISK_SCORING_NODE = "risk_scoring";

    private final SecurityRiskCardFactory cardFactory;

    public RiskScoringNode() {
        this(new SecurityRiskCardFactory());
    }

    public RiskScoringNode(SecurityRiskCardFactory cardFactory) {
        this.cardFactory = cardFactory;
    }

    public Map<String, Object> apply(OverAllState state) {
        return score(state.value("toolExecutions", List.of()));
    }

    public Map<String, Object> score(List<Map<String, Object>> toolExecutions) {
        List<Object> riskCards = cardFactory.build(toolExecutions);
        return Map.of(
                "riskCards", riskCards,
                "visitedNodes", List.of(INTAKE_NODE, RISK_TOOL_PLANNING_NODE, RISK_SCORING_NODE)
        );
    }
}
