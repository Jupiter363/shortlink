package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileCandidateSelector;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfileCandidateSelectorTest {

    @Test
    void selectsCandidatesByRiskTrafficDomainAndShortUriDeterministically() {
        List<ShortLinkRiskProfile> candidates = List.of(
                profile("b.example", "same", 90, 500),
                profile("a.example", "z-last", 90, 500),
                profile("a.example", "a-first", 90, 500),
                profile("c.example", "higher-traffic", 90, 600),
                profile("d.example", "higher-risk", 91, 100)
        );

        assertThat(RiskProfileCandidateSelector.top(candidates, 5))
                .extracting(profile -> profile.domain() + "/" + profile.shortUri())
                .containsExactly(
                        "d.example/higher-risk",
                        "c.example/higher-traffic",
                        "a.example/a-first",
                        "a.example/z-last",
                        "b.example/same"
                );
    }

    private ShortLinkRiskProfile profile(String domain, String shortUri, int riskScore, int pv2h) {
        LocalDateTime windowEnd = LocalDateTime.of(2026, 7, 10, 10, 0);
        return new ShortLinkRiskProfile(
                "gid-001",
                domain,
                shortUri,
                domain + "/" + shortUri,
                windowEnd.minusHours(2),
                windowEnd,
                new ShortLinkRiskMetrics(
                        pv2h,
                        50,
                        900,
                        300,
                        2100,
                        1200,
                        8.0,
                        0.82,
                        0.78,
                        0.50,
                        0.65,
                        0.60,
                        12.0,
                        0.74,
                        0.88
                ),
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE),
                RiskWatchStatus.NONE,
                List.of(),
                "",
                "risk-profile:batch-001"
        );
    }
}
