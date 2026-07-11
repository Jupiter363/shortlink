package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import com.nageoffer.shortlink.agent.securityriskagent.model.SecurityRiskAssessment;
import com.nageoffer.shortlink.agent.securityriskagent.rule.SecurityRiskCardFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                        "topIpStats", List.of(Map.of(
                                "ipHash", "a".repeat(64), "maskedIp", "192.168.*.*", "cnt", 45
                        ))
                )
        )));

        assertThat(output.get("visitedNodes")).isEqualTo(List.of("intake", "risk_tool_planning", "risk_scoring"));
        assertThat((List<?>) output.get("riskSignals"))
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item).isInstanceOf(RiskSignal.class));
        assertThat(output.get("riskCards").toString())
                .contains("top_ip_concentration")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10");
    }

    @Test
    void profileCardsDoNotCreateRiskSignals() {
        SecurityRiskCardFactory cardFactory = mock(SecurityRiskCardFactory.class);
        List<Map<String, Object>> executions = List.of(Map.of("name", "get_group_stats"));
        RiskSignal signal = signal();
        Map<String, Object> ruleCard = signal.toCard();
        when(cardFactory.assess(executions)).thenReturn(new SecurityRiskAssessment(List.of(signal), List.of(ruleCard)));
        RiskScoringNode scoringNode = new RiskScoringNode(cardFactory);
        ProfileRiskAnalysisContext profileContext = new ProfileRiskAnalysisContext(
                "gid-001",
                null,
                List.of(profile())
        );

        Map<String, Object> output = scoringNode.score(executions, profileContext);

        @SuppressWarnings("unchecked")
        List<RiskSignal> riskSignals = (List<RiskSignal>) output.get("riskSignals");
        @SuppressWarnings("unchecked")
        List<Object> riskCards = (List<Object>) output.get("riskCards");

        assertThat(riskSignals).containsExactly(signal);
        assertThat(riskCards)
                .hasSize(2)
                .first()
                .asString()
                .contains("risk_profile_short_link");
        assertThat(riskCards).endsWith(ruleCard);
        verify(cardFactory, times(1)).assess(executions);
    }

    private RiskSignal signal() {
        return new RiskSignal(
                "high",
                78,
                "traffic_anomaly",
                "top_ip_concentration",
                "Concentrated traffic source",
                "get_group_stats",
                Map.of("gid", "gid-001"),
                Map.of("topIpShare", 0.45D),
                Map.of("topIpShare", 0.4D),
                Map.of("maskedTopIp", "192.168.*.*"),
                List.of("Review traffic source")
        );
    }

    private ShortLinkRiskProfile profile() {
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        return new ShortLinkRiskProfile(
                "gid-001",
                "nurl.ink",
                "profile001",
                "nurl.ink/profile001",
                endTime.minusHours(2),
                endTime,
                new ShortLinkRiskMetrics(600, 50, 900, 300, 2100, 1200, 8.0, 0.82, 0.78, 0.50, 0.65, 0.60, 12.0, 0.74, 0.88),
                85,
                85,
                RiskLevel.HIGH,
                Set.of(RiskReasonCode.IP_CONCENTRATION),
                RiskWatchStatus.NONE,
                List.of(),
                ""
        );
    }
}
