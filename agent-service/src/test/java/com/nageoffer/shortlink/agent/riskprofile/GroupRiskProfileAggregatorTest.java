package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.service.GroupRiskProfileAggregator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GroupRiskProfileAggregatorTest {

    @Test
    void aggregatesCountsTopRiskReasonCodesAndSevenDayTrend() {
        List<ShortLinkRiskProfile> profiles = new ArrayList<>();
        profiles.add(profile("high-1", 95, 900, RiskLevel.HIGH, RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION));
        profiles.add(profile("high-2", 90, 850, RiskLevel.HIGH, RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION));
        profiles.add(profile("high-3", 85, 800, RiskLevel.HIGH, RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION));
        profiles.add(profile("medium-1", 65, 700, RiskLevel.MEDIUM, RiskReasonCode.IP_CONCENTRATION, RiskReasonCode.PEAK_HOUR_BURST));
        profiles.add(profile("medium-2", 60, 650, RiskLevel.MEDIUM, RiskReasonCode.IP_CONCENTRATION, RiskReasonCode.PEAK_HOUR_BURST));
        profiles.add(profile("medium-3", 55, 600, RiskLevel.MEDIUM, RiskReasonCode.IP_CONCENTRATION, RiskReasonCode.PEAK_HOUR_BURST));
        profiles.add(profile("medium-4", 50, 550, RiskLevel.MEDIUM, RiskReasonCode.IP_CONCENTRATION, RiskReasonCode.PEAK_HOUR_BURST));
        profiles.add(profile("low-1", 20, 500, RiskLevel.LOW));
        profiles.add(profile("low-2", 15, 450, RiskLevel.LOW));
        profiles.add(profile("low-3", 10, 400, RiskLevel.LOW));
        profiles.add(profile("low-4", 5, 350, RiskLevel.LOW));
        profiles.add(profile("low-5", 1, 300, RiskLevel.LOW));
        List<RiskTrendPoint> historyTrend = trend7d();

        GroupRiskProfile profile = new GroupRiskProfileAggregator().aggregate("gid-001", profiles, historyTrend);

        assertThat(profile.totalShortLinksScanned()).isEqualTo(12);
        assertThat(profile.highRiskCount()).isEqualTo(3);
        assertThat(profile.mediumRiskCount()).isEqualTo(4);
        assertThat(profile.lowRiskCount()).isEqualTo(5);
        assertThat(profile.maxRiskScore()).isEqualTo(95);
        assertThat(profile.groupRiskScore()).isEqualTo(95);
        assertThat(profile.groupRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(profile.groupReasonCodes()).containsExactly(
                RiskReasonCode.IP_CONCENTRATION,
                RiskReasonCode.PEAK_HOUR_BURST,
                RiskReasonCode.TRAFFIC_SPIKE
        );
        assertThat(profile.topRiskShortLinks()).hasSize(10);
        assertThat(profile.topRiskShortLinks()).extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly(
                        "high-1",
                        "high-2",
                        "high-3",
                        "medium-1",
                        "medium-2",
                        "medium-3",
                        "medium-4",
                        "low-1",
                        "low-2",
                        "low-3"
                );
        assertThat(profile.riskTrend7d()).isEqualTo(historyTrend);
    }

    @Test
    void topRiskShortLinksUseDomainBeforeShortUriAsTheTieBreaker() {
        List<ShortLinkRiskProfile> profiles = List.of(
                profile("b.example", "same", 90, 500, RiskLevel.HIGH),
                profile("a.example", "z-last", 90, 500, RiskLevel.HIGH),
                profile("a.example", "a-first", 90, 500, RiskLevel.HIGH)
        );

        GroupRiskProfile groupProfile = new GroupRiskProfileAggregator().aggregate(
                "gid-001",
                profiles,
                List.of()
        );

        assertThat(groupProfile.topRiskShortLinks())
                .extracting(profile -> profile.domain() + "/" + profile.shortUri())
                .containsExactly(
                        "a.example/a-first",
                        "a.example/z-last",
                        "b.example/same"
                );
    }

    private ShortLinkRiskProfile profile(
            String shortUri,
            int riskScore,
            int pv2h,
            RiskLevel riskLevel,
            RiskReasonCode... reasonCodes
    ) {
        return profile("nurl.ink", shortUri, riskScore, pv2h, riskLevel, reasonCodes);
    }

    private ShortLinkRiskProfile profile(
            String domain,
            String shortUri,
            int riskScore,
            int pv2h,
            RiskLevel riskLevel,
            RiskReasonCode... reasonCodes
    ) {
        return new ShortLinkRiskProfile(
                "gid-001",
                domain,
                shortUri,
                domain + "/" + shortUri,
                LocalDateTime.of(2026, 7, 10, 8, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                metrics(pv2h),
                riskScore,
                riskScore,
                riskLevel,
                Set.of(reasonCodes),
                RiskWatchStatus.NONE,
                List.of(),
                ""
        );
    }

    private ShortLinkRiskMetrics metrics(int pv2h) {
        return new ShortLinkRiskMetrics(
                pv2h,
                Math.max(1, pv2h / 2),
                pv2h * 4,
                Math.max(1, pv2h * 2),
                pv2h * 10,
                Math.max(1, pv2h * 5),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private List<RiskTrendPoint> trend7d() {
        LocalDate start = LocalDate.of(2026, 7, 4);
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new RiskTrendPoint(start.plusDays(index), 40 + index, RiskLevel.MEDIUM))
                .toList();
    }
}
