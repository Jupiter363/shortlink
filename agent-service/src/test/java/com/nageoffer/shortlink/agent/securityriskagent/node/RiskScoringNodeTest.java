package com.nageoffer.shortlink.agent.securityriskagent.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoringNodeTest {

    private final RiskScoringNode node = new RiskScoringNode();

    @Test
    void scoreBuildsRiskCardsFromToolExecutions() {
        Map<String, Object> output = node.score(List.of(Map.of(
                "name", "get_group_stats",
                "success", true,
                "arguments", Map.of("gid", "g1"),
                "data", Map.of(
                        "pv", 100,
                        "uv", 80,
                        "topIpStats", List.of(Map.of("ip", "192.168.1.10", "cnt", 45))
                )
        )));

        assertThat(output.get("visitedNodes")).isEqualTo(List.of("intake", "risk_tool_planning", "risk_scoring"));
        assertThat(output.get("riskCards").toString())
                .contains("top_ip_concentration")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10");
    }
}
