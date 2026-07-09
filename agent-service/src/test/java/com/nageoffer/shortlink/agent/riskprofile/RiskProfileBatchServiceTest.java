package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.detector.ShortLinkRiskDetector;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import com.nageoffer.shortlink.agent.riskprofile.service.ShortLinkRiskProfileService;
import com.nageoffer.shortlink.agent.riskprofile.source.RiskStatsSourceGateway;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkStatsWindow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RiskProfileBatchServiceTest {

    private static final Instant BATCH_NOW = Instant.parse("2026-07-10T02:00:00Z");

    @Test
    void scansActiveShortLinksLoadsThreeWindowsPersistsProfilesAndReturnsAbnormalCandidates() {
        ShortLinkActiveCandidate high = candidate("high");
        ShortLinkActiveCandidate medium = candidate("medium");
        ShortLinkActiveCandidate low = candidate("low");
        FakeRiskStatsSourceGateway sourceGateway = new FakeRiskStatsSourceGateway(List.of(high, medium, low));
        sourceGateway.put(high, new StatsSet(
                window(600, 50, 30, 0.82, 0.78, 0.50, 0.65, 0.60, 0.74, 0.88),
                window(900, 300, 200, null, null, null, null, null, null, null),
                window(2100, 1200, 800, null, null, null, null, null, null, null)
        ));
        sourceGateway.put(medium, new StatsSet(
                window(140, 90, 70, 0.65, 0.35, 0.54, 0.58, 0.52, 0.55, 0.55),
                window(1300, 620, 420, null, null, null, null, null, null, null),
                window(3500, 2600, 1900, null, null, null, null, null, null, null)
        ));
        sourceGateway.put(low, new StatsSet(
                window(20, 18, 16, 0.08, 0.05, 0.18, 0.22, 0.20, 0.16, 0.08),
                window(260, 220, 180, null, null, null, null, null, null, null),
                window(1800, 1500, 1200, null, null, null, null, null, null, null)
        ));
        JdbcShortLinkRiskProfileRepository repository = mock(JdbcShortLinkRiskProfileRepository.class);
        ShortLinkRiskProfileService profileService = new ShortLinkRiskProfileService(
                sourceGateway,
                repository,
                new ShortLinkRiskDetector()
        );
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getProfile().setActiveScanDays(7);
        properties.getRisk().getProfile().setTopCandidateSize(10);
        RiskProfileBatchService batchService = new RiskProfileBatchService(sourceGateway, profileService, properties);

        RiskProfileBatchResult result = batchService.runOnce(BATCH_NOW);

        assertThat(sourceGateway.lastSince()).isEqualTo(BATCH_NOW.minus(Duration.ofDays(7)));
        assertThat(sourceGateway.loadCalls())
                .extracting(LoadCall::duration)
                .containsExactly(
                        Duration.ofHours(2), Duration.ofHours(24), Duration.ofDays(7),
                        Duration.ofHours(2), Duration.ofHours(24), Duration.ofDays(7),
                        Duration.ofHours(2), Duration.ofHours(24), Duration.ofDays(7)
                );
        assertThat(result.scannedShortLinks()).isEqualTo(3);
        assertThat(result.generatedProfiles()).isEqualTo(3);
        assertThat(result.abnormalCandidates()).extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("high", "medium");
        assertThat(result.abnormalCandidatesByGid()).containsOnlyKeys("gid-001");

        ArgumentCaptor<ShortLinkRiskProfile> profileCaptor = ArgumentCaptor.forClass(ShortLinkRiskProfile.class);
        verify(repository, times(3)).save(profileCaptor.capture());
        assertThat(profileCaptor.getAllValues())
                .allSatisfy(profile -> {
                    assertThat(profile.profileWindowStart()).isEqualTo(LocalDateTime.of(2026, 7, 10, 8, 0));
                    assertThat(profile.profileWindowEnd()).isEqualTo(LocalDateTime.of(2026, 7, 10, 10, 0));
                });
        assertThat(profileCaptor.getAllValues())
                .filteredOn(profile -> profile.shortUri().equals("high"))
                .singleElement()
                .satisfies(profile -> {
                    assertThat(profile.metrics().pv2h()).isEqualTo(600);
                    assertThat(profile.metrics().pv24h()).isEqualTo(900);
                    assertThat(profile.metrics().pv7d()).isEqualTo(2100);
                    assertThat(profile.riskScore()).isGreaterThanOrEqualTo(80);
                });
    }

    private ShortLinkActiveCandidate candidate(String shortUri) {
        return new ShortLinkActiveCandidate("gid-001", "nurl.ink", shortUri, "nurl.ink/" + shortUri);
    }

    private WindowFixture window(
            int pv,
            int uv,
            int uip,
            Double topIpShare,
            Double topVisitorShare,
            Double topRegionShare,
            Double topDeviceShare,
            Double topBrowserShare,
            Double peakHourShare,
            Double repeatVisitRatio
    ) {
        return new WindowFixture(
                pv,
                uv,
                uip,
                topIpShare,
                topVisitorShare,
                topRegionShare,
                topDeviceShare,
                topBrowserShare,
                peakHourShare,
                repeatVisitRatio
        );
    }

    private record WindowFixture(
            int pv,
            int uv,
            int uip,
            Double topIpShare,
            Double topVisitorShare,
            Double topRegionShare,
            Double topDeviceShare,
            Double topBrowserShare,
            Double peakHourShare,
            Double repeatVisitRatio
    ) {
    }

    private record StatsSet(WindowFixture twoHours, WindowFixture twentyFourHours, WindowFixture sevenDays) {

        WindowFixture byDuration(Duration duration) {
            if (Duration.ofHours(2).equals(duration)) {
                return twoHours;
            }
            if (Duration.ofHours(24).equals(duration)) {
                return twentyFourHours;
            }
            if (Duration.ofDays(7).equals(duration)) {
                return sevenDays;
            }
            throw new IllegalArgumentException("Unexpected duration " + duration);
        }
    }

    private record LoadCall(ShortLinkActiveCandidate candidate, Duration duration) {
    }

    private static class FakeRiskStatsSourceGateway implements RiskStatsSourceGateway {

        private final List<ShortLinkActiveCandidate> candidates;

        private final Map<String, StatsSet> statsByShortUri = new LinkedHashMap<>();

        private final List<LoadCall> loadCalls = new ArrayList<>();

        private Instant lastSince;

        private FakeRiskStatsSourceGateway(List<ShortLinkActiveCandidate> candidates) {
            this.candidates = candidates;
        }

        void put(ShortLinkActiveCandidate candidate, StatsSet statsSet) {
            statsByShortUri.put(candidate.shortUri(), statsSet);
        }

        Instant lastSince() {
            return lastSince;
        }

        List<LoadCall> loadCalls() {
            return loadCalls;
        }

        @Override
        public List<ShortLinkActiveCandidate> listActiveShortLinks(Instant since) {
            lastSince = since;
            return candidates;
        }

        @Override
        public ShortLinkStatsWindow loadStatsWindow(ShortLinkActiveCandidate candidate, Instant start, Instant end) {
            Duration duration = Duration.between(start, end);
            loadCalls.add(new LoadCall(candidate, duration));
            WindowFixture fixture = statsByShortUri.get(candidate.shortUri()).byDuration(duration);
            return new ShortLinkStatsWindow(
                    candidate.gid(),
                    candidate.domain(),
                    candidate.shortUri(),
                    candidate.fullShortUrl(),
                    start,
                    end,
                    fixture.pv(),
                    fixture.uv(),
                    fixture.uip(),
                    fixture.topIpShare(),
                    fixture.topVisitorShare(),
                    fixture.topRegionShare(),
                    fixture.topDeviceShare(),
                    fixture.topBrowserShare(),
                    fixture.peakHourShare(),
                    fixture.repeatVisitRatio()
            );
        }
    }
}
