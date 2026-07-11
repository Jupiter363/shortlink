package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopIpConcentrationRuleTest {

    private final TopIpConcentrationRule rule = new TopIpConcentrationRule(new SecurityRiskSanitizer());

    @Test
    void evaluatesSafeMaskedIpRowsDirectly() {
        List<RiskSignal> signals = rule.evaluate(execution(List.of(
                safeRow("192.0.*.*", 45),
                safeRow("198.51.*.*", 12)
        )));

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.reasonCode()).isEqualTo("top_ip_concentration");
            assertThat(signal.metrics())
                    .containsEntry("pv", 100L)
                    .containsEntry("topIpCount", 45L)
                    .containsEntry("topIpShare", 0.45D);
            assertThat(signal.evidence())
                    .containsEntry("maskedTopIp", "192.0.*.*")
                    .containsEntry("topIpCount", 45L);
            assertThat(signal.toString()).doesNotContain("192.0.2.44");
        });
    }

    @Test
    void ignoresRawIpRowsEvenWhenTheirCountWouldTriggerTheRule() {
        List<RiskSignal> signals = rule.evaluate(execution(List.of(Map.of(
                "ip", "192.0.2.44",
                "cnt", 90
        ))));

        assertThat(signals).isEmpty();
    }

    @Test
    void ignoresRowsWithoutNonBlankMaskedIp() {
        List<RiskSignal> signals = rule.evaluate(execution(List.of(
                Map.of("ipHash", "a".repeat(64), "cnt", 90),
                Map.of("ipHash", "b".repeat(64), "maskedIp", " ", "cnt", 80)
        )));

        assertThat(signals).isEmpty();
    }

    @Test
    void usesTheHighestCountAmongSafeRowsOnly() {
        List<RiskSignal> signals = rule.evaluate(execution(List.of(
                safeRow("192.0.*.*", 31),
                Map.of("ip", "203.0.113.9", "cnt", 99),
                safeRow("198.51.*.*", 48)
        )));

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.metrics()).containsEntry("topIpCount", 48L);
            assertThat(signal.evidence()).containsEntry("maskedTopIp", "198.51.*.*");
        });
    }

    private Map<String, Object> execution(List<Map<String, Object>> rows) {
        return Map.of(
                "name", "get_group_stats",
                "success", true,
                "arguments", Map.of("gid", "g1"),
                "data", Map.of("pv", 100, "topIpStats", rows)
        );
    }

    private Map<String, Object> safeRow(String maskedIp, long count) {
        return Map.of(
                "ipHash", "a".repeat(64),
                "maskedIp", maskedIp,
                "cnt", count
        );
    }
}
