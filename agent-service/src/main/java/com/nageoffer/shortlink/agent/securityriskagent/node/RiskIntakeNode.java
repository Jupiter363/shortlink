package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;

import java.util.List;
import java.util.Map;

public class RiskIntakeNode {

    private static final String INTAKE_NODE = "intake";

    private final String graphName;

    private final String graphVersion;

    public RiskIntakeNode(String graphName, String graphVersion) {
        this.graphName = graphName;
        this.graphVersion = graphVersion;
    }

    public Map<String, Object> apply(OverAllState state) {
        return intake();
    }

    public Map<String, Object> intake() {
        return Map.of(
                "graphName", graphName,
                "graphVersion", graphVersion,
                "visitedNodes", List.of(INTAKE_NODE)
        );
    }
}
