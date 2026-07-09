package com.nageoffer.shortlink.agent.riskcenter;

import com.nageoffer.shortlink.agent.riskcenter.model.RiskEvent;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskReview;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskEventSource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCenterRepositoryTest {

    @Test
    void savesEventsSnapshotsAndReviews() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_center_repository");
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);

        eventRepository.saveEvent(event("event-old", 71, LocalDateTime.of(2026, 7, 10, 1, 0)));
        eventRepository.saveEvent(event("event-new", 92, LocalDateTime.of(2026, 7, 10, 2, 0)));

        assertThat(eventRepository.listEvents("gid-001", RiskTargetType.SHORT_LINK, 1, 10))
                .extracting(RiskEvent::eventId)
                .containsExactly("event-new", "event-old");

        snapshotRepository.upsertSnapshot(snapshot("event-old", 71, RiskWatchStatus.NONE));
        snapshotRepository.upsertSnapshot(snapshot("event-new", 92, RiskWatchStatus.WATCHING));

        assertThat(snapshotRepository.findByTarget(RiskTargetType.SHORT_LINK, "gid-001", "nurl.ink", "abc123"))
                .isPresent()
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.riskScore()).isEqualTo(92);
                    assertThat(snapshot.watchStatus()).isEqualTo(RiskWatchStatus.WATCHING);
                    assertThat(snapshot.lastEventId()).isEqualTo("event-new");
                });

        RiskReview review = new RiskReview(
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
                LocalDateTime.of(2026, 7, 10, 2, 30)
        );
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
                eventTime
        );
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
                LocalDateTime.of(2026, 7, 10, 2, 0)
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
