package com.nageoffer.shortlink.agent.riskanalysis;

import com.nageoffer.shortlink.agent.riskanalysis.job.JdbcRiskAnalysisJobRepository;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJob;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobStatus;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
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

class RiskAnalysisJobRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 10, 10, 0);

    @Test
    void createsOnlyOneJobForTheSameBatchGroupAndGraphVersion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_analysis_job_unique");
        JdbcRiskAnalysisJobRepository repository = new JdbcRiskAnalysisJobRepository(jdbcTemplate);

        assertThat(repository.createIfAbsent(pendingJob("job-001", "gid-001"))).isTrue();
        assertThat(repository.createIfAbsent(pendingJob("job-duplicate", "gid-001"))).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                from t_agent_risk_analysis_job
                where batch_id = ?
                  and gid = ?
                  and graph_name = ?
                  and graph_version = ?
                """,
                Long.class,
                "risk-profile:batch-001",
                "gid-001",
                "security-risk-graph",
                "v1"
        )).isEqualTo(1L);
        assertThat(repository.findByUniqueKey(
                "risk-profile:batch-001",
                "gid-001",
                "security-risk-graph",
                "v1"
        )).isPresent().get().extracting(RiskAnalysisJob::jobId).isEqualTo("job-001");
    }

    @Test
    void onlyTheCurrentUnexpiredBatchOwnerCanCreateAnAnalysisJob() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_analysis_job_batch_owner_fence");
        JdbcRiskAnalysisJobRepository jobRepository = new JdbcRiskAnalysisJobRepository(jdbcTemplate);
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        assertThat(batchRepository.tryAcquire(
                "risk-profile:batch-001",
                NOW.minusHours(2),
                NOW,
                "owner-a",
                NOW,
                Duration.ofMinutes(5)
        )).isTrue();
        assertThat(batchRepository.tryAcquire(
                "risk-profile:batch-001",
                NOW.minusHours(2),
                NOW,
                "owner-b",
                NOW.plusMinutes(6),
                Duration.ofMinutes(10)
        )).isTrue();

        assertThat(jobRepository.createIfAbsentIfBatchOwned(
                pendingJob("job-fenced", "gid-001"),
                "owner-a",
                NOW.plusMinutes(6)
        )).isFalse();
        assertThat(jobRepository.findByJobId("job-fenced")).isEmpty();
        assertThat(jobRepository.createIfAbsentIfBatchOwned(
                pendingJob("job-fenced", "gid-001"),
                "owner-b",
                NOW.plusMinutes(6)
        )).isTrue();
    }

    @Test
    void claimsPendingAndDueRetryJobsWithAnOwnerLease() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_claim");
        repository.createIfAbsent(pendingJob("job-pending", "gid-pending"));

        assertThat(repository.claimNext("worker-a", "risk-trace-001", NOW, Duration.ofMinutes(5), 3))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.jobId()).isEqualTo("job-pending");
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.RUNNING);
                    assertThat(job.ownerToken()).isEqualTo("worker-a");
                    assertThat(job.leaseUntil()).isEqualTo(NOW.plusMinutes(5));
                    assertThat(job.attemptCount()).isEqualTo(1);
                    assertThat(job.traceId()).isEqualTo("risk-trace-001");
                });

        assertThat(repository.recordFailure(
                "job-pending",
                "worker-a",
                "risk-trace-001",
                1,
                3,
                NOW.plusMinutes(1),
                NOW.plusMinutes(10),
                "temporary graph failure"
        )).isTrue();
        assertThat(repository.claimNext(
                "worker-b",
                "risk-trace-002",
                NOW.plusMinutes(9),
                Duration.ofMinutes(5),
                3
        )).isEmpty();
        assertThat(repository.claimNext(
                "worker-b",
                "risk-trace-002",
                NOW.plusMinutes(10),
                Duration.ofMinutes(5),
                3
        ))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.jobId()).isEqualTo("job-pending");
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.RUNNING);
                    assertThat(job.ownerToken()).isEqualTo("worker-b");
                    assertThat(job.attemptCount()).isEqualTo(2);
                });
    }

    @Test
    void recordsAttemptsAndStopsRetryingAtTheConfiguredLimit() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_failure");
        repository.createIfAbsent(pendingJob("job-failure", "gid-001"));
        repository.claimNext("worker-a", "risk-trace-001", NOW, Duration.ofMinutes(5), 2);

        assertThat(repository.recordFailure(
                "job-failure",
                "worker-a",
                "risk-trace-001",
                1,
                2,
                NOW.plusMinutes(1),
                NOW.plusMinutes(3),
                "first failure"
        )).isTrue();
        assertThat(repository.findByJobId("job-failure"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.RETRY_WAIT);
                    assertThat(job.attemptCount()).isEqualTo(1);
                    assertThat(job.nextRetryTime()).isEqualTo(NOW.plusMinutes(3));
                    assertThat(job.errorSummary()).isEqualTo("first failure");
                });

        repository.claimNext(
                "worker-b",
                "risk-trace-002",
                NOW.plusMinutes(3),
                Duration.ofMinutes(5),
                2
        );
        assertThat(repository.recordFailure(
                "job-failure",
                "worker-b",
                "risk-trace-001",
                2,
                2,
                NOW.plusMinutes(4),
                NOW.plusMinutes(8),
                "second failure"
        )).isTrue();
        assertThat(repository.findByJobId("job-failure"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.FAILED);
                    assertThat(job.attemptCount()).isEqualTo(2);
                    assertThat(job.nextRetryTime()).isNull();
                    assertThat(job.ownerToken()).isEmpty();
                    assertThat(job.leaseUntil()).isNull();
                    assertThat(job.errorSummary()).isEqualTo("second failure");
                });
    }

    @Test
    void keepsTheOriginalTraceIdAcrossRetryClaims() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_stable_trace");
        repository.createIfAbsent(pendingJob("job-stable-trace", "gid-001"));
        repository.claimNext(
                "worker-a",
                "risk-trace-original",
                NOW,
                Duration.ofMinutes(5),
                3
        );
        repository.recordFailure(
                "job-stable-trace",
                "worker-a",
                "risk-trace-original",
                1,
                3,
                NOW.plusMinutes(1),
                NOW.plusMinutes(3),
                "temporary failure"
        );

        assertThat(repository.claimNext(
                "worker-b",
                "risk-trace-replacement",
                NOW.plusMinutes(3),
                Duration.ofMinutes(5),
                3
        ))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.ownerToken()).isEqualTo("worker-b");
                    assertThat(job.attemptCount()).isEqualTo(2);
                    assertThat(job.traceId()).isEqualTo("risk-trace-original");
                });
    }

    @Test
    void expiredRunningJobCanBeTakenOverAndSuccessfulCompletionReleasesTheLease() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_expired_lease");
        repository.createIfAbsent(pendingJob("job-expired", "gid-001"));
        repository.claimNext("worker-a", "risk-trace-old", NOW, Duration.ofMinutes(5), 3);

        assertThat(repository.claimNext(
                "worker-b",
                "risk-trace-new",
                NOW.plusMinutes(6),
                Duration.ofMinutes(5),
                3
        ))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.ownerToken()).isEqualTo("worker-b");
                    assertThat(job.attemptCount()).isEqualTo(2);
                    assertThat(job.traceId()).isEqualTo("risk-trace-old");
                });
        assertThat(repository.recordSuccess(
                "job-expired",
                "worker-a",
                "risk-trace-old",
                1,
                NOW.plusMinutes(7)
        )).isFalse();
        assertThat(repository.recordSuccess(
                "job-expired",
                "worker-b",
                "risk-trace-old",
                2,
                NOW.plusMinutes(7)
        )).isTrue();
        assertThat(repository.findByJobId("job-expired"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.SUCCEEDED);
                    assertThat(job.ownerToken()).isEmpty();
                    assertThat(job.leaseUntil()).isNull();
                    assertThat(job.traceId()).isEqualTo("risk-trace-old");
                    assertThat(job.errorSummary()).isEmpty();
                });
    }

    @Test
    void renewsTheCurrentOwnerLeaseBeforeItExpires() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_renew_lease");
        repository.createIfAbsent(pendingJob("job-renew", "gid-001"));
        repository.claimNext("worker-a", "risk-trace-001", NOW, Duration.ofMinutes(5), 3);

        assertThat(repository.renewLease(
                "job-renew",
                "worker-a",
                "risk-trace-001",
                1,
                NOW.plusMinutes(4),
                Duration.ofMinutes(5)
        )).isTrue();
        assertThat(repository.findByJobId("job-renew"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.RUNNING);
                    assertThat(job.ownerToken()).isEqualTo("worker-a");
                    assertThat(job.leaseUntil()).isEqualTo(NOW.plusMinutes(9));
                    assertThat(job.updateTime()).isEqualTo(NOW.plusMinutes(4));
                });
    }

    @Test
    void expiredLeaseAtTheMaximumAttemptIsFailedInsteadOfClaimedAgain() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_exhausted_lease");
        repository.createIfAbsent(pendingJob("job-exhausted", "gid-001"));
        repository.claimNext(
                "worker-a",
                "risk-trace-original",
                NOW,
                Duration.ofMinutes(5),
                1
        );

        assertThat(repository.claimNext(
                "worker-b",
                "risk-trace-replacement",
                NOW.plusMinutes(6),
                Duration.ofMinutes(5),
                1
        )).isEmpty();
        assertThat(repository.findByJobId("job-exhausted"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.FAILED);
                    assertThat(job.attemptCount()).isEqualTo(1);
                    assertThat(job.ownerToken()).isEmpty();
                    assertThat(job.leaseUntil()).isNull();
                    assertThat(job.traceId()).isEqualTo("risk-trace-original");
                    assertThat(job.errorSummary()).contains("lease expired");
                });
    }

    @Test
    void exhaustedRetryWaitIsFailedWhenTheMaximumAttemptConfigurationIsLowered() {
        JdbcRiskAnalysisJobRepository repository = repository("risk_analysis_job_lowered_max_attempts");
        repository.createIfAbsent(pendingJob("job-lowered-max", "gid-001"));
        repository.claimNext(
                "worker-a",
                "risk-trace-original",
                NOW,
                Duration.ofMinutes(5),
                3
        );
        repository.recordFailure(
                "job-lowered-max",
                "worker-a",
                "risk-trace-original",
                1,
                3,
                NOW.plusMinutes(1),
                NOW.plusMinutes(2),
                "first failure"
        );
        repository.claimNext(
                "worker-b",
                "risk-trace-replacement",
                NOW.plusMinutes(2),
                Duration.ofMinutes(5),
                3
        );
        repository.recordFailure(
                "job-lowered-max",
                "worker-b",
                "risk-trace-original",
                2,
                3,
                NOW.plusMinutes(3),
                NOW.plusMinutes(10),
                "second failure"
        );

        assertThat(repository.claimNext(
                "worker-c",
                "risk-trace-third",
                NOW.plusMinutes(4),
                Duration.ofMinutes(5),
                2
        )).isEmpty();
        assertThat(repository.findByJobId("job-lowered-max"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.FAILED);
                    assertThat(job.attemptCount()).isEqualTo(2);
                    assertThat(job.nextRetryTime()).isNull();
                    assertThat(job.ownerToken()).isEmpty();
                    assertThat(job.leaseUntil()).isNull();
                    assertThat(job.traceId()).isEqualTo("risk-trace-original");
                    assertThat(job.errorSummary()).isEqualTo("second failure");
                });
    }

    @Test
    void claimsJobOnlyAfterItsBatchReachesASuccessfulTerminalState() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_analysis_job_batch_publication");
        JdbcRiskAnalysisJobRepository jobRepository = new JdbcRiskAnalysisJobRepository(jdbcTemplate);
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        acquireBatch(batchRepository, "risk-profile:batch-001", "batch-owner");
        jobRepository.createIfAbsent(pendingJob("job-batch-publication", "gid-001"));

        assertThat(jobRepository.claimNext(
                "worker-a",
                "risk-trace-001",
                NOW,
                Duration.ofMinutes(5),
                3
        )).isEmpty();

        assertThat(batchRepository.complete(batch(
                "risk-profile:batch-001",
                RiskProfileBatchStatus.SUCCEEDED,
                "batch-owner"
        ))).isTrue();
        assertThat(jobRepository.claimNext(
                "worker-a",
                "risk-trace-001",
                NOW.plusMinutes(1),
                Duration.ofMinutes(5),
                3
        )).isPresent();
    }

    @Test
    void neverClaimsJobFromAFailedBatch() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_analysis_job_failed_batch");
        JdbcRiskAnalysisJobRepository jobRepository = new JdbcRiskAnalysisJobRepository(jdbcTemplate);
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        acquireBatch(batchRepository, "risk-profile:batch-001", "batch-owner");
        jobRepository.createIfAbsent(pendingJob("job-failed-batch", "gid-001"));
        assertThat(batchRepository.complete(batch(
                "risk-profile:batch-001",
                RiskProfileBatchStatus.FAILED,
                "batch-owner"
        ))).isTrue();

        assertThat(jobRepository.claimNext(
                "worker-a",
                "risk-trace-001",
                NOW.plusMinutes(1),
                Duration.ofMinutes(5),
                3
        )).isEmpty();
    }

    private RiskAnalysisJob pendingJob(String jobId, String gid) {
        return new RiskAnalysisJob(
                jobId,
                "risk-profile:batch-001",
                gid,
                "security-risk-graph",
                "v1",
                RiskAnalysisJobStatus.PENDING,
                0,
                null,
                "",
                null,
                "risk-batch:risk-profile:batch-001:" + gid,
                "",
                "",
                NOW,
                NOW
        );
    }

    private JdbcRiskAnalysisJobRepository repository(String databaseName) {
        JdbcTemplate jdbcTemplate = jdbcTemplate(databaseName);
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        acquireBatch(batchRepository, "risk-profile:batch-001", "batch-owner");
        assertThat(batchRepository.complete(batch(
                "risk-profile:batch-001",
                RiskProfileBatchStatus.SUCCEEDED,
                "batch-owner"
        ))).isTrue();
        return new JdbcRiskAnalysisJobRepository(jdbcTemplate);
    }

    private void acquireBatch(
            JdbcRiskProfileBatchRepository repository,
            String batchId,
            String ownerToken
    ) {
        assertThat(repository.tryAcquire(
                batchId,
                NOW.minusHours(2),
                NOW,
                ownerToken,
                NOW,
                Duration.ofMinutes(10)
        )).isTrue();
    }

    private RiskProfileBatch batch(
            String batchId,
            RiskProfileBatchStatus status,
            String ownerToken
    ) {
        return new RiskProfileBatch(
                batchId,
                NOW.minusHours(2),
                NOW,
                status,
                ownerToken,
                NOW.plusMinutes(10),
                1,
                1,
                status == RiskProfileBatchStatus.FAILED ? 1 : 0,
                1,
                List.of(),
                NOW,
                NOW.plusMinutes(1)
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
