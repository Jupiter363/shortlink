package com.jupiter.shortlink.agent.riskprofile;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.harness.checkpoint.MysqlGraphCompileConfigFactory;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskprofile.model.*;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.service.GroupRiskProfileAggregator;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskProfileGraphProjection;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskProfileGraphProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compatibilityConstructorDecodesErasedCandidatesOnlyAsTheFixedProfileDto() {
        var original = StatsTestFixtures.profile();
        Map<String, Object> raw = JSON.convertValue(original, Map.class);
        raw.put("@class", "java.lang.Runtime"); // This is data, never an instruction to resolve a class.
        List<ShortLinkRiskProfile> erased = (List) List.of(raw);
        var context = new ProfileRiskAnalysisContext("g1", null, erased);
        assertThat(context.shortLinkProfiles().get(0)).isInstanceOf(ShortLinkRiskProfile.class);
        assertThat(context.shortLinkProfiles().get(0).withEvidence(original.evidence())).isEqualTo(original);
        assertThat(context.shortLinkProfiles().get(0).evidence().commandEvidence()).isEqualTo(original.evidence().commandEvidence());
        assertThat(((List<?>) erased).get(0)).isSameAs(raw);
    }

    @Test
    void sevenDaysOf2016CutsBecomeVerifiableBoundedCopiesWithoutChangingScoresOrDimensions() throws Exception {
        var original = largeProfile();
        var group = new GroupRiskProfileAggregator().aggregate("g1", List.of(original), List.of());
        String originalDigest = RiskProfileGraphProjection.sha256(original.evidence().meta());
        var context = new ProfileRiskAnalysisContext("g1", group, List.of(original));
        var projected = context.shortLinkProfiles().get(0);
        var meta = projected.evidence().meta();
        assertThat(JSON.writeValueAsBytes(original.evidence().meta()).length).isGreaterThan(1_000_000);
        assertThat(JSON.writeValueAsBytes(context).length).isLessThan(64 * 1024);
        assertThat(JSON.writeValueAsBytes(context).length).isLessThan(RiskProfileGraphProjection.MAX_CONTEXT_BYTES);
        assertThat(meta).doesNotContainKeys("sourceCut", "manifestVersion", "unrecognizedSourceDetails");
        Map<?, ?> refs = (Map<?, ?>) meta.get("evidenceReferences");
        assertThat(refs.get("metadataSha256")).isEqualTo(originalDigest);
        for (String field : List.of("sourceCut", "manifestVersion")) {
            Map<?, ?> ref = (Map<?, ?>) refs.get(field);
            assertThat(ref.get("entries")).isEqualTo(2016);
            assertThat(ref.get("sha256")).isEqualTo(RiskProfileGraphProjection.sha256(original.evidence().meta().get(field)));
        }
        assertThat(projected.metrics()).isSameAs(original.metrics());
        assertThat(projected.withEvidence(original.evidence())).isEqualTo(original);
        assertThat(context.groupProfile().topRiskShortLinks().get(0)).isEqualTo(projected);
        assertThat(group.topRiskShortLinks().get(0)).isSameAs(original);
        assertThat(RiskProfileGraphProjection.sha256(original.evidence().meta())).isEqualTo(originalDigest);
        assertThat(original.evidence().meta()).doesNotContainKey("evidenceReferences");
        assertThat(projected.evidence().commandEvidence()).isEqualTo(original.evidence().commandEvidence());
        assertThat(projected.evidence().permitsAutomaticAction(StatsTestFixtures.NOW)).isTrue();
        for (RiskReasonCode reason : RiskReasonCode.values())
            assertThat(projected.evidence().supportsAutomaticReason(reason)).isEqualTo(original.evidence().supportsAutomaticReason(reason));
        assertThat(projected.evidence().meta().get("collectionQuality")).isEqualTo(original.evidence().meta().get("collectionQuality"));
    }

    @Test
    void typedGraphAndNativeMysqlEnvelopeKeepReferencesAndDimensionsIdenticalAcrossThreeRestores() throws Exception {
        var original = largeProfile();
        var context = new ProfileRiskAnalysisContext("g1",
                new GroupRiskProfileAggregator().aggregate("g1", List.of(original), List.of()), List.of(original));
        var serializer = AgentStateSerializerFactory.create();
        var graph = new StateGraph("bounded-profile-checkpoint", Map::of, serializer)
                .addNode("load", AsyncNodeAction.node_async(state -> profileState(context)))
                .addNode("verify", AsyncNodeAction.node_async(state -> {
                    assertThat(state.data().get("profileRiskContext")).isInstanceOf(ProfileRiskAnalysisContext.class);
                    return Map.of("verified", true);
                }))
                .addEdge(StateGraph.START, "load").addEdge("load", "verify").addEdge("verify", StateGraph.END)
                .compile(MysqlGraphCompileConfigFactory.create(MemorySaver.builder().build()));
        var result = graph.invoke(new LinkedHashMap<>(), RunnableConfig.builder().threadId("bounded-profile").build()).orElseThrow();
        assertThat(result.data().get("verified")).isEqualTo(true);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        var saver = MysqlSaver.builder().dataSource(dataSource).stateSerializer(serializer)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS).build();
        Map<String, Object> state = result.data();
        String references = JSON.writeValueAsString(context.shortLinkProfiles().get(0).evidence().meta().get("evidenceReferences"));
        for (int round = 0; round < 3; round++) {
            String envelope = ReflectionTestUtils.invokeMethod(saver, "encodeState", state);
            assertThat(envelope.getBytes(StandardCharsets.UTF_8).length).isLessThan(256 * 1024);
            String payload = JSON.readTree(envelope).get("binaryPayload").asText();
            state = ReflectionTestUtils.invokeMethod(saver, "decodeState", payload.getBytes(StandardCharsets.US_ASCII));
            var restored = (ProfileRiskAnalysisContext) state.get("profileRiskContext");
            assertThat(JSON.writeValueAsString(restored.shortLinkProfiles().get(0).evidence().meta().get("evidenceReferences")))
                    .isEqualTo(references);
            assertThat(restored.shortLinkProfiles().get(0).evidence().commandEvidence()).isEqualTo(original.evidence().commandEvidence());
            assertThat(restored.shortLinkProfiles().get(0).metrics().dimensionWindows()).containsOnlyKeys("2h", "24h", "7d");
            var dimensions = restored.shortLinkProfiles().get(0).metrics().dimensionWindows().get("7d");
            assertThat(dimensions.networkStats().get(0)).isInstanceOf(RiskWindowDimensions.Bucket.class);
            assertThat(JSON.writeValueAsString(dimensions)).contains("广东省", "电信", "newUser", "oldUser", "UNKNOWN", "SHORT_LINK");
            var projectedAgain = new ProfileRiskAnalysisContext(restored.gid(), restored.groupProfile(), restored.shortLinkProfiles());
            assertThat(projectedAgain).isEqualTo(restored);
            assertThat(JSON.writeValueAsBytes(restored).length).isLessThan(64 * 1024);
        }
    }

    @Test
    void repositoryRetainsFullCutWhenItsReadResultIsProjected() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:projection_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var repository = new JdbcShortLinkRiskProfileRepository(jdbc);
        var original = largeProfile();
        RiskProfileTestFixture.saveShortLinkProfile(jdbc, repository, original);
        String before = jdbc.queryForObject("SELECT profile_json FROM t_agent_short_link_risk_profile", String.class);
        var loaded = repository.findByBatchIdAndGid(original.batchId(), original.gid());
        var context = new ProfileRiskAnalysisContext("g1", null, loaded);
        assertThat(context.shortLinkProfiles().get(0).evidence().meta()).containsKey("evidenceReferences");
        assertThat(((Map<?, ?>) loaded.get(0).evidence().meta().get("sourceCut"))).hasSize(2016);
        assertThat(jdbc.queryForObject("SELECT profile_json FROM t_agent_short_link_risk_profile", String.class)).isEqualTo(before);
        assertThat(RiskProfileGraphProjection.sha256(loaded.get(0).evidence().meta()))
                .isEqualTo(RiskProfileGraphProjection.sha256(original.evidence().meta()));
    }

    @Test
    void stalePartialUnknownQualityAndApproximationRemainUnableToAuthorizeActions() {
        for (var change : List.of(Map.of("collectionQuality", Map.of("status", "UNKNOWN")),
                Map.of("freshness", "STALE"), Map.of("completeness", "PARTIAL"),
                Map.of("approximation", Map.of("pv", Map.of("type", "APPROXIMATE"))),
                Map.of("snapshotExpiresAt", StatsTestFixtures.NOW - 1))) {
            var meta = new LinkedHashMap<String, Object>(StatsTestFixtures.meta());
            meta.putAll(change);
            var evidence = new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION);
            var projected = new ProfileRiskAnalysisContext("g1", null,
                    List.of(StatsTestFixtures.profile().withEvidence(evidence))).shortLinkProfiles().get(0).evidence();
            assertThat(evidence.permitsAutomaticAction(StatsTestFixtures.NOW)).isFalse();
            assertThat(projected.permitsAutomaticAction(StatsTestFixtures.NOW)).isFalse();
            for (String field : change.keySet()) assertThat(projected.meta().get(field)).isEqualTo(meta.get(field));
        }
    }

    @Test
    void metadataCandidateAndWholeContextBudgetsFailWithoutSilentlyTruncatingEvidence() {
        assertThatThrownBy(() -> new ProfileRiskAnalysisContext("g1", null,
                Collections.nCopies(101, StatsTestFixtures.profile())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("candidate budget");
        var meta = new LinkedHashMap<String, Object>(StatsTestFixtures.meta());
        meta.put("collectionQuality", Map.of("status", "UNKNOWN", "reasons", List.of("x".repeat(33_000))));
        var evidence = new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION);
        assertThatThrownBy(() -> new ProfileRiskAnalysisContext("g1", null,
                List.of(StatsTestFixtures.profile().withEvidence(evidence))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evidence byte budget");
        assertThatThrownBy(() -> new ProfileRiskAnalysisContext("界".repeat(400_000), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("context byte budget");
        assertThat(evidence.meta()).containsKey("sourceCut").doesNotContainKey("evidenceReferences");
    }

    @Test
    void fingerprintsIgnoreObjectKeyOrderButDetectChangedCutsAndMixedReferencesAreRejected() {
        var ordered = new LinkedHashMap<String, Object>();
        ordered.put("z", 7); ordered.put("a", Map.of("n", 3L));
        assertThat(RiskProfileGraphProjection.sha256(ordered))
                .isEqualTo(RiskProfileGraphProjection.sha256(Map.of("a", Map.of("n", 3), "z", 7L)));
        assertThat(RiskProfileGraphProjection.sha256(ordered))
                .isNotEqualTo(RiskProfileGraphProjection.sha256(Map.of("a", Map.of("n", 4), "z", 7)));
        var context = new ProfileRiskAnalysisContext("g1", null, List.of(StatsTestFixtures.profile()));
        var meta = new LinkedHashMap<String, Object>(context.shortLinkProfiles().get(0).evidence().meta());
        meta.put("sourceCut", Map.of("changed", 1));
        assertThatThrownBy(() -> new ProfileRiskAnalysisContext("g1", null, List.of(StatsTestFixtures.profile()
                .withEvidence(new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Mixed full and projected");
    }

    private static Map<String, Object> profileState(ProfileRiskAnalysisContext context) {
        return Map.of("profileRiskContext", context, "profileRiskDataSource", context.toDataSource(),
                "toolExecutions", List.of(context.toToolExecution()), "cards", context.riskCards());
    }

    private static ShortLinkRiskProfile largeProfile() {
        var meta = new LinkedHashMap<String, Object>(StatsTestFixtures.meta());
        Map<String, Object> cuts = new LinkedHashMap<>();
        Map<String, Object> manifests = new LinkedHashMap<>();
        for (int i = 0; i < 2016; i++) {
            String key = Long.toString(StatsTestFixtures.NOW - Duration.ofDays(7).toMillis() + i * 300_000L);
            cuts.put(key, Map.of("click", Map.of("0", Map.of("offset", i + 1L, "archiveDigest", "a".repeat(64),
                    "receiptDigests", List.of("b".repeat(64), "c".repeat(64), "d".repeat(64), "e".repeat(64), "f".repeat(64), "1".repeat(64))))));
            manifests.put(key, Map.of("buildId", "build-" + key, "revision", 1L, "status", "PUBLISHED"));
        }
        meta.put("sourceCut", cuts);
        meta.put("manifestVersion", manifests);
        meta.put("unrecognizedSourceDetails", Map.of("body", "kept only in the original database evidence"));
        var row = Map.<String, Object>of("countryStats", List.of(Map.of("country", "CN", "cnt", 3L)),
                "localeCnStats", List.of(Map.of("locale", "广东省", "cnt", 3L)),
                "networkStats", List.of(Map.of("network", "电信", "cnt", 3L)),
                "uvTypeStats", List.of(Map.of("uvType", "newUser", "cnt", 1L), Map.of("uvType", "oldUser", "cnt", 1L), Map.of("uvType", "UNKNOWN", "cnt", 1L)),
                "dimensionQuality", Map.of("uvTypeStats", Map.of("status", "UNKNOWN", "scope", "SHORT_LINK", "maxHistoryDays", 180, "unknownUv", 1L)));
        Map<String, RiskWindowDimensions> dimensions = new LinkedHashMap<>();
        for (var range : Map.of("2h", 2, "24h", 24, "7d", 168).entrySet())
            dimensions.put(range.getKey(), RiskWindowDimensions.from(range.getKey(),
                    StatsTestFixtures.NOW - Duration.ofHours(range.getValue()).toMillis(), StatsTestFixtures.NOW, row, meta));
        return StatsTestFixtures.profile().withDimensionWindows(dimensions)
                .withEvidence(new StatsEvidence("1001", 99, meta, StatsEvidence.CURRENT_RULE_VERSION));
    }
}
