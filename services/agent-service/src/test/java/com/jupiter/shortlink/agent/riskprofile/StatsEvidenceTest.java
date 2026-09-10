package com.jupiter.shortlink.agent.riskprofile;

import static org.assertj.core.api.Assertions.*;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;

import org.junit.jupiter.api.Test;

import java.util.*;

class StatsEvidenceTest {

    @Test
    void approximateUvOnlyDisablesRulesWhichConsumeUvAndDoesNotDisableExactPvRules() {
        var meta = StatsTestFixtures.meta();
        meta.put(
                "approximation",
                Map.of(
                        "pv",
                        Map.of("type", "EXACT"),
                        "uv",
                        Map.of("type", "APPROXIMATE"),
                        "peakHourShare",
                        Map.of("type", "EXACT"),
                        "topIpShare",
                        Map.of("type", "APPROXIMATE")));
        var evidence = new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION);
        assertThat(evidence.permitsAutomaticAction(StatsTestFixtures.NOW)).isTrue();
        assertThat(
                        evidence.supportsAutomaticReason(
                                com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode
                                        .TRAFFIC_SPIKE))
                .isTrue();
        assertThat(
                        evidence.supportsAutomaticReason(
                                com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode
                                        .PEAK_HOUR_BURST))
                .isTrue();
        assertThat(
                        evidence.supportsAutomaticReason(
                                com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode
                                        .HIGH_REPEAT_VISIT))
                .isFalse();
        assertThat(
                        evidence.supportsAutomaticReason(
                                com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode
                                        .IP_CONCENTRATION))
                .isFalse();
    }

    @Test
    void rejectsUnavailableEmptyAndUnknownEvidenceWithoutInventingZero() {
        assertThat(StatsEvidence.usable(Map.of("pv", 0))).isFalse();
        assertThat(
                        StatsEvidence.usable(
                                Map.of(
                                        "meta",
                                        StatsTestFixtures.meta(),
                                        "metrics",
                                        Map.of(),
                                        "items",
                                        List.of())))
                .isFalse();
        assertThatThrownBy(() -> StatsEvidence.number(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatsEvidence.number("9223372036854775808"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatsEvidence.number("1.5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(StatsEvidence.number(3_000_000_000L)).isEqualTo(3_000_000_000L);
    }

    @Test
    void evidenceIsDeeplyImmutableAndDeadlineUsesSourceTime() {
        Map<String, Object> metadata = StatsTestFixtures.meta();
        var evidence = new StatsEvidence("1001", 99, metadata, StatsEvidence.CURRENT_RULE_VERSION);
        metadata.put("effectiveEnd", 0);
        assertThat(evidence.executeBefore()).isEqualTo(StatsTestFixtures.NOW + 120_000);
        assertThat(evidence.permitsAutomaticAction(StatsTestFixtures.NOW)).isTrue();
        assertThat(evidence.permitsAutomaticAction(StatsTestFixtures.NOW + 120_001)).isFalse();
        assertThatThrownBy(() -> evidence.meta().put("freshness", "FRESH"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void qualityProvisionalAndMixedCutDisableAutomaticUse() {
        for (Map.Entry<String, Object> changed :
                Map.<String, Object>of(
                                "freshness",
                                "STALE",
                                "collectionQuality",
                                Map.of("status", "UNKNOWN"))
                        .entrySet()) {
            var metadata = StatsTestFixtures.meta();
            metadata.put(changed.getKey(), changed.getValue());
            assertThat(
                            new StatsEvidence(
                                            "1001",
                                            99,
                                            metadata,
                                            StatsEvidence.CURRENT_RULE_VERSION)
                                    .permitsAutomaticAction(StatsTestFixtures.NOW))
                    .isFalse();
        }
        var changed = StatsTestFixtures.meta();
        changed.put("sourceCut", Map.of("click", Map.of("0", 124)));
        assertThatThrownBy(
                        () -> StatsEvidence.requireSameSnapshot(StatsTestFixtures.meta(), changed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realTimeRuleAllowsProvisionalEvidenceOnlyWhenOtherQualityRequirementsHold() {
        var meta = StatsTestFixtures.meta();
        meta.put("provisional", true);
        assertThat(
                        new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION)
                                .permitsAutomaticAction(StatsTestFixtures.NOW))
                .isTrue();
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        assertThat(
                        new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION)
                                .permitsAutomaticAction(StatsTestFixtures.NOW))
                .isFalse();
    }
}
