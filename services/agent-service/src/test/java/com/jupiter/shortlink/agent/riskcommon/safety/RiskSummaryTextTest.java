package com.jupiter.shortlink.agent.riskcommon.safety;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RiskSummaryTextTest {

    @Test
    void preservesShortAndExactlyFullSummaries() {
        assertThat(RiskSummaryText.forPersistence(null)).isEmpty();
        assertThat(RiskSummaryText.forPersistence("风险提示：流量集中 🔍")).isEqualTo("风险提示：流量集中 🔍");
        String exactLimit = "🚀".repeat(2048);
        assertThat(RiskSummaryText.forPersistence(exactLimit)).isEqualTo(exactLimit);
    }

    @Test
    void truncatesAtUnicodeCodePointBoundaryAndAddsAnExplicitMarker() {
        String marker = RiskSummaryText.OMISSION_MARKER;
        int prefixCodePoints = 2048 - marker.codePointCount(0, marker.length());
        String prefix = "析".repeat(prefixCodePoints - 1) + "🔍";
        String original = prefix + "🚀".repeat(100);

        String summary = RiskSummaryText.forPersistence(original);

        assertThat(summary).isEqualTo(prefix + marker);
        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(2048);
        assertThat(new String(summary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(summary);
        assertThat(RiskSummaryText.forPersistence(summary)).isEqualTo(summary);
        assertThat(original).endsWith("🚀".repeat(100));
    }
}
