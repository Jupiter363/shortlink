package com.nageoffer.shortlink.agent.securityriskagent.node;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskIntakeNodeTest {

    @Test
    void intakeInitializesGraphIdentityAndVisitedNodes() {
        RiskIntakeNode node = new RiskIntakeNode("security-risk-graph", "v1");

        assertThat(node.intake())
                .containsEntry("graphName", "security-risk-graph")
                .containsEntry("graphVersion", "v1")
                .containsEntry("visitedNodes", List.of("intake"));
    }
}
