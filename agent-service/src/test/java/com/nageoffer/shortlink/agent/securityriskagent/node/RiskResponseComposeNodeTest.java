package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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

        assertThat(output.get("pendingActions")).isEqualTo(List.of());
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
    void composeDoesNotInferPendingActionFromHighRiskCard() {
        Map<String, Object> output = node.compose(
                List.of(Map.of(
                        "riskLevel", "high",
                        "arguments", Map.of("fullShortUrl", "riskLevel=high")
                )),
                List.of(),
                Map.of()
        );

        assertThat(output.get("pendingActions")).isEqualTo(List.of());
        assertThat(output.get("cards").toString()).contains("riskLevel=high");
    }

    @Test
    void composeReturnsOnlyPersistedPendingActionViews() {
        AgentPendingActionView pendingAction = pendingAction("action-1");

        Map<String, Object> output = node.compose(
                List.of(Map.of("riskLevel", "high")),
                List.of(pendingAction),
                List.of(),
                Map.of(),
                Map.of(),
                List.of(Map.of("action", "LIMIT_RATE", "policyId", "policy-1"))
        );

        assertThat(output.get("pendingActions")).isEqualTo(List.of(pendingAction));
        assertThat(output.get("pendingActions").toString())
                .doesNotContain("LIMIT_RATE", "policy-1", "payloadJson", "executionToken");
        assertThat(output.get("dataSources").toString())
                .contains("risk_policy", "LIMIT_RATE", "policy-1");
        assertThat(output.get("visitedNodes").toString())
                .contains("risk_event_persist", "risk_action_proposal", "risk_auto_action");
    }

    private AgentPendingActionView pendingAction(String actionId) {
        return new AgentPendingActionView(
                actionId,
                "security-risk",
                "risk.disable-short-link",
                AgentActionStatus.PENDING,
                "g1",
                "SHORT_LINK",
                Map.of("domain", "nurl.ink", "shortUri", "abc123"),
                "Disable high-risk short link",
                "High-confidence batch profile",
                Map.of("riskScore", 95, "eventId", "event-1"),
                0,
                0,
                LocalDateTime.of(2026, 7, 12, 10, 0),
                null,
                null,
                Map.of(),
                null
        );
    }
}
