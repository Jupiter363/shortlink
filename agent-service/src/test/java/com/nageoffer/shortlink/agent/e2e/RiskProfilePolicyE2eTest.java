package com.nageoffer.shortlink.agent.e2e;

import com.nageoffer.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.infrastructure.persistence.JdbcGraphCheckpointStore;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.detector.ShortLinkRiskDetector;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.service.GroupRiskProfileAggregator;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import com.nageoffer.shortlink.agent.riskprofile.service.ShortLinkRiskProfileService;
import com.nageoffer.shortlink.agent.riskprofile.source.RiskStatsSourceGateway;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkStatsWindow;
import com.nageoffer.shortlink.agent.securityriskagent.graph.DefaultSecurityRiskGraphExecutor;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nageoffer.shortlink.agent.riskprofile.RiskProfileTestFixture.saveGroupProfile;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskProfilePolicyE2eTest {

    private static final Instant BATCH_NOW = Instant.parse("2026-07-10T02:00:00Z");
    private static final LocalDateTime BATCH_END_TIME = LocalDateTime.of(2026, 7, 10, 10, 0);
    private static final Clock CLOCK = Clock.fixed(BATCH_NOW, ZoneId.of("Asia/Shanghai"));
    private static final String OWNER_TOKEN = "risk-profile-e2e-owner";

    @Test
    void profileBatchFeedsSecurityRiskAgentAndPublishesLimitRatePolicyWithoutSensitiveCheckpointData() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_profile_policy_e2e_" + System.nanoTime());
        JdbcShortLinkRiskProfileRepository shortLinkRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);
        JdbcRiskActionAuditRepository auditRepository = new JdbcRiskActionAuditRepository(jdbcTemplate);
        JdbcGraphCheckpointStore checkpointStore = new JdbcGraphCheckpointStore(jdbcTemplate);
        AgentProperties properties = agentProperties();
        seedSevenDayGroupTrend(jdbcTemplate, groupRepository);
        FakeRiskStatsSourceGateway sourceGateway = new FakeRiskStatsSourceGateway(List.of(
                candidate("high001"),
                candidate("low001")
        ));
        sourceGateway.put("high001", new StatsSet(
                window(600, 50, 30, 0.82, 0.78, 0.50, 0.65, 0.60, 0.74, 0.88),
                window(900, 300, 200, null, null, null, null, null, null, null),
                window(2100, 1200, 800, null, null, null, null, null, null, null)
        ));
        sourceGateway.put("low001", new StatsSet(
                window(20, 18, 16, 0.08, 0.05, 0.18, 0.22, 0.20, 0.16, 0.08),
                window(260, 220, 180, null, null, null, null, null, null, null),
                window(1800, 1500, 1200, null, null, null, null, null, null, null)
        ));
        RiskProfileBatchService batchService = new RiskProfileBatchService(
                sourceGateway,
                new ShortLinkRiskProfileService(sourceGateway, shortLinkRepository, new ShortLinkRiskDetector()),
                groupRepository,
                new GroupRiskProfileAggregator(),
                properties
        );

        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        LocalDateTime leaseNow = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        assertThat(batchRepository.tryAcquire(
                "risk-profile:" + BATCH_NOW.getEpochSecond(),
                BATCH_END_TIME.minusHours(2),
                BATCH_END_TIME,
                OWNER_TOKEN,
                leaseNow,
                Duration.ofDays(1)
        )).isTrue();

        RiskProfileBatchResult batchResult = batchService.runOnce(BATCH_NOW, OWNER_TOKEN);

        assertThat(sourceGateway.lastSince()).isEqualTo(BATCH_NOW.minus(Duration.ofDays(7)));
        assertThat(batchResult.scannedShortLinks()).isEqualTo(2);
        assertThat(batchResult.generatedProfiles()).isEqualTo(2);
        assertThat(batchResult.abnormalCandidates())
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("high001");
        GroupRiskProfile groupProfile = groupRepository.findLatestByGid("gid-001").orElseThrow();
        assertThat(groupProfile.totalShortLinksScanned()).isEqualTo(2);
        assertThat(groupProfile.highRiskCount()).isEqualTo(1);
        assertThat(groupProfile.lowRiskCount()).isEqualTo(1);
        assertThat(groupProfile.riskTrend7d())
                .hasSize(7)
                .extracting(point -> point.date())
                .containsExactly(
                        LocalDate.of(2026, 7, 4),
                        LocalDate.of(2026, 7, 5),
                        LocalDate.of(2026, 7, 6),
                        LocalDate.of(2026, 7, 7),
                        LocalDate.of(2026, 7, 8),
                        LocalDate.of(2026, 7, 9),
                        LocalDate.of(2026, 7, 10)
                );

        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        RiskPolicyService riskPolicyService = new RiskPolicyService(
                policyRepository,
                auditRepository,
                new RiskPolicyRedisPublisher(stringRedisTemplate, CLOCK),
                properties
        );
        RiskCenterService riskCenterService = new RiskCenterService(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkRepository,
                groupRepository,
                riskPolicyService
        );
        CapturingLlmChatClient chatClient = new CapturingLlmChatClient(
                "E2E risk explanation ip=192.168.1.10 user=visitor-001"
        );
        DefaultSecurityRiskGraphExecutor executor = new DefaultSecurityRiskGraphExecutor(
                chatClient,
                checkpointStore,
                properties,
                new AgentToolRegistry(List.of()),
                shortLinkRepository,
                groupRepository,
                riskCenterService,
                riskPolicyService
        );

        AgentRunResult result = executor.execute(new SecurityRiskGraphRequest(
                "risk-profile-e2e-session",
                "risk-operator",
                "analyze profile gid=gid-001 ip=192.168.1.10 user=visitor-001",
                "trace-risk-profile-e2e"
        ));

        assertThat(result.answer())
                .contains("192.168.*.*")
                .contains("user=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");
        assertThat(result.dataSources().toString())
                .contains("risk_profile")
                .contains("risk_policy")
                .contains("high001")
                .doesNotContain("low001");
        assertThat(result.cards().toString())
                .contains("risk_profile_short_link")
                .contains("high001")
                .doesNotContain("low001");
        assertThat(result.pendingActions().toString())
                .contains("auto_limit_rate")
                .contains("executed")
                .contains("review_security_risk");
        assertThat(chatClient.request.messages().get(1).content())
                .contains("Risk signal context")
                .contains("192.168.*.*")
                .contains("user=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001");

        assertThat(eventRepository.listEvents("gid-001", RiskTargetType.SHORT_LINK, 1, 10))
                .extracting(event -> event.shortUri())
                .containsExactly("high001");
        assertThat(snapshotRepository.findByTarget(RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "high001"))
                .isPresent();
        assertThat(snapshotRepository.findByTarget(RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "low001"))
                .isEmpty();
        String policyKey = "risk:policy:short-link:rate-limit:nurl.ink:high001";
        assertThat(policyRepository.findActiveByPolicyKey(policyKey))
                .isPresent()
                .get()
                .satisfies(policy -> {
                    assertThat(policy.action()).isEqualTo(RiskPolicyAction.LIMIT_RATE);
                    assertThat(policy.gid()).isEqualTo("gid-001");
                    assertThat(policy.eventId()).isNotBlank();
                    assertThat(auditRepository.countByPolicyId(policy.policyId())).isEqualTo(1);
                });
        verify(valueOperations).set(eq(policyKey), contains("LIMIT_RATE"));

        Optional<GraphCheckpoint> checkpoint = checkpointStore.loadLatest(
                "risk-profile-e2e-session",
                "security-risk-graph",
                "v1"
        );
        assertThat(checkpoint).isPresent();
        assertThat(checkpoint.get().checkpointJson())
                .contains("profile_candidate_load")
                .contains("risk_event_persist")
                .contains("risk_auto_action")
                .contains("activatedPolicies")
                .contains("192.168.*.*")
                .contains("user=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("low001");
    }

    private AgentProperties agentProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getRisk().setHashSalt("risk-test-salt");
        properties.getRisk().getProfile().setActiveScanDays(7);
        properties.getRisk().getProfile().setTopCandidateSize(10);
        properties.getRisk().getAutoAction().setLimitRateEnabled(true);
        properties.getRisk().getAutoAction().setLimitRateMinScore(80);
        properties.getRisk().getAutoAction().setLimitRateLimit(60);
        properties.getRisk().getAutoAction().setLimitRateWindowSeconds(60);
        return properties;
    }

    private void seedSevenDayGroupTrend(
            JdbcTemplate jdbcTemplate,
            JdbcGroupRiskProfileRepository groupRepository
    ) {
        LocalDate startDate = LocalDate.of(2026, 7, 4);
        for (int index = 0; index < 7; index++) {
            LocalDate date = startDate.plusDays(index);
            LocalDateTime endTime = date.atTime(index == 6 ? 8 : 10, 0);
            int score = 35 + index;
            saveGroupProfile(jdbcTemplate, groupRepository, new GroupRiskProfile(
                    "gid-001",
                    endTime.minusHours(2),
                    endTime,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    score,
                    score,
                    score,
                    RiskLevel.fromScore(score),
                    List.of(),
                    List.of(),
                    List.of(),
                    ""
            ));
        }
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

    private static class FakeRiskStatsSourceGateway implements RiskStatsSourceGateway {

        private final List<ShortLinkActiveCandidate> candidates;
        private final Map<String, StatsSet> statsByShortUri = new LinkedHashMap<>();
        private Instant lastSince;

        private FakeRiskStatsSourceGateway(List<ShortLinkActiveCandidate> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        void put(String shortUri, StatsSet statsSet) {
            statsByShortUri.put(shortUri, statsSet);
        }

        Instant lastSince() {
            return lastSince;
        }

        @Override
        public List<ShortLinkActiveCandidate> listActiveShortLinks(Instant since) {
            lastSince = since;
            return candidates;
        }

        @Override
        public ShortLinkStatsWindow loadStatsWindow(ShortLinkActiveCandidate candidate, Instant start, Instant end) {
            WindowFixture fixture = statsByShortUri.get(candidate.shortUri()).byDuration(Duration.between(start, end));
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

    private static class CapturingLlmChatClient implements LlmChatClient {

        private final String answer;
        private DeepSeekChatRequest request;

        private CapturingLlmChatClient(String answer) {
            this.answer = answer;
        }

        @Override
        public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
            this.request = request;
            return new DeepSeekChatResponse(
                    "chat-risk-profile-e2e",
                    "deepseek-v4-flash",
                    answer,
                    "stop",
                    new DeepSeekChatResponse.Usage(10, 20, 30)
            );
        }
    }
}
