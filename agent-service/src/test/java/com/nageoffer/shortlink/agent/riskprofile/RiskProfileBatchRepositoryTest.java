package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfileBatchRepositoryTest {

    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 7, 10, 8, 0);

    private static final LocalDateTime WINDOW_END = LocalDateTime.of(2026, 7, 10, 10, 0);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 10, 10, 0);

    @Test
    void onlyOneOwnerCanAcquireTheSameBatchLease() {
        JdbcRiskProfileBatchRepository repository = repository("risk_profile_batch_single_owner");

        assertThat(repository.tryAcquire(
                "risk-profile:batch-001",
                WINDOW_START,
                WINDOW_END,
                "owner-a",
                NOW,
                Duration.ofMinutes(10)
        )).isTrue();
        assertThat(repository.tryAcquire(
                "risk-profile:batch-001",
                WINDOW_START,
                WINDOW_END,
                "owner-b",
                NOW.plusMinutes(1),
                Duration.ofMinutes(10)
        )).isFalse();

        assertThat(repository.findByBatchId("risk-profile:batch-001"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.ownerToken()).isEqualTo("owner-a");
                    assertThat(batch.status()).isEqualTo(RiskProfileBatchStatus.RUNNING);
                    assertThat(batch.leaseUntil()).isEqualTo(NOW.plusMinutes(10));
                });
    }

    @Test
    void expiredLeaseCanBeTakenOverByAnotherOwner() {
        JdbcRiskProfileBatchRepository repository = repository("risk_profile_batch_lease_takeover");
        repository.tryAcquire(
                "risk-profile:batch-002",
                WINDOW_START,
                WINDOW_END,
                "owner-a",
                NOW,
                Duration.ofMinutes(5)
        );

        assertThat(repository.tryAcquire(
                "risk-profile:batch-002",
                WINDOW_START,
                WINDOW_END,
                "owner-b",
                NOW.plusMinutes(6),
                Duration.ofMinutes(10)
        )).isTrue();

        assertThat(repository.findByBatchId("risk-profile:batch-002"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.ownerToken()).isEqualTo("owner-b");
                    assertThat(batch.leaseUntil()).isEqualTo(NOW.plusMinutes(16));
                });
    }

    @Test
    void completesBatchWithPartialSuccessCountsAndFailures() {
        JdbcRiskProfileBatchRepository repository = repository("risk_profile_batch_complete");
        repository.tryAcquire(
                "risk-profile:batch-003",
                WINDOW_START,
                WINDOW_END,
                "owner-a",
                NOW,
                Duration.ofMinutes(10)
        );

        RiskProfileBatch completed = new RiskProfileBatch(
                "risk-profile:batch-003",
                WINDOW_START,
                WINDOW_END,
                RiskProfileBatchStatus.PARTIAL_SUCCESS,
                "owner-a",
                NOW.plusMinutes(10),
                3,
                2,
                1,
                1,
                List.of(new RiskProfileBatchFailure(
                        "gid-001/nurl.ink/failed",
                        "STATS_SOURCE_FAILED",
                        "business stats request failed"
                )),
                NOW,
                NOW.plusMinutes(2)
        );

        assertThat(repository.complete(completed)).isTrue();
        assertThat(repository.findByBatchId("risk-profile:batch-003"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(RiskProfileBatchStatus.PARTIAL_SUCCESS);
                    assertThat(batch.scannedCount()).isEqualTo(3);
                    assertThat(batch.generatedCount()).isEqualTo(2);
                    assertThat(batch.failedCount()).isEqualTo(1);
                    assertThat(batch.analysisJobCount()).isEqualTo(1);
                    assertThat(batch.failures()).singleElement().satisfies(failure -> {
                        assertThat(failure.targetKey()).isEqualTo("gid-001/nurl.ink/failed");
                        assertThat(failure.errorCode()).isEqualTo("STATS_SOURCE_FAILED");
                    });
                    assertThat(batch.ownerToken()).isEmpty();
                    assertThat(batch.leaseUntil()).isNull();
                    assertThat(batch.finishTime()).isEqualTo(NOW.plusMinutes(2));
                });
    }

    @Test
    void partialSuccessBatchCannotBeReacquiredAfterItsJobsArePublished() {
        JdbcRiskProfileBatchRepository repository = repository("risk_profile_batch_partial_terminal");
        repository.tryAcquire(
                "risk-profile:batch-partial",
                WINDOW_START,
                WINDOW_END,
                "owner-a",
                NOW,
                Duration.ofMinutes(10)
        );
        assertThat(repository.complete(new RiskProfileBatch(
                "risk-profile:batch-partial",
                WINDOW_START,
                WINDOW_END,
                RiskProfileBatchStatus.PARTIAL_SUCCESS,
                "owner-a",
                NOW.plusMinutes(10),
                3,
                2,
                1,
                1,
                List.of(new RiskProfileBatchFailure(
                        "gid-001/nurl.ink/failed",
                        "PROFILE_GENERATION_FAILED",
                        "stats unavailable"
                )),
                NOW,
                NOW.plusMinutes(2)
        ))).isTrue();

        assertThat(repository.tryAcquire(
                "risk-profile:batch-partial",
                WINDOW_START,
                WINDOW_END,
                "owner-b",
                NOW.plusMinutes(3),
                Duration.ofMinutes(10)
        )).isFalse();
        assertThat(repository.findByBatchId("risk-profile:batch-partial"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(RiskProfileBatchStatus.PARTIAL_SUCCESS);
                    assertThat(batch.ownerToken()).isEmpty();
                });
    }

    @Test
    void refusesToCompleteABatchWithRunningStatus() {
        JdbcRiskProfileBatchRepository repository = repository("risk_profile_batch_invalid_complete");
        repository.tryAcquire(
                "risk-profile:batch-004",
                WINDOW_START,
                WINDOW_END,
                "owner-a",
                NOW,
                Duration.ofMinutes(10)
        );

        assertThat(repository.complete(new RiskProfileBatch(
                "risk-profile:batch-004",
                WINDOW_START,
                WINDOW_END,
                RiskProfileBatchStatus.RUNNING,
                "owner-a",
                NOW.plusMinutes(10),
                1,
                1,
                0,
                0,
                List.of(),
                NOW,
                NOW.plusMinutes(1)
        ))).isFalse();
        assertThat(repository.findByBatchId("risk-profile:batch-004"))
                .isPresent()
                .get()
                .extracting(RiskProfileBatch::status)
                .isEqualTo(RiskProfileBatchStatus.RUNNING);
    }

    @Test
    void expiredOwnerCannotCompleteTheBatchBeforeTakeover() {
        JdbcRiskProfileBatchRepository repository = repository("risk_profile_batch_expired_complete");
        repository.tryAcquire(
                "risk-profile:batch-expired-complete",
                WINDOW_START,
                WINDOW_END,
                "owner-a",
                NOW,
                Duration.ofMinutes(5)
        );

        assertThat(repository.complete(new RiskProfileBatch(
                "risk-profile:batch-expired-complete",
                WINDOW_START,
                WINDOW_END,
                RiskProfileBatchStatus.SUCCEEDED,
                "owner-a",
                NOW.plusMinutes(5),
                1,
                1,
                0,
                0,
                List.of(),
                NOW,
                NOW.plusMinutes(6)
        ))).isFalse();
        assertThat(repository.findByBatchId("risk-profile:batch-expired-complete"))
                .isPresent()
                .get()
                .satisfies(batch -> {
                    assertThat(batch.status()).isEqualTo(RiskProfileBatchStatus.RUNNING);
                    assertThat(batch.ownerToken()).isEqualTo("owner-a");
                });
    }

    @Test
    void findsFailedAndExpiredRunningBatchesByOldestUpdateTime() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_profile_batch_failed_recovery");
        JdbcRiskProfileBatchRepository repository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        saveTerminalBatch(
                repository,
                "risk-profile:failed-04",
                WINDOW_END.minusHours(6),
                RiskProfileBatchStatus.FAILED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:failed-06",
                WINDOW_END.minusHours(4),
                RiskProfileBatchStatus.FAILED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:failed-08",
                WINDOW_END.minusHours(2),
                RiskProfileBatchStatus.FAILED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:partial-05",
                WINDOW_END.minusHours(5),
                RiskProfileBatchStatus.PARTIAL_SUCCESS
        );
        saveTerminalBatch(
                repository,
                "risk-profile:succeeded-07",
                WINDOW_END.minusHours(3),
                RiskProfileBatchStatus.SUCCEEDED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:failed-current",
                WINDOW_END,
                RiskProfileBatchStatus.FAILED
        );
        assertThat(repository.tryAcquire(
                "risk-profile:running-expired",
                WINDOW_END.minusHours(9),
                WINDOW_END.minusHours(7),
                "owner-expired",
                NOW,
                Duration.ofMinutes(5)
        )).isTrue();
        assertThat(repository.tryAcquire(
                "risk-profile:running-active",
                WINDOW_END.minusHours(10),
                WINDOW_END.minusHours(8),
                "owner-active",
                NOW.plusMinutes(15),
                Duration.ofMinutes(10)
        )).isTrue();
        setUpdateTime(jdbcTemplate, "risk-profile:failed-04", NOW.minusHours(4));
        setUpdateTime(jdbcTemplate, "risk-profile:running-expired", NOW.minusHours(3));
        setUpdateTime(jdbcTemplate, "risk-profile:failed-06", NOW.minusHours(2));
        setUpdateTime(jdbcTemplate, "risk-profile:failed-08", NOW.minusHours(1));

        assertThat(repository.findRecoverableBefore(WINDOW_END, NOW.plusMinutes(15), 3))
                .extracting(RiskProfileBatch::batchId)
                .containsExactly(
                        "risk-profile:failed-04",
                        "risk-profile:running-expired",
                        "risk-profile:failed-06"
                );
    }

    @Test
    void recentlyRetriedFailureMovesBehindOlderUnattemptedFailures() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_profile_batch_failed_recovery_rotation");
        JdbcRiskProfileBatchRepository repository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        saveTerminalBatch(
                repository,
                "risk-profile:failed-a",
                WINDOW_END.minusHours(6),
                RiskProfileBatchStatus.FAILED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:failed-b",
                WINDOW_END.minusHours(4),
                RiskProfileBatchStatus.FAILED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:failed-c",
                WINDOW_END.minusHours(2),
                RiskProfileBatchStatus.FAILED
        );
        setUpdateTime(jdbcTemplate, "risk-profile:failed-a", NOW.minusHours(3));
        setUpdateTime(jdbcTemplate, "risk-profile:failed-b", NOW.minusHours(2));
        setUpdateTime(jdbcTemplate, "risk-profile:failed-c", NOW.minusHours(1));

        assertThat(repository.findRecoverableBefore(WINDOW_END, NOW.plusMinutes(15), 2))
                .extracting(RiskProfileBatch::batchId)
                .containsExactly("risk-profile:failed-a", "risk-profile:failed-b");

        setUpdateTime(jdbcTemplate, "risk-profile:failed-a", NOW.plusMinutes(1));

        assertThat(repository.findRecoverableBefore(WINDOW_END, NOW.plusMinutes(15), 2))
                .extracting(RiskProfileBatch::batchId)
                .containsExactly("risk-profile:failed-b", "risk-profile:failed-c");
    }

    @Test
    void malformedRecoveryCandidateMovesBehindTheNextCandidateAfterAnAttempt() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_profile_batch_malformed_recovery_rotation");
        JdbcRiskProfileBatchRepository repository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        saveTerminalBatch(
                repository,
                "risk-profile:malformed-window",
                WINDOW_END.minusHours(6),
                RiskProfileBatchStatus.FAILED
        );
        saveTerminalBatch(
                repository,
                "risk-profile:" + WINDOW_END.minusHours(4)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .getEpochSecond(),
                WINDOW_END.minusHours(4),
                RiskProfileBatchStatus.FAILED
        );
        jdbcTemplate.update("""
                        update t_agent_risk_profile_batch
                        set window_start = ?
                        where batch_id = ?
                        """,
                WINDOW_END.minusHours(9),
                "risk-profile:malformed-window"
        );
        setUpdateTime(jdbcTemplate, "risk-profile:malformed-window", NOW.minusHours(2));
        setUpdateTime(
                jdbcTemplate,
                "risk-profile:" + WINDOW_END.minusHours(4)
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .getEpochSecond(),
                NOW.minusHours(1)
        );

        RiskProfileBatch malformed = repository.findRecoverableBefore(
                        WINDOW_END,
                        NOW.plusMinutes(15),
                        1
                )
                .get(0);
        assertThat(malformed.batchId()).isEqualTo("risk-profile:malformed-window");

        repository.recordRecoveryAttempt(
                malformed.batchId(),
                NOW.plusMinutes(16)
        );

        assertThat(repository.findRecoverableBefore(WINDOW_END, NOW.plusMinutes(16), 1))
                .extracting(RiskProfileBatch::batchId)
                .containsExactly(
                        "risk-profile:" + WINDOW_END.minusHours(4)
                                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                                .toInstant()
                                .getEpochSecond()
                );
    }

    private void setUpdateTime(JdbcTemplate jdbcTemplate, String batchId, LocalDateTime updateTime) {
        jdbcTemplate.update(
                "update t_agent_risk_profile_batch set update_time = ? where batch_id = ?",
                updateTime,
                batchId
        );
    }

    private void saveTerminalBatch(
            JdbcRiskProfileBatchRepository repository,
            String batchId,
            LocalDateTime windowEnd,
            RiskProfileBatchStatus status
    ) {
        String ownerToken = "owner-" + batchId;
        assertThat(repository.tryAcquire(
                batchId,
                windowEnd.minusHours(2),
                windowEnd,
                ownerToken,
                NOW,
                Duration.ofMinutes(10)
        )).isTrue();
        assertThat(repository.complete(new RiskProfileBatch(
                batchId,
                windowEnd.minusHours(2),
                windowEnd,
                status,
                ownerToken,
                NOW.plusMinutes(10),
                1,
                status == RiskProfileBatchStatus.FAILED ? 0 : 1,
                status == RiskProfileBatchStatus.SUCCEEDED ? 0 : 1,
                0,
                List.of(),
                NOW,
                NOW.plusMinutes(1)
        ))).isTrue();
    }

    private JdbcRiskProfileBatchRepository repository(String databaseName) {
        return new JdbcRiskProfileBatchRepository(jdbcTemplate(databaseName));
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
