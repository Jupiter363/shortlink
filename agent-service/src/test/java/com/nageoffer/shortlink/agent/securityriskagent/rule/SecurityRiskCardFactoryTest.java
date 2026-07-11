package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import com.nageoffer.shortlink.agent.securityriskagent.model.SecurityRiskAssessment;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityRiskCardFactoryTest {

    private final SecurityRiskCardFactory factory = new SecurityRiskCardFactory();

    @Test
    void assessKeepsSignalsAndCardsFromTheSameRuleEvaluation() {
        SecurityRiskRuleEngine ruleEngine = mock(SecurityRiskRuleEngine.class);
        List<Map<String, Object>> executions = List.of(statsExecution(Map.of("pv", 100)));
        RiskSignal signal = signal("top_ip_concentration");
        when(ruleEngine.evaluate(executions)).thenReturn(List.of(signal));
        SecurityRiskCardFactory cardFactory = new SecurityRiskCardFactory(new SecurityRiskSanitizer(), ruleEngine);

        SecurityRiskAssessment assessment = cardFactory.assess(executions);

        assertThat(assessment.signals())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item).isInstanceOf(RiskSignal.class));
        assertThat(assessment.cards())
                .isNotEmpty()
                .containsExactly(signal.toCard());
        verify(ruleEngine, times(1)).evaluate(executions);
    }

    @Test
    void buildDelegatesToAssessmentCards() {
        List<Map<String, Object>> executions = List.of(statsExecution(Map.of(
                "pv", 100,
                "uv", 80,
                "uip", 20,
                "topIpStats", List.of(Map.of("ip", "192.168.1.10", "cnt", 45))
        )));
        SecurityRiskAssessment expected = new SecurityRiskAssessment(
                List.of(signal("top_ip_concentration")),
                List.of(Map.of("type", "expected-card"))
        );
        SecurityRiskCardFactory cardFactory = spy(factory);
        doReturn(expected).when(cardFactory).assess(executions);

        assertThat(cardFactory.build(executions)).isSameAs(expected.cards());
        verify(cardFactory, times(1)).assess(executions);
    }

    @Test
    void assessmentDefensivelyCopiesCollections() {
        List<RiskSignal> signals = new ArrayList<>(List.of(signal("top_ip_concentration")));
        List<Object> cards = new ArrayList<>(List.of(Map.of("type", "risk_signal")));

        SecurityRiskAssessment assessment = new SecurityRiskAssessment(signals, cards);
        signals.clear();
        cards.clear();

        assertThat(assessment.signals()).hasSize(1);
        assertThat(assessment.cards()).hasSize(1);
        assertThatThrownBy(() -> assessment.signals().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> assessment.cards().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new SecurityRiskAssessment(null, null).signals()).isEmpty();
        assertThat(new SecurityRiskAssessment(null, null).cards()).isEmpty();
    }

    @Test
    void buildCreatesTopIpConcentrationRiskCardWithMaskedEvidence() {
        List<Object> cards = factory.build(List.of(statsExecution(Map.of(
                "pv", 100,
                "uv", 80,
                "uip", 20,
                "topIpStats", List.of(
                        Map.of("ip", "192.168.1.10", "cnt", 45),
                        Map.of("ip", "10.0.0.8", "cnt", 10)
                )
        ))));

        Map<String, Object> card = firstCard(cards, "top_ip_concentration");

        assertThat(card)
                .containsEntry("type", "risk_signal")
                .containsEntry("severity", "warning")
                .containsEntry("riskLevel", "high");
        assertThat(card.get("riskScore")).isEqualTo(78);
        assertThat(map(card.get("metrics")))
                .containsEntry("pv", 100L)
                .containsEntry("topIpCount", 45L)
                .containsEntry("topIpShare", 0.45D);
        assertThat(map(card.get("evidence")))
                .containsEntry("maskedTopIp", "192.168.*.*")
                .doesNotContainValue("192.168.1.10");
    }

    @Test
    void buildCreatesHighRepeatVisitsCardWhenPvUvRatioIsHigh() {
        List<Object> cards = factory.build(List.of(statsExecution(Map.of(
                "pv", 120,
                "uv", 20,
                "uip", 18,
                "topIpStats", List.of()
        ))));

        Map<String, Object> card = firstCard(cards, "high_repeat_visits");

        assertThat(card.get("riskScore")).isEqualTo(68);
        assertThat(card.get("riskLevel")).isEqualTo("medium");
        assertThat(map(card.get("metrics")))
                .containsEntry("pv", 120L)
                .containsEntry("uv", 20L)
                .containsEntry("pvPerUv", 6.0D);
    }

    @Test
    void buildCreatesHourBurstCardWhenPeakHourShareIsHigh() {
        List<Object> cards = factory.build(List.of(statsExecution(Map.of(
                "pv", 100,
                "uv", 90,
                "uip", 80,
                "hourStats", List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 70, 30),
                "topIpStats", List.of()
        ))));

        Map<String, Object> card = firstCard(cards, "hour_burst");

        assertThat(card.get("riskScore")).isEqualTo(64);
        assertThat(card.get("riskLevel")).isEqualTo("medium");
        assertThat(map(card.get("metrics")))
                .containsEntry("peakHour", 22L)
                .containsEntry("peakHourShare", 0.7D);
    }

    @Test
    void sanitizeForPromptRemovesUsersAndMasksIpRecursively() {
        Object sanitized = factory.sanitizeForPrompt(List.of(Map.of(
                "ip", "172.16.1.2",
                "user", "visitor-001",
                "nested", Map.of(
                        "ip", "10.0.0.9",
                        "user", "visitor-002"
                )
        )));

        String text = sanitized.toString();

        assertThat(text)
                .contains("172.16.*.*")
                .contains("10.0.*.*")
                .doesNotContain("172.16.1.2")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-001")
                .doesNotContain("visitor-002");
    }

    @Test
    void sanitizeForPromptUsesSharedSafetyRulesForIdentityAndSecrets() {
        Object sanitized = factory.sanitizeForPrompt(Map.of(
                "username", "admin",
                "uid", "u-001",
                "token", "internal-token",
                "password", "db-password",
                "message", "token=abc password:secret jdbc:mysql://127.0.0.1:3306/shortlink?user=root"
        ));

        String text = sanitized.toString();

        assertThat(text)
                .contains("token=***")
                .contains("password:***")
                .contains("jdbc:***")
                .doesNotContain("admin")
                .doesNotContain("u-001")
                .doesNotContain("internal-token")
                .doesNotContain("db-password")
                .doesNotContain("abc")
                .doesNotContain("secret")
                .doesNotContain("127.0.0.1")
                .doesNotContain("shortlink")
                .doesNotContain("root");
    }

    @Test
    void sanitizeTextMasksInlineIpAndUserIdentifiers() {
        String sanitized = factory.sanitizeText("focus ip=192.168.1.10 user=visitor-001 username:admin");

        assertThat(sanitized)
                .contains("192.168.*.*")
                .contains("user=***")
                .contains("username:***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("admin");
    }

    private Map<String, Object> statsExecution(Map<String, Object> data) {
        return Map.of(
                "name", "get_group_stats",
                "success", true,
                "arguments", Map.of("gid", "g1", "startDate", "2026-07-01", "endDate", "2026-07-07"),
                "data", data
        );
    }

    private RiskSignal signal(String reasonCode) {
        return new RiskSignal(
                "high",
                78,
                "traffic_anomaly",
                reasonCode,
                "Concentrated traffic source",
                "get_group_stats",
                Map.of("gid", "g1"),
                Map.of("topIpShare", 0.45D),
                Map.of("topIpShare", 0.4D),
                Map.of("maskedTopIp", "192.168.*.*"),
                List.of("Review traffic source")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCard(List<Object> cards, String reasonCode) {
        return (Map<String, Object>) cards.stream()
                .map(item -> (Map<String, Object>) item)
                .filter(item -> reasonCode.equals(map(item.get("summary")).get("reasonCode")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
