package com.jupiter.shortlink.agent.riskprofile;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.harness.checkpoint.MysqlGraphCompileConfigFactory;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.service.GroupRiskProfileAggregator;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RiskProfileCheckpointSerializationTest {
    @Test
    void emptyProfileContextAndRealRequestInputSurviveCloningAndNativeMysqlEnvelope() throws Exception {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sessionId", "empty-request");
        input.put("username", "Jupiter");
        input.put("principal", new com.jupiter.shortlink.agent.harness.security.AgentPrincipal("2", "Jupiter", 1, false).toState());
        input.put("profileRiskContext", ProfileRiskAnalysisContext.empty());
        input.put("profileRiskDataSource", Map.of());
        input.put("toolExecutions", List.of());
        input.put("cards", List.of());
        input.put("analysisInput", Map.of());
        input.put("message", "诊断默认分组近24小时风险");
        input.put("traceId", "empty-profile-trace");
        var serializer = AgentStateSerializerFactory.create();
        var dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        var saver = com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver.builder()
                .dataSource(dataSource).stateSerializer(serializer)
                .createOption(com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption.CREATE_IF_NOT_EXISTS).build();
        for (int round = 0; round < 3; round++) {
            input = serializer.cloneObject(input).data();
            assertThat(input.get("profileRiskContext")).isInstanceOf(ProfileRiskAnalysisContext.class);
            var restored = (ProfileRiskAnalysisContext) input.get("profileRiskContext");
            assertThat(restored.isEmpty()).isTrue();
            assertThat(restored.groupProfile()).isNull();
            String envelope = org.springframework.test.util.ReflectionTestUtils.invokeMethod(saver, "encodeState", input);
            String base64 = new com.fasterxml.jackson.databind.ObjectMapper().readTree(envelope).get("binaryPayload").asText();
            input = org.springframework.test.util.ReflectionTestUtils.invokeMethod(saver, "decodeState", base64.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertThat(((ProfileRiskAnalysisContext) input.get("profileRiskContext")).isEmpty()).isTrue();
        }
    }

    private ProfileRiskAnalysisContext context() {
        var profile = StatsTestFixtures.profile();
        var group = new GroupRiskProfileAggregator().aggregate("g1", List.of(profile),
                List.of(new RiskTrendPoint(profile.profileWindowEnd().toLocalDate(), 90, RiskLevel.HIGH)));
        return new ProfileRiskAnalysisContext("g1", group, List.of(profile));
    }

    @Test
    void pinnedUpstreamSerializerLosesTypedEnumListsBetweenCheckpointWrites() throws Exception {
        var upstream = new SpringAIJacksonStateSerializer(OverAllState::new);
        var first = upstream.dataToBytes(Map.of("profileRiskContext", context()));
        var restored = upstream.dataFromBytes(first);
        var context = (ProfileRiskAnalysisContext) restored.get("profileRiskContext");
        assertThat((List<?>) context.groupProfile().groupReasonCodes()).allSatisfy(value -> assertThat(value).isInstanceOf(String.class));
        assertThatThrownBy(() -> upstream.dataToBytes(restored)).hasMessageContaining("java.lang.Enum");
    }

    @Test
    void fixedSerializerReadsExistingWireFormatAndKeepsNestedTypesAcrossThreeRestores() throws Exception {
        var upstream = new SpringAIJacksonStateSerializer(OverAllState::new);
        byte[] checkpoint = upstream.dataToBytes(Map.of("profileRiskContext", context(),
                "cards", List.of(Map.of("type", "risk_profile", "pv", 3_000_000_000L)), "warnings", List.of("stale")));
        for (int round = 0; round < 3; round++) {
            var fixed = AgentStateSerializerFactory.create();
            Map<String, Object> state = fixed.dataFromBytes(checkpoint);
            var restored = (ProfileRiskAnalysisContext) state.get("profileRiskContext");
            assertTypedContext(restored);
            assertThat(restored.groupProfile().topRiskShortLinks().get(0).evidence().tenantId()).isEqualTo("1001");
            assertThat(restored.groupProfile().riskTrend7d().get(0).riskLevel()).isEqualTo(RiskLevel.HIGH);
            assertThat(state.get("warnings")).isEqualTo(List.of("stale"));
            assertThat((List<?>) state.get("cards")).hasSize(1);
            checkpoint = fixed.dataToBytes(state);
        }
    }

    @Test
    void realRepositoryRoundtripAndGraphNodeCloningKeepEnumsAndRiskEvidenceTyped() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:risk_checkpoint_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var links = new JdbcShortLinkRiskProfileRepository(jdbc);
        var groups = new JdbcGroupRiskProfileRepository(jdbc);
        var original = context();
        RiskProfileTestFixture.saveShortLinkProfile(jdbc, links, original.shortLinkProfiles().get(0));
        RiskProfileTestFixture.saveGroupProfile(jdbc, groups, original.groupProfile());
        var persisted = new ProfileRiskAnalysisContext("g1",
                groups.findByBatchIdAndGid(original.batchId(), "g1").orElseThrow(),
                links.findByBatchIdAndGid(original.batchId(), "g1"));
        assertTypedContext(persisted);
        var graph = new StateGraph("risk-checkpoint-roundtrip", Map::of, AgentStateSerializerFactory.create())
                .addNode("load", AsyncNodeAction.node_async(state -> Map.of("profileRiskContext", persisted)))
                .addNode("check", AsyncNodeAction.node_async(state -> {
                    assertTypedContext((ProfileRiskAnalysisContext) state.data().get("profileRiskContext"));
                    return Map.of("verified", true);
                }))
                .addEdge(StateGraph.START, "load").addEdge("load", "check").addEdge("check", StateGraph.END)
                .compile(MysqlGraphCompileConfigFactory.create(MemorySaver.builder().build()));
        var result = graph.invoke(new LinkedHashMap<>(), RunnableConfig.builder().threadId("risk-checkpoint").build()).orElseThrow();
        assertThat(result.data().get("verified")).isEqualTo(true);
        Map<String, Object> state = result.data();
        for (int round = 0; round < 3; round++) {
            var fixed = AgentStateSerializerFactory.create();
            state = fixed.dataFromBytes(fixed.dataToBytes(state));
            assertTypedContext((ProfileRiskAnalysisContext) state.get("profileRiskContext"));
        }
    }

    private void assertTypedContext(ProfileRiskAnalysisContext context) {
        assertThat(context.groupProfile().groupReasonCodes()).isNotEmpty();
        assertThat((List<?>) context.groupProfile().groupReasonCodes()).allSatisfy(value -> assertThat(value).isInstanceOf(RiskReasonCode.class));
        assertThat(context.shortLinkProfiles()).hasSize(1);
        assertThat(context.shortLinkProfiles().get(0).reasonCodes()).contains(RiskReasonCode.TRAFFIC_SPIKE);
        assertThat(context.shortLinkProfiles().get(0).metrics().pv2h()).isEqualTo(3_000_000_000L);
        assertThat(context.shortLinkProfiles().get(0).evidence().tenantId()).isEqualTo("1001");
        assertThat(context.toDataSource()).containsKey("groupProfile");
    }
}
