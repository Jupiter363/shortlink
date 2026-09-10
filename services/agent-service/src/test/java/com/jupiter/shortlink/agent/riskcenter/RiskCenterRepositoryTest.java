package com.jupiter.shortlink.agent.riskcenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.riskcenter.model.RiskEvent;
import com.jupiter.shortlink.agent.riskcenter.model.RiskReview;
import com.jupiter.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.jupiter.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.jupiter.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.jupiter.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.jupiter.shortlink.agent.riskcommon.model.RiskEventSource;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.jupiter.shortlink.agent.riskcommon.model.RiskTargetType;
import com.jupiter.shortlink.agent.riskcommon.model.RiskWatchStatus;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class RiskCenterRepositoryTest {

    @Test
    void authorizedQueriesFilterTenantAndStableLinkBeforeDecodingEvidence() {
        JdbcTemplate jdbc = jdbcTemplate("risk_scope_" + System.nanoTime());
        JdbcRiskEventRepository events = new JdbcRiskEventRepository(jdbc);
        events.saveEvent(event("visible", 80, LocalDateTime.of(2026, 7, 10, 2, 0)));
        events.saveEvent(event("foreign", 80, LocalDateTime.of(2026, 7, 10, 3, 0)));
        jdbc.update(
                "UPDATE t_agent_risk_event SET tenant_id='1001',link_id=7 WHERE"
                    + " event_id='visible'");
        jdbc.update(
                "UPDATE t_agent_risk_event SET tenant_id='2002',link_id=8,evidence_json='not-json'"
                    + " WHERE event_id='foreign'");
        var scope =
                new com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient
                        .AuthorizedScope(
                        "1001", "4", List.of(Map.of("linkId", 7L, "gid", "moved-gid")));
        assertThat(events.listAuthorized(scope, 1, 10))
                .extracting(RiskEvent::eventId)
                .containsExactly("visible");
        assertThat(events.countAuthorized(scope)).isEqualTo(1);
        assertThat(events.findAuthorizedEvent(scope, "foreign")).isEmpty();
    }

    @Test
    void olderSnapshotCannotReplaceNewerEvidenceOrHumanAnnotation() {
        JdbcTemplate jdbc = jdbcTemplate("risk_snapshot_" + System.nanoTime());
        JdbcRiskSnapshotRepository snapshots = new JdbcRiskSnapshotRepository(jdbc);
        snapshots.upsertSnapshot(snapshot("new", 92, RiskWatchStatus.NONE));
        snapshots.updateWatchStatus(
                RiskTargetType.SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                RiskWatchStatus.WATCHING);
        jdbc.update("UPDATE t_agent_risk_snapshot SET last_scan_time='2026-07-11 02:00:00'");
        snapshots.upsertSnapshot(snapshot("old", 10, RiskWatchStatus.NONE));
        var actual =
                snapshots
                        .findByTarget(RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "abc123")
                        .orElseThrow();
        assertThat(actual.lastEventId()).isEqualTo("new");
        assertThat(actual.riskScore()).isEqualTo(92);
        assertThat(actual.watchStatus()).isEqualTo(RiskWatchStatus.WATCHING);
        assertThat(actual.policyStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void savesEventsSnapshotsAndReviews() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_center_repository");
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository =
                new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);

        eventRepository.saveEvent(event("event-old", 71, LocalDateTime.of(2026, 7, 10, 1, 0)));
        eventRepository.saveEvent(event("event-new", 92, LocalDateTime.of(2026, 7, 10, 2, 0)));

        assertThat(eventRepository.listEvents("gid-001", RiskTargetType.SHORT_LINK, 1, 10))
                .extracting(RiskEvent::eventId)
                .containsExactly("event-new", "event-old");

        snapshotRepository.upsertSnapshot(snapshot("event-old", 71, RiskWatchStatus.NONE));
        snapshotRepository.upsertSnapshot(snapshot("event-new", 92, RiskWatchStatus.WATCHING));

        assertThat(
                        snapshotRepository.findByTarget(
                                RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "abc123"))
                .isPresent()
                .get()
                .satisfies(
                        snapshot -> {
                            assertThat(snapshot.riskScore()).isEqualTo(92);
                            assertThat(snapshot.watchStatus()).isEqualTo(RiskWatchStatus.NONE);
                            assertThat(snapshot.policyStatus()).isEqualTo("UNKNOWN");
                            assertThat(snapshot.lastEventId()).isEqualTo("event-new");
                        });

        RiskReview review =
                new RiskReview(
                        "review-001",
                        "event-new",
                        RiskTargetType.SHORT_LINK,
                        "gid-001",
                        "nurl.ink",
                        "abc123",
                        "nurl.ink/abc123",
                        RiskReviewAction.WATCH,
                        "risk-admin",
                        "watch this short link",
                        LocalDateTime.of(2026, 7, 10, 2, 30));
        reviewRepository.saveReview(review);

        assertThat(reviewRepository.findByReviewId("review-001"))
                .isPresent()
                .get()
                .extracting(RiskReview::reviewAction)
                .isEqualTo(RiskReviewAction.WATCH);
    }

    private RiskEvent event(String eventId, int score, LocalDateTime eventTime) {
        return new RiskEvent(
                eventId,
                RiskTargetType.SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "nurl.ink/abc123",
                score,
                RiskLevel.fromScore(score),
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                Map.of("pv2h", 600, "topShare", 0.82),
                List.of("LIMIT_RATE"),
                "traffic spike and concentrated access",
                "trace-" + eventId,
                "session-001",
                RiskEventSource.PROFILE_BATCH,
                eventTime);
    }

    private RiskSnapshot snapshot(String eventId, int score, RiskWatchStatus watchStatus) {
        return new RiskSnapshot(
                RiskTargetType.SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "nurl.ink/abc123",
                score,
                RiskLevel.fromScore(score),
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                List.of(Map.of("type", "traffic", "value", 600)),
                watchStatus,
                "ACTIVE",
                eventId,
                "trace-" + eventId,
                LocalDateTime.of(2026, 7, 10, 2, 0));
    }

    private JdbcTemplate jdbcTemplate(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
