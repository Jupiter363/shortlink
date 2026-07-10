package com.nageoffer.shortlink.agent.securityriskagent.node;

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
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nageoffer.shortlink.agent.riskprofile.RiskProfileTestFixture.saveGroupProfile;
import static org.mockito.Mockito.mock;

class RiskEventPersistNodeTest {

    @Test
    void persistsEventsSnapshotsAndGroupSummaryForHighAndMediumProfiles() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_event_persist_node");
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        GroupRiskProfile groupProfile = groupProfile("gid-001", endTime);
        saveGroupProfile(jdbcTemplate, groupRepository, groupProfile);
        RiskCenterService riskCenterService = new RiskCenterService(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkRepository,
                groupRepository,
                mock(RiskPolicyService.class)
        );
        RiskEventPersistNode node = new RiskEventPersistNode(riskCenterService, groupRepository);
        ProfileRiskAnalysisContext context = new ProfileRiskAnalysisContext(
                "gid-001",
                groupProfile,
                List.of(
                        profile("gid-001", "high001", 92, endTime),
                        profile("gid-001", "medium001", 55, endTime),
                        profile("gid-001", "low001", 20, endTime)
                )
        );

        Map<String, Object> output = node.persist(context, "trace-001", "session-001", "group risk summary");

        assertThat(eventRepository.listEvents("gid-001", RiskTargetType.SHORT_LINK, 1, 10))
                .extracting(event -> event.shortUri())
                .containsExactly("medium001", "high001");
        assertThat(snapshotRepository.findByTarget(RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "high001"))
                .isPresent()
                .get()
                .extracting(snapshot -> snapshot.riskScore())
                .isEqualTo(92);
        assertThat(groupRepository.findLatestByGid("gid-001"))
                .isPresent()
                .get()
                .extracting(GroupRiskProfile::agentSummary)
                .isEqualTo("group risk summary");
        assertThat(output.get("persistedRiskEvents").toString()).contains("high001", "medium001");
    }

    @Test
    void delayedBatchUpdatesOnlyItsOwnGroupProfileSummary() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_event_persist_exact_batch_summary");
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime oldEndTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        String oldBatchId = "risk-profile:batch-old";
        String newBatchId = "risk-profile:batch-new";
        GroupRiskProfile oldGroupProfile = groupProfile("gid-001", oldEndTime).withBatchId(oldBatchId);
        GroupRiskProfile newGroupProfile = groupProfile("gid-001", oldEndTime.plusHours(2)).withBatchId(newBatchId);
        saveGroupProfile(jdbcTemplate, groupRepository, oldGroupProfile);
        saveGroupProfile(jdbcTemplate, groupRepository, newGroupProfile);
        RiskCenterService riskCenterService = new RiskCenterService(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkRepository,
                groupRepository,
                mock(RiskPolicyService.class)
        );
        RiskEventPersistNode node = new RiskEventPersistNode(riskCenterService, groupRepository);
        ProfileRiskAnalysisContext context = new ProfileRiskAnalysisContext(
                "gid-001",
                oldGroupProfile,
                List.of(profile("gid-001", "high001", 92, oldEndTime).withBatchId(oldBatchId))
        );

        node.persist(context, "trace-old", "session-old", "old batch summary");

        assertThat(groupRepository.findByBatchIdAndGid(oldBatchId, "gid-001"))
                .isPresent()
                .get()
                .extracting(GroupRiskProfile::agentSummary)
                .isEqualTo("old batch summary");
        assertThat(groupRepository.findByBatchIdAndGid(newBatchId, "gid-001")
                .map(GroupRiskProfile::agentSummary))
                .contains("");
    }

    @Test
    void retryWithSameTraceUpsertsOneDeterministicRiskEvent() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_event_persist_retry_idempotency");
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        String batchId = "risk-profile:batch-retry";
        GroupRiskProfile groupProfile = groupProfile("gid-001", endTime).withBatchId(batchId);
        saveGroupProfile(jdbcTemplate, groupRepository, groupProfile);
        RiskCenterService riskCenterService = new RiskCenterService(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkRepository,
                groupRepository,
                mock(RiskPolicyService.class)
        );
        RiskEventPersistNode node = new RiskEventPersistNode(riskCenterService, groupRepository);
        ProfileRiskAnalysisContext context = new ProfileRiskAnalysisContext(
                "gid-001",
                groupProfile,
                List.of(profile("gid-001", "high001", 92, endTime).withBatchId(batchId))
        );

        Map<String, Object> first = node.persist(context, "trace-retry", "session-retry", "risk summary");
        Map<String, Object> second = node.persist(context, "trace-retry", "session-retry", "risk summary");

        assertThat(eventRepository.listEvents("gid-001", RiskTargetType.SHORT_LINK, 1, 10))
                .hasSize(1);
        assertThat(first.get("eventIdsByTarget")).isEqualTo(second.get("eventIdsByTarget"));
    }

    private GroupRiskProfile groupProfile(String gid, LocalDateTime endTime) {
        return new GroupRiskProfile(
                gid,
                endTime.minusHours(2),
                endTime,
                3,
                1,
                1,
                1,
                0,
                0,
                55.0,
                92,
                77,
                RiskLevel.HIGH,
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                List.of(),
                List.of(new RiskTrendPoint(endTime.toLocalDate(), 77, RiskLevel.HIGH)),
                ""
        );
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
}
