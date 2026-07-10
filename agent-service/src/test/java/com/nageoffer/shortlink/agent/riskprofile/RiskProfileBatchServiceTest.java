package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.detector.ShortLinkRiskDetector;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.riskprofile.service.GroupRiskProfileAggregator;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskProfileBatchServiceTest {

    private static final Instant BATCH_NOW = Instant.parse("2026-07-10T02:00:00Z");

    private static final String OWNER_TOKEN = "owner-a";

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
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        when(repository.saveIfLeaseOwned(
                any(ShortLinkRiskProfile.class),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        )).thenReturn(true);
        when(groupRepository.saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        )).thenReturn(true);
        when(groupRepository.findTrend7d("gid-001", LocalDate.of(2026, 7, 10))).thenReturn(trend7d());
        ShortLinkRiskProfileService profileService = new ShortLinkRiskProfileService(
                sourceGateway,
                repository,
                new ShortLinkRiskDetector()
        );
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getProfile().setActiveScanDays(7);
        properties.getRisk().getProfile().setTopCandidateSize(10);
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                profileService,
                groupRepository,
                new GroupRiskProfileAggregator(),
                properties
        );

        RiskProfileBatchResult result = batchService.runOnce(BATCH_NOW, OWNER_TOKEN);

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
        verify(repository, times(3)).saveIfLeaseOwned(
                profileCaptor.capture(),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        );
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

        ArgumentCaptor<GroupRiskProfile> groupProfileCaptor = ArgumentCaptor.forClass(GroupRiskProfile.class);
        verify(groupRepository).findTrend7d("gid-001", LocalDate.of(2026, 7, 10));
        verify(groupRepository).saveIfLeaseOwned(
                groupProfileCaptor.capture(),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        );
        assertThat(groupProfileCaptor.getValue().totalShortLinksScanned()).isEqualTo(3);
        assertThat(groupProfileCaptor.getValue().topRiskShortLinks()).hasSize(3);
        assertThat(groupProfileCaptor.getValue().riskTrend7d()).hasSize(7);
    }

    @Test
    void continuesWhenOneCandidateFailsAndReportsTheFailure() {
        ShortLinkActiveCandidate successfulCandidate = candidate("successful");
        ShortLinkActiveCandidate failedCandidate = candidate("failed");
        RiskStatsSourceGateway sourceGateway = mock(RiskStatsSourceGateway.class);
        when(sourceGateway.listActiveShortLinks(BATCH_NOW.minus(Duration.ofDays(7))))
                .thenReturn(List.of(successfulCandidate, failedCandidate));
        ShortLinkRiskProfileService profileService = mock(ShortLinkRiskProfileService.class);
        String batchId = "risk-profile:" + BATCH_NOW.getEpochSecond();
        ShortLinkRiskProfile successfulProfile = riskProfile("successful", 91).withBatchId(batchId);
        when(profileService.generateProfile(
                eq(successfulCandidate),
                eq(BATCH_NOW),
                eq(batchId),
                eq(OWNER_TOKEN)
        ))
                .thenReturn(successfulProfile);
        when(profileService.generateProfile(
                eq(failedCandidate),
                eq(BATCH_NOW),
                eq(batchId),
                eq(OWNER_TOKEN)
        ))
                .thenThrow(new IllegalStateException(
                        "business stats request failed token=secret-value ip=10.20.30.40"
                ));
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        when(groupRepository.saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        )).thenReturn(true);
        when(groupRepository.findTrend7d("gid-001", LocalDate.of(2026, 7, 10))).thenReturn(trend7d());
        AgentProperties properties = new AgentProperties();
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                profileService,
                groupRepository,
                new GroupRiskProfileAggregator(),
                properties
        );

        RiskProfileBatchResult result = batchService.runOnce(BATCH_NOW, OWNER_TOKEN);

        assertThat(result.batchId()).isEqualTo(batchId);
        assertThat(result.scannedShortLinks()).isEqualTo(2);
        assertThat(result.generatedProfiles()).isEqualTo(1);
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.targetKey()).isEqualTo("gid-001/nurl.ink/failed");
            assertThat(failure.errorCode()).isEqualTo("PROFILE_GENERATION_FAILED");
            assertThat(failure.message())
                    .isEqualTo("business stats request failed token=*** ip=10.20.*.*");
        });
        assertThat(result.abnormalCandidates()).containsExactly(successfulProfile);
        verify(groupRepository).saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        );
    }

    @Test
    void retriesAggregateAllProfilesAlreadyPersistedForTheSameBatch() {
        ShortLinkActiveCandidate previouslySuccessfulCandidate = candidate("previously-successful");
        ShortLinkActiveCandidate newlySuccessfulCandidate = candidate("newly-successful");
        RiskStatsSourceGateway sourceGateway = mock(RiskStatsSourceGateway.class);
        when(sourceGateway.listActiveShortLinks(BATCH_NOW.minus(Duration.ofDays(7))))
                .thenReturn(List.of(previouslySuccessfulCandidate, newlySuccessfulCandidate));
        String batchId = "risk-profile:" + BATCH_NOW.getEpochSecond();
        ShortLinkRiskProfile previousProfile = riskProfile("previously-successful", 88).withBatchId(batchId);
        ShortLinkRiskProfile newProfile = riskProfile("newly-successful", 93).withBatchId(batchId);
        ShortLinkRiskProfileService profileService = mock(ShortLinkRiskProfileService.class);
        when(profileService.generateProfile(
                eq(previouslySuccessfulCandidate),
                eq(BATCH_NOW),
                eq(batchId),
                eq(OWNER_TOKEN)
        ))
                .thenThrow(new IllegalStateException("temporary retry failure"));
        when(profileService.generateProfile(
                eq(newlySuccessfulCandidate),
                eq(BATCH_NOW),
                eq(batchId),
                eq(OWNER_TOKEN)
        ))
                .thenReturn(newProfile);
        when(profileService.findByBatchIdAndGid(batchId, "gid-001"))
                .thenReturn(List.of(previousProfile, newProfile));
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        when(groupRepository.saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        )).thenReturn(true);
        when(groupRepository.findTrend7d("gid-001", LocalDate.of(2026, 7, 10))).thenReturn(trend7d());
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                profileService,
                groupRepository,
                new GroupRiskProfileAggregator(),
                new AgentProperties()
        );

        RiskProfileBatchResult result = batchService.runOnce(BATCH_NOW, OWNER_TOKEN);

        assertThat(result.generatedProfiles()).isEqualTo(2);
        assertThat(result.failures()).isEmpty();
        assertThat(result.abnormalCandidates())
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("newly-successful", "previously-successful");
        ArgumentCaptor<GroupRiskProfile> groupCaptor = ArgumentCaptor.forClass(GroupRiskProfile.class);
        verify(groupRepository).saveIfLeaseOwned(
                groupCaptor.capture(),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        );
        assertThat(groupCaptor.getValue().totalShortLinksScanned()).isEqualTo(2);
        assertThat(groupCaptor.getValue().topRiskShortLinks())
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("newly-successful", "previously-successful");
        assertThat(groupCaptor.getValue().batchId()).isEqualTo(batchId);
    }

    @Test
    void abnormalCandidatesUseDomainBeforeShortUriAsTheTieBreaker() {
        ShortLinkActiveCandidate bSame = candidate("b.example", "same");
        ShortLinkActiveCandidate aLast = candidate("a.example", "z-last");
        ShortLinkActiveCandidate aFirst = candidate("a.example", "a-first");
        RiskStatsSourceGateway sourceGateway = mock(RiskStatsSourceGateway.class);
        when(sourceGateway.listActiveShortLinks(BATCH_NOW.minus(Duration.ofDays(7))))
                .thenReturn(List.of(bSame, aLast, aFirst));
        String batchId = "risk-profile:" + BATCH_NOW.getEpochSecond();
        ShortLinkRiskProfile bSameProfile = riskProfile("b.example", "same", 90, 500)
                .withBatchId(batchId);
        ShortLinkRiskProfile aLastProfile = riskProfile("a.example", "z-last", 90, 500)
                .withBatchId(batchId);
        ShortLinkRiskProfile aFirstProfile = riskProfile("a.example", "a-first", 90, 500)
                .withBatchId(batchId);
        ShortLinkRiskProfileService profileService = mock(ShortLinkRiskProfileService.class);
        when(profileService.generateProfile(bSame, BATCH_NOW, batchId, OWNER_TOKEN)).thenReturn(bSameProfile);
        when(profileService.generateProfile(aLast, BATCH_NOW, batchId, OWNER_TOKEN)).thenReturn(aLastProfile);
        when(profileService.generateProfile(aFirst, BATCH_NOW, batchId, OWNER_TOKEN)).thenReturn(aFirstProfile);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        when(groupRepository.saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq(OWNER_TOKEN),
                any(LocalDateTime.class)
        )).thenReturn(true);
        when(groupRepository.findTrend7d("gid-001", LocalDate.of(2026, 7, 10))).thenReturn(List.of());
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getProfile().setTopCandidateSize(3);
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                profileService,
                groupRepository,
                new GroupRiskProfileAggregator(),
                properties
        );

        RiskProfileBatchResult result = batchService.runOnce(BATCH_NOW, OWNER_TOKEN);

        assertThat(result.abnormalCandidates())
                .extracting(profile -> profile.domain() + "/" + profile.shortUri())
                .containsExactly(
                        "a.example/a-first",
                        "a.example/z-last",
                        "b.example/same"
                );
    }

    @Test
    void guardedBatchUsesTheLeaseOwnerForShortLinkAndGroupWrites() {
        ShortLinkActiveCandidate candidate = candidate("guarded");
        RiskStatsSourceGateway sourceGateway = mock(RiskStatsSourceGateway.class);
        when(sourceGateway.listActiveShortLinks(BATCH_NOW.minus(Duration.ofDays(7))))
                .thenReturn(List.of(candidate));
        String batchId = "risk-profile:" + BATCH_NOW.getEpochSecond();
        ShortLinkRiskProfile profile = riskProfile("guarded", 91).withBatchId(batchId);
        ShortLinkRiskProfileService profileService = mock(ShortLinkRiskProfileService.class);
        when(profileService.generateProfile(candidate, BATCH_NOW, batchId, "owner-a"))
                .thenReturn(profile);
        when(profileService.findByBatchIdAndGid(batchId, "gid-001"))
                .thenReturn(List.of(profile));
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        when(groupRepository.findTrend7d("gid-001", LocalDate.of(2026, 7, 10))).thenReturn(trend7d());
        when(groupRepository.saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq("owner-a"),
                any(LocalDateTime.class)
        )).thenReturn(true);
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                profileService,
                groupRepository,
                new GroupRiskProfileAggregator(),
                new AgentProperties()
        );

        RiskProfileBatchResult result = batchService.runOnce(BATCH_NOW, "owner-a");

        assertThat(result.generatedProfiles()).isEqualTo(1);
        verify(profileService).generateProfile(candidate, BATCH_NOW, batchId, "owner-a");
        verify(groupRepository).saveIfLeaseOwned(
                any(GroupRiskProfile.class),
                eq("owner-a"),
                any(LocalDateTime.class)
        );
    }

    @Test
    void leaseLossStopsTheBatchInsteadOfBeingReportedAsACandidateFailure() {
        ShortLinkActiveCandidate candidate = candidate("lease-lost");
        RiskStatsSourceGateway sourceGateway = mock(RiskStatsSourceGateway.class);
        when(sourceGateway.listActiveShortLinks(BATCH_NOW.minus(Duration.ofDays(7))))
                .thenReturn(List.of(candidate));
        String batchId = "risk-profile:" + BATCH_NOW.getEpochSecond();
        ShortLinkRiskProfileService profileService = mock(ShortLinkRiskProfileService.class);
        when(profileService.generateProfile(candidate, BATCH_NOW, batchId, "owner-a"))
                .thenThrow(new RiskProfileBatchLeaseLostException(batchId));
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                profileService,
                mock(JdbcGroupRiskProfileRepository.class),
                new GroupRiskProfileAggregator(),
                new AgentProperties()
        );

        assertThatThrownBy(() -> batchService.runOnce(BATCH_NOW, "owner-a"))
                .isInstanceOf(RiskProfileBatchLeaseLostException.class);
    }

    private ShortLinkActiveCandidate candidate(String shortUri) {
        return candidate("nurl.ink", shortUri);
    }

    private ShortLinkActiveCandidate candidate(String domain, String shortUri) {
        return new ShortLinkActiveCandidate("gid-001", domain, shortUri, domain + "/" + shortUri);
    }

    private ShortLinkRiskProfile riskProfile(String shortUri, int riskScore) {
        return riskProfile("nurl.ink", shortUri, riskScore, 600);
    }

    private ShortLinkRiskProfile riskProfile(
            String domain,
            String shortUri,
            int riskScore,
            int pv2h
    ) {
        return new ShortLinkRiskProfile(
                "gid-001",
                domain,
                shortUri,
                domain + "/" + shortUri,
                LocalDateTime.of(2026, 7, 10, 8, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                new com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics(
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
                com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel.fromScore(riskScore),
                java.util.Set.of(
                        com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode.TRAFFIC_SPIKE,
                        com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode.IP_CONCENTRATION
                ),
                com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus.NONE,
                List.of(),
                ""
        );
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

    private List<RiskTrendPoint> trend7d() {
        LocalDate start = LocalDate.of(2026, 7, 4);
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> new RiskTrendPoint(start.plusDays(index), 40 + index, null))
                .toList();
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
