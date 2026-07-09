package com.nageoffer.shortlink.agent.securityriskagent.graph;

import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DefaultSecurityRiskGraphExecutorTest {

    @Test
    void executeRunsRiskToolsBuildsSanitizedRiskCardsAndSavesCheckpoint() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of(
                        "pv", 100,
                        "uv", 80,
                        "uip", 20,
                        "topIpStats", List.of(Map.of("ip", "192.168.1.10", "cnt", 45)),
                        "hourStats", List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 70, 30)
                ))
        );
        CapturingAgentTool accessRecordsTool = new CapturingAgentTool(
                "get_group_access_records",
                ToolResult.success(Map.of(
                        "records", List.of(Map.of(
                                "ip", "192.168.1.10",
                                "user", "visitor-001",
                                "device", "PC",
                                "browser", "Chrome"
                        )),
                        "total", 1
                ))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool, accessRecordsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-1",
                "zhangsan",
                "analyze security risk gid=g1 startDate=2026-07-01 endDate=2026-07-07 access records current=1 size=5",
                "trace-1"
        ));

        assertThat(result.answer()).isEqualTo("security risk answer");
        assertThat(result.dataSources().toString()).contains("security-risk-graph");
        assertThat(result.toolCalls().toString())
                .contains("get_group_stats")
                .contains("get_group_access_records")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.cards().toString())
                .contains("top_ip_concentration")
                .contains("hour_burst")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.pendingActions().toString())
                .contains("review_security_risk")
                .contains("pending_confirmation");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("Risk signal context")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(statsTool.context.username()).isEqualTo("zhangsan");
        assertThat(statsTool.context.arguments()).containsEntry("gid", "g1");
        assertThat(accessRecordsTool.context.arguments())
                .containsEntry("current", 1L)
                .containsEntry("size", 5L);
        assertThat(checkpointStore.saved).hasSize(1);
        assertThat(checkpointStore.saved.get(0).graphName()).isEqualTo("security-risk-graph");
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("top_ip_concentration")
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
    }

    @Test
    void executeSanitizesUserMessageAndLlmAnswerBeforePromptResponseAndCheckpoint() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient("raw ip 10.0.0.9 user=visitor-009");
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 10, "uv", 10, "topIpStats", List.of()))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-2",
                "zhangsan",
                "check gid=g1 startDate=2026-07-01 endDate=2026-07-07 ip=192.168.1.10 user=visitor-001",
                "trace-2"
        ));

        assertThat(chatClient.request.messages().get(1).content())
                .contains("192.168.*.*")
                .contains("user=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.answer())
                .contains("10.0.*.*")
                .contains("user=***")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-009");
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("10.0.*.*")
                .doesNotContain("10.0.0.9")
                .doesNotContain("visitor-009");
    }

    @Test
    void executeSanitizesToolFailureMessagesBeforePromptResponseAndCheckpoint() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        ThrowingAgentTool statsTool = new ThrowingAgentTool(
                "get_group_stats",
                "backend failed for ip=192.168.1.10 user=visitor-001"
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-3",
                "zhangsan",
                "check gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-3"
        ));

        assertThat(chatClient.request.messages().get(1).content())
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.toolCalls().toString())
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("192.168.*.*")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
    }

    @Test
    void executeDoesNotCreatePendingActionFromStringifiedMediumRiskCardContent() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(Map.of(
                        "pv", 120,
                        "uv", 20,
                        "topIpStats", List.of()
                ))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-4",
                "zhangsan",
                "check gid=g1 fullShortUrl=riskLevel=high startDate=2026-07-01 endDate=2026-07-07",
                "trace-4"
        ));

        assertThat(result.cards().toString())
                .contains("high_repeat_visits")
                .contains("riskLevel=medium")
                .contains("fullShortUrl=riskLevel=high");
        assertThat(result.pendingActions()).isEmpty();
    }

    @Test
    void executeKeepsResultAndReturnsSafeWarningWhenCheckpointSaveFails() {
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of(
                        "pv", 100,
                        "uv", 80,
                        "topIpStats", List.of(Map.of("ip", "192.168.1.10", "cnt", 45))
                ))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                new ThrowingGraphCheckpointStore("checkpoint failed ip=192.168.1.10 user=visitor-001 token=abc"),
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-5",
                "zhangsan",
                "check gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-5"
        ));

        assertThat(result.answer()).isEqualTo("security risk answer");
        assertThat(result.cards().toString()).contains("top_ip_concentration");
        assertThat(result.warnings()).contains("Graph checkpoint save failed");
        assertThat(result.traceEvents().toString())
                .contains("checkpoint_save")
                .contains("failed")
                .contains("Graph checkpoint save failed")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
    }

    @Test
    void executeReturnsSafeFallbackWhenGraphNodeFails() {
        LlmChatClient throwingChatClient = request -> {
            throw new IllegalStateException("llm crashed ip=192.168.1.10 user=visitor-001 token=abc");
        };
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 10, "uv", 10, "topIpStats", List.of()))
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                throwingChatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of(statsTool))
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-6",
                "zhangsan",
                "check gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "trace-6"
        ));

        assertThat(result.answer()).isEqualTo("Security risk graph failed.");
        assertThat(result.cards()).isEmpty();
        assertThat(result.warnings()).containsExactly("Graph execution failed");
        assertThat(result.traceEvents().toString())
                .contains("graph_execution")
                .contains("failed")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
        assertThat(checkpointStore.saved).isEmpty();
    }

    @Test
    void executeConsumesRiskProfilesPersistsEventsAndTracesProfileNodes() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("security_risk_graph_profile");
        JdbcShortLinkRiskProfileRepository shortLinkRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        RiskPolicyService riskPolicyService = mock(RiskPolicyService.class);
        RiskCenterService riskCenterService = new RiskCenterService(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkRepository,
                groupRepository,
                riskPolicyService
        );
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        ShortLinkRiskProfile highProfile = profile("gid-001", "high001", 92, endTime);
        shortLinkRepository.save(highProfile);
        groupRepository.save(groupProfile("gid-001", endTime, List.of(highProfile)));
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient();
        CapturingGraphCheckpointStore checkpointStore = new CapturingGraphCheckpointStore();
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                new AgentProperties(),
                new AgentToolRegistry(List.of()),
                shortLinkRepository,
                groupRepository,
                riskCenterService,
                riskPolicyService
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "session-profile",
                "zhangsan",
                "analyze profile gid=gid-001",
                "trace-profile"
        ));

        List<String> nodeNames = result.traceEvents().stream()
                .map(event -> String.valueOf(((Map<?, ?>) event).get("nodeName")))
                .toList();
        assertThat(nodeNames).containsExactly(
                "intake",
                "profile_candidate_load",
                "risk_tool_planning",
                "risk_scoring",
                "llm_explanation",
                "risk_event_persist",
                "risk_auto_action",
                "response_compose",
                "checkpoint_save"
        );
        assertThat(result.dataSources().toString()).contains("risk_profile");
        assertThat(result.cards().toString()).contains("risk_profile_short_link").contains("high001");
        assertThat(eventRepository.listEvents("gid-001", RiskTargetType.SHORT_LINK, 1, 10))
                .extracting(event -> event.shortUri())
                .contains("high001");
        assertThat(snapshotRepository.findByTarget(RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "high001"))
                .isPresent();
        assertThat(checkpointStore.saved.get(0).checkpointJson())
                .contains("profile_candidate_load")
                .contains("risk_event_persist")
                .contains("risk_auto_action");
    }

    private ShortLinkRiskProfile profile(String gid, String shortUri, int riskScore, LocalDateTime endTime) {
        return new ShortLinkRiskProfile(
                gid,
                "nurl.ink",
                shortUri,
                "nurl.ink/" + shortUri,
                endTime.minusHours(2),
                endTime,
                new ShortLinkRiskMetrics(600, 50, 900, 300, 2100, 1200, 8.0, 0.82, 0.78, 0.50, 0.65, 0.60, 12.0, 0.74, 0.88),
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                RiskWatchStatus.NONE,
                List.of(),
                ""
        );
    }

    private GroupRiskProfile groupProfile(String gid, LocalDateTime endTime, List<ShortLinkRiskProfile> topProfiles) {
        return new GroupRiskProfile(
                gid,
                endTime.minusHours(2),
                endTime,
                1,
                0,
                0,
                1,
                0,
                0,
                92.0,
                92,
                92,
                RiskLevel.HIGH,
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                topProfiles,
                List.of(new RiskTrendPoint(endTime.toLocalDate(), 92, RiskLevel.HIGH)),
                ""
        );
    }

    private JdbcTemplate jdbcTemplate(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static class CapturingLlmChatClient implements LlmChatClient {

        private DeepSeekChatRequest request;

        private final String answer;

        private CapturingLlmChatClient() {
            this("security risk answer");
        }

        private CapturingLlmChatClient(String answer) {
            this.answer = answer;
        }

        @Override
        public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
            this.request = request;
            return new DeepSeekChatResponse(
                    "chat-1",
                    "deepseek-v4-flash",
                    answer,
                    "stop",
                    new DeepSeekChatResponse.Usage(10, 20, 30)
            );
        }
    }

    private static class CapturingGraphCheckpointStore implements GraphCheckpointStore {

        private final List<GraphCheckpoint> saved = new ArrayList<>();

        @Override
        public void save(GraphCheckpoint checkpoint) {
            saved.add(checkpoint);
        }

        @Override
        public Optional<GraphCheckpoint> loadLatest(String threadId, String graphName, String graphVersion) {
            return Optional.empty();
        }
    }

    private static class ThrowingGraphCheckpointStore implements GraphCheckpointStore {

        private final String message;

        private ThrowingGraphCheckpointStore(String message) {
            this.message = message;
        }

        @Override
        public void save(GraphCheckpoint checkpoint) {
            throw new IllegalStateException(message);
        }

        @Override
        public Optional<GraphCheckpoint> loadLatest(String threadId, String graphName, String graphVersion) {
            return Optional.empty();
        }
    }

    private static class CapturingAgentTool implements AgentTool {

        private final String name;

        private final ToolResult result;

        private ToolContext context;

        private CapturingAgentTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "test tool", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            this.context = context;
            return result;
        }
    }

    private static class ThrowingAgentTool implements AgentTool {

        private final String name;

        private final String message;

        private ThrowingAgentTool(String name, String message) {
            this.name = name;
            this.message = message;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "throwing test tool", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            throw new IllegalStateException(message);
        }
    }
}
