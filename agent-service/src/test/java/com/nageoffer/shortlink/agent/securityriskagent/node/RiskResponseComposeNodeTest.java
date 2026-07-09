package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskResponseComposeNodeTest {

    private final RiskResponseComposeNode node = new RiskResponseComposeNode(
            "security-risk-graph",
            "v1",
            new SecurityRiskSanitizer()
    );

    @Test
    void composeSanitizesCardsToolCallsAndDataSources() {
        Map<String, Object> output = node.compose(
                List.of(Map.of(
                        "riskLevel", "high",
                        "evidence", Map.of("ip", "192.168.1.10", "user", "visitor-001")
                )),
                List.of(Map.of(
                        "name", "get_group_access_records",
                        "success", true,
                        "data", Map.of("ip", "10.0.0.9", "username", "admin")
                )),
                Map.of("type", "llm", "provider", "deepseek")
        );

        assertThat(output.get("pendingActions").toString()).contains("review_security_risk");
        assertThat(output.toString())
                .contains("security-risk-graph")
                .contains("192.168.*.*")
                .contains("10.0.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-001")
                .doesNotContain("admin");
    }

    @Test
    void composeDoesNotCreatePendingActionFromStringifiedMediumRiskContent() {
        Map<String, Object> output = node.compose(
                List.of(Map.of(
                        "riskLevel", "medium",
                        "arguments", Map.of("fullShortUrl", "riskLevel=high")
                )),
                List.of(),
                Map.of()
        );

        assertThat(output.get("pendingActions")).isEqualTo(List.of());
        assertThat(output.get("cards").toString()).contains("riskLevel=high");
    }
}
