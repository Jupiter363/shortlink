package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskprofile.detector.ShortLinkRiskDetector;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskSourceStats;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShortLinkRiskDetectorTest {

    @Test
    void scoresHighRiskWhenTrafficSpikeAndIpConcentrationAndRepeatVisitAreStrong() {
        ShortLinkRiskSourceStats stats = ShortLinkRiskSourceStats.builder()
                .gid("gid-001")
                .domain("nurl.ink")
                .shortUri("abc123")
                .fullShortUrl("nurl.ink/abc123")
                .pv2h(600)
                .uv2h(50)
                .pv24h(900)
                .uv24h(300)
                .pv7d(2100)
                .uv7d(1200)
                .topIpShare(0.82)
                .topVisitorShare(0.78)
                .topRegionShare(0.50)
                .topDeviceShare(0.65)
                .topBrowserShare(0.60)
                .peakHourShare(0.74)
                .repeatVisitRatio(0.88)
                .build();

        ShortLinkRiskProfile profile = new ShortLinkRiskDetector().detect(stats);

        assertThat(profile.riskScore()).isBetween(80, 100);
        assertThat(profile.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(profile.reasonCodes()).contains(
                RiskReasonCode.TRAFFIC_SPIKE,
                RiskReasonCode.IP_CONCENTRATION,
                RiskReasonCode.HIGH_REPEAT_VISIT,
                RiskReasonCode.PEAK_HOUR_BURST
        );
    }

    @Test
    void normalTrafficScoresLowRisk() {
        ShortLinkRiskSourceStats stats = ShortLinkRiskSourceStats.builder()
                .gid("gid-001")
                .domain("nurl.ink")
                .shortUri("normal")
                .fullShortUrl("nurl.ink/normal")
                .pv2h(20)
                .uv2h(18)
                .pv24h(260)
                .uv24h(220)
                .pv7d(1800)
                .uv7d(1500)
                .topIpShare(0.08)
                .topVisitorShare(0.05)
                .topRegionShare(0.18)
                .topDeviceShare(0.22)
                .topBrowserShare(0.20)
                .peakHourShare(0.16)
                .repeatVisitRatio(0.08)
                .build();

        ShortLinkRiskProfile profile = new ShortLinkRiskDetector().detect(stats);

        assertThat(profile.riskScore()).isLessThan(40);
        assertThat(profile.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(profile.reasonCodes()).isEmpty();
    }

    @Test
    void moderateConcentrationScoresMediumRisk() {
        ShortLinkRiskSourceStats stats = ShortLinkRiskSourceStats.builder()
                .gid("gid-001")
                .domain("nurl.ink")
                .shortUri("medium")
                .fullShortUrl("nurl.ink/medium")
                .pv2h(140)
                .uv2h(90)
                .pv24h(1300)
                .uv24h(620)
                .pv7d(3500)
                .uv7d(2600)
                .topIpShare(0.65)
                .topVisitorShare(0.35)
                .topRegionShare(0.54)
                .topDeviceShare(0.58)
                .topBrowserShare(0.52)
                .peakHourShare(0.55)
                .repeatVisitRatio(0.55)
                .build();

        ShortLinkRiskProfile profile = new ShortLinkRiskDetector().detect(stats);

        assertThat(profile.riskScore()).isBetween(40, 69);
        assertThat(profile.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(profile.reasonCodes()).contains(RiskReasonCode.IP_CONCENTRATION);
        assertThat(profile.reasonCodes()).doesNotContain(RiskReasonCode.TRAFFIC_SPIKE);
    }
}
