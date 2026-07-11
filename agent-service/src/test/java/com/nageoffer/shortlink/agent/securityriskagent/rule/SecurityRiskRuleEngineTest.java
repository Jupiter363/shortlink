package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRiskRuleEngineTest {

    private final SecurityRiskRuleEngine ruleEngine = SecurityRiskRuleEngine.defaultEngine();

    @Test
    void evaluateCreatesTopIpConcentrationSignalWithMaskedEvidence() {
        List<RiskSignal> signals = ruleEngine.evaluate(List.of(statsExecution(Map.of(
                "pv", 100,
                "uv", 80,
                "uip", 20,
                "topIpStats", List.of(Map.of(
                        "ipHash", "a".repeat(64), "maskedIp", "192.168.*.*", "cnt", 45
                ))
        ))));

        RiskSignal signal = firstSignal(signals, "top_ip_concentration");

        assertThat(signal.riskLevel()).isEqualTo("high");
        assertThat(signal.riskScore()).isEqualTo(78);
        assertThat(signal.sourceTool()).isEqualTo("get_group_stats");
        assertThat(signal.metrics())
                .containsEntry("pv", 100L)
                .containsEntry("topIpCount", 45L)
                .containsEntry("topIpShare", 0.45D);
        assertThat(signal.evidence())
                .containsEntry("maskedTopIp", "192.168.*.*")
                .doesNotContainValue("192.168.1.10");
    }

    @Test
    void evaluateCreatesHighRepeatVisitsSignal() {
        List<RiskSignal> signals = ruleEngine.evaluate(List.of(statsExecution(Map.of(
                "pv", 120,
                "uv", 20,
                "uip", 18,
                "topIpStats", List.of()
        ))));

        RiskSignal signal = firstSignal(signals, "high_repeat_visits");

        assertThat(signal.riskLevel()).isEqualTo("medium");
        assertThat(signal.riskScore()).isEqualTo(68);
        assertThat(signal.metrics())
                .containsEntry("pv", 120L)
                .containsEntry("uv", 20L)
                .containsEntry("pvPerUv", 6.0D);
    }

    @Test
    void evaluateCreatesHourBurstSignal() {
        List<RiskSignal> signals = ruleEngine.evaluate(List.of(statsExecution(Map.of(
                "pv", 100,
                "uv", 90,
                "uip", 80,
                "hourStats", List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 70, 30),
                "topIpStats", List.of()
        ))));

        RiskSignal signal = firstSignal(signals, "hour_burst");

        assertThat(signal.riskLevel()).isEqualTo("medium");
        assertThat(signal.riskScore()).isEqualTo(64);
        assertThat(signal.metrics())
                .containsEntry("peakHour", 22L)
                .containsEntry("peakHourShare", 0.7D);
    }

    @Test
    void evaluateSkipsFailedAndNonStatsToolExecutions() {
        List<RiskSignal> signals = ruleEngine.evaluate(List.of(
                Map.of("name", "get_group_access_records", "success", true, "data", Map.of("pv", 100)),
                Map.of("name", "get_group_stats", "success", false, "data", Map.of("pv", 100))
        ));

        assertThat(signals).isEmpty();
    }

    private Map<String, Object> statsExecution(Map<String, Object> data) {
        return Map.of(
                "name", "get_group_stats",
                "success", true,
                "arguments", Map.of("gid", "g1", "startDate", "2026-07-01", "endDate", "2026-07-07"),
                "data", data
        );
    }

    private RiskSignal firstSignal(List<RiskSignal> signals, String reasonCode) {
        return signals.stream()
                .filter(signal -> reasonCode.equals(signal.reasonCode()))
                .findFirst()
                .orElseThrow();
    }
}
