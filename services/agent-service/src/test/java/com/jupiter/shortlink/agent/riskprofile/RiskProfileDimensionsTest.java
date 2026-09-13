package com.jupiter.shortlink.agent.riskprofile;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.jupiter.shortlink.agent.riskprofile.model.*;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.service.ShortLinkRiskProfileService;
import com.jupiter.shortlink.agent.riskprofile.source.*;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiskProfileDimensionsTest {
    private final RiskJsonCodec json = new RiskJsonCodec();

    private static RiskWindowDimensions dimensions(String window, long hours) {
        var row = new LinkedHashMap<String, Object>();
        row.put("countryStats", List.of(Map.of("country", "CN", "cnt", 3L, "ratio", 1D)));
        row.put("localeCnStats", List.of(Map.of("locale", "广东省", "cnt", 3L, "ratio", 1D)));
        row.put("networkStats", List.of(Map.of("network", "电信", "cnt", 3L, "ratio", 1D)));
        row.put("uvTypeStats", List.of(Map.of("uvType", "newUser", "cnt", 1L, "ratio", .5D),
                Map.of("uvType", "oldUser", "cnt", 1L, "ratio", .5D)));
        row.put("geoStatusStats", List.of(Map.of("status", "RESOLVED", "cnt", 3L, "ratio", 1D)));
        row.put("geoVersionStats", List.of(Map.of("version", "pinned-geo-v1", "cnt", 3L, "ratio", 1D)));
        row.put("topRegionShare", 1D);
        row.put("dimensionQuality", Map.of("networkStats", Map.of("status", "AVAILABLE", "semantic", "ISP"),
                "uvTypeStats", Map.of("status", "AVAILABLE", "scope", "SHORT_LINK", "maxHistoryDays", 180,
                        "historyStart", 0L, "historyEnd", StatsTestFixtures.NOW, "unknownUv", 0L)));
        return RiskWindowDimensions.from(window, StatsTestFixtures.NOW - Duration.ofHours(hours).toMillis(),
                StatsTestFixtures.NOW, row, StatsTestFixtures.meta());
    }

    private static Map<String, RiskWindowDimensions> dimensions() {
        return Map.of("2h", dimensions("2h", 2), "24h", dimensions("24h", 24), "7d", dimensions("7d", 168));
    }

    @Test
    void legacyFifteenArgumentMetricsAndMissingJsonDimensionsRemainReadable() {
        var legacy = StatsTestFixtures.profile().metrics();
        assertThat(legacy.dimensionWindows()).isEmpty();
        Map<String, Object> oldJson = new LinkedHashMap<>(json.fromJson(json.toJson(legacy), Map.class));
        oldJson.remove("dimensionWindows");
        var restored = json.fromJson(json.toJson(oldJson), ShortLinkRiskMetrics.class);
        assertThat(restored).isEqualTo(legacy);
        assertThat(restored.pv2h()).isEqualTo(3_000_000_000L);
        var enriched = legacy.withDimensionWindows(dimensions());
        var roundtrip = json.fromJson(json.toJson(enriched), ShortLinkRiskMetrics.class);
        assertThat(roundtrip.dimensionWindows().get("2h").networkStats().get(0))
                .isInstanceOf(RiskWindowDimensions.Bucket.class);
        assertThat(json.toJson(roundtrip)).isEqualTo(json.toJson(enriched));
    }

    @Test
    void unknownQualityDoesNotInheritGlobalWindowQualityAndBudgetsRejectOversizedLists() {
        var meta = StatsTestFixtures.meta();
        meta.put("dimensionQuality", Map.of("countryStats", Map.of("status", "AVAILABLE")));
        var unknown = RiskWindowDimensions.from("2h", 1, 2, Map.of(), meta);
        assertThat(unknown.countryStats()).isEmpty();
        assertThat(unknown.dimensionQuality().get("countryStats")).containsEntry("status", "UNKNOWN");
        assertThat(unknown.snapshotContext()).doesNotContainKeys("dimensionQuality", "tenantId", "sourceCut");
        assertThatThrownBy(() -> unknown.dimensionQuality().get("countryStats").put("status", "AVAILABLE"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RiskWindowDimensions.from("2h", 1, 2,
                Map.of("networkStats", Collections.nCopies(257, Map.of("network", "ISP", "cnt", 1))), meta))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
    }

    @Test
    void serviceAttachesThreeWindowExplanationsOnlyAfterUnchangedDetection() {
        var gateway = mock(RiskStatsSourceGateway.class);
        var repository = mock(JdbcShortLinkRiskProfileRepository.class);
        when(repository.saveIfLeaseOwned(any(), anyString(), any())).thenReturn(true);
        var candidate = new ShortLinkActiveCandidate("g1", "nurl.ink", "abc", "nurl.ink/abc");
        var service = new ShortLinkRiskProfileService(gateway, repository);
        when(gateway.loadStatsWindows(any(), any())).thenReturn(windows(false));
        var original = service.generateProfile(candidate, Instant.ofEpochMilli(StatsTestFixtures.NOW), "batch", "owner");
        when(gateway.loadStatsWindows(any(), any())).thenReturn(windows(true));
        var result = service.generateProfile(candidate, Instant.ofEpochMilli(StatsTestFixtures.NOW), "batch", "owner");
        assertThat(result.metrics().dimensionWindows()).containsOnlyKeys("2h", "24h", "7d");
        assertThat(result.metrics().dimensionWindows().get("24h").startInclusive())
                .isEqualTo(StatsTestFixtures.NOW - Duration.ofHours(24).toMillis());
        assertThat(result.metrics().withDimensionWindows(Map.of())).isEqualTo(original.metrics().withDimensionWindows(Map.of()));
        assertThat(result.riskScore()).isEqualTo(original.riskScore());
        assertThat(result.anomalyScore()).isEqualTo(original.anomalyScore());
        assertThat(result.reasonCodes()).isEqualTo(original.reasonCodes());
        assertThat(result.evidence()).isEqualTo(original.evidence());
        assertThat(result.evidence().permitsAutomaticAction(StatsTestFixtures.NOW))
                .isEqualTo(original.evidence().permitsAutomaticAction(StatsTestFixtures.NOW));
        verify(repository, times(2)).saveIfLeaseOwned(any(), eq("owner"), any());
    }

    @Test
    void mismatchedDimensionRangeCannotBeAttachedToAProfile() {
        var gateway = mock(RiskStatsSourceGateway.class);
        var repository = mock(JdbcShortLinkRiskProfileRepository.class);
        var windows = new HashMap<>(windows(true));
        var original = windows.get("2h");
        windows.put("2h", new ShortLinkStatsWindow(original.gid(), original.domain(), original.shortUri(),
                original.fullShortUrl(), original.startTime(), original.endTime(), original.pv(), original.uv(),
                original.uip(), original.topIpShare(), original.topVisitorShare(), original.topRegionShare(),
                original.topDeviceShare(), original.topBrowserShare(), original.peakHourShare(), original.repeatVisitRatio(),
                original.tenantId(), original.linkId(), original.meta(), dimensions("24h", 24)));
        when(gateway.loadStatsWindows(any(), any())).thenReturn(windows);
        assertThatThrownBy(() -> new ShortLinkRiskProfileService(gateway, repository).generateProfile(
                new ShortLinkActiveCandidate("g1", "nurl.ink", "abc", "nurl.ink/abc"),
                Instant.ofEpochMilli(StatsTestFixtures.NOW), "batch", "owner"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different window");
        verifyNoInteractions(repository);
    }

    @Test
    void existingProfileJsonPersistsDimensionsAndLegacyRowsKeepSqlMetricsAuthoritative() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:dimension_profile_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var repository = new JdbcShortLinkRiskProfileRepository(jdbc);
        var profile = StatsTestFixtures.profile().withDimensionWindows(dimensions());
        RiskProfileTestFixture.saveShortLinkProfile(jdbc, repository, profile);
        var restored = repository.findByBatchIdAndGid(profile.batchId(), profile.gid()).get(0);
        assertThat(json.toJson(restored.metrics().dimensionWindows())).isEqualTo(json.toJson(profile.metrics().dimensionWindows()));
        assertThat(restored.metrics().pv2h()).isEqualTo(profile.metrics().pv2h());
        assertThat(restored.riskScore()).isEqualTo(profile.riskScore());

        String table = "t_agent_short_link_risk_profile";
        String snapshot = jdbc.queryForObject("SELECT profile_json FROM " + table, String.class);
        Map<String, Object> old = new LinkedHashMap<>(json.fromJson(snapshot, Map.class));
        Map<String, Object> oldMetrics = new LinkedHashMap<>((Map<String, Object>) old.get("metrics"));
        oldMetrics.remove("dimensionWindows");
        oldMetrics.put("pv2h", 1); // SQL scalar columns remain the preexisting authority.
        old.put("metrics", oldMetrics);
        jdbc.update("UPDATE " + table + " SET profile_json=?", json.toJson(old));
        var legacy = repository.findByBatchIdAndGid(profile.batchId(), profile.gid()).get(0);
        assertThat(legacy.metrics().dimensionWindows()).isEmpty();
        assertThat(legacy.metrics().pv2h()).isEqualTo(profile.metrics().pv2h());
    }

    @Test
    void nativeGraphRoundtripsKeepTypedBucketsAndToolContextCarriesAllWindows() throws Exception {
        var profile = StatsTestFixtures.profile().withDimensionWindows(dimensions());
        Map<String, Object> state = Map.of("profileRiskContext", new ProfileRiskAnalysisContext("g1", null, List.of(profile)));
        for (int round = 0; round < 3; round++) {
            var serializer = AgentStateSerializerFactory.create();
            state = serializer.dataFromBytes(serializer.dataToBytes(state));
            var context = (ProfileRiskAnalysisContext) state.get("profileRiskContext");
            var restored = context.shortLinkProfiles().get(0).metrics().dimensionWindows();
            assertThat(restored.get("2h")).isInstanceOf(RiskWindowDimensions.class);
            assertThat(restored.get("2h").networkStats().get(0)).isInstanceOf(RiskWindowDimensions.Bucket.class);
            String tool = json.toJson(context.toToolExecution());
            assertThat(tool).contains("dimensionWindows", "2h", "24h", "7d", "电信", "广东省", "SHORT_LINK")
                    .doesNotContain("visitorHash", "ipHash", "rawIp");
        }
    }

    private static Map<String, ShortLinkStatsWindow> windows(boolean enriched) {
        Map<String, ShortLinkStatsWindow> result = new LinkedHashMap<>();
        dimensions().forEach((name, dimensions) -> result.put(name, new ShortLinkStatsWindow(
                "g1", "nurl.ink", "abc", "nurl.ink/abc", Instant.ofEpochMilli(dimensions.startInclusive()),
                Instant.ofEpochMilli(dimensions.endExclusive()), 300L, 100L, 80L, .2D, .3D, .8D,
                .4D, .5D, .6D, .7D, "1001", 99L, StatsTestFixtures.meta(), enriched ? dimensions : null)));
        return result;
    }
}
