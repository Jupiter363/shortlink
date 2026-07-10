package com.nageoffer.shortlink.agent.riskanalysis;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.riskanalysis.job.JdbcRiskAnalysisJobRepository;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJob;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobLeaseManager;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobLeaseLostException;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobStatus;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobWorker;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphExecutor;
import com.nageoffer.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskAnalysisJobWorkerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Instant NOW_INSTANT = Instant.parse("2026-07-10T02:00:00Z");

    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, SHANGHAI);

    private static final String BATCH_ID = "risk-profile:batch-001";

    @Test
    void executesGraphWithFixedJobSessionAndExactBatchCandidatesThenMarksSuccess() {
        JdbcRiskAnalysisJobRepository jobRepository = mock(JdbcRiskAnalysisJobRepository.class);
        SecurityRiskGraphExecutor graphExecutor = mock(SecurityRiskGraphExecutor.class);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = mock(JdbcShortLinkRiskProfileRepository.class);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        RiskAnalysisJob claimed = runningJob(1, "owner-a", "trace-001");
        ShortLinkRiskProfile bSame = profile("b.example", "same", 90, 500);
        ShortLinkRiskProfile aLast = profile("a.example", "z-last", 90, 500);
        ShortLinkRiskProfile aFirst = profile("a.example", "a-first", 90, 500);
        ShortLinkRiskProfile low = profile("low", 20);
        when(jobRepository.claimNext("owner-a", "trace-001", NOW, Duration.ofMinutes(5), 3))
                .thenReturn(Optional.of(claimed));
        when(groupRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(Optional.of(groupProfile()));
        when(shortLinkRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(List.of(low, bSame, aLast, aFirst));
        when(graphExecutor.execute(any())).thenReturn(runResult(claimed));
        when(jobRepository.recordSuccess(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                NOW
        )).thenReturn(true);
        RiskAnalysisJobWorker worker = worker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                Clock.fixed(NOW_INSTANT, SHANGHAI),
                3,
                () -> "owner-a",
                () -> "trace-001"
        );

        assertThat(worker.runNext()).isTrue();

        ArgumentCaptor<SecurityRiskGraphRequest> requestCaptor =
                ArgumentCaptor.forClass(SecurityRiskGraphRequest.class);
        verify(graphExecutor).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(request -> {
            assertThat(request.sessionId()).isEqualTo(claimed.sessionId());
            assertThat(request.traceId()).isEqualTo(claimed.traceId());
            assertThat(request.username()).isEqualTo("risk-analysis-worker");
            assertThat(request.analysisInput()).isNotNull();
            assertThat(request.analysisInput().batchId()).isEqualTo(BATCH_ID);
            assertThat(request.analysisInput().gid()).isEqualTo("gid-001");
            assertThat(request.analysisInput().profileWindowEnd()).isEqualTo(groupProfile().profileWindowEnd());
            assertThat(request.analysisInput().candidates())
                    .extracting(candidate -> candidate.domain() + "/" + candidate.shortUri())
                    .containsExactly(
                            "a.example/a-first",
                            "a.example/z-last",
                            "b.example/same"
                    );
        });
        verify(jobRepository).recordSuccess(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                NOW
        );
        verify(jobRepository).claimNext(
                "owner-a",
                "trace-001",
                NOW,
                Duration.ofMinutes(5),
                3
        );
    }

    @Test
    void graphFailuresRetryWithBackoffAndStopAtTheMaximumAttemptCount() {
        JdbcRiskAnalysisJobRepository jobRepository = jobRepository("risk_analysis_worker_retry");
        JdbcShortLinkRiskProfileRepository shortLinkRepository = mock(JdbcShortLinkRiskProfileRepository.class);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        SecurityRiskGraphExecutor graphExecutor = mock(SecurityRiskGraphExecutor.class);
        jobRepository.createIfAbsent(pendingJob());
        when(groupRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(Optional.of(groupProfile()));
        when(shortLinkRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(List.of(profile("high", 92)));
        when(graphExecutor.execute(any()))
                .thenThrow(new IllegalStateException("failed ip=192.168.1.10 user=visitor-001 token=abc"));

        RiskAnalysisJobWorker firstAttempt = worker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                Clock.fixed(NOW_INSTANT, SHANGHAI),
                2,
                () -> "owner-a",
                () -> "trace-001"
        );

        assertThat(firstAttempt.runNext()).isTrue();
        assertThat(jobRepository.findByJobId("job-001"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.RETRY_WAIT);
                    assertThat(job.attemptCount()).isEqualTo(1);
                    assertThat(job.nextRetryTime()).isEqualTo(NOW.plusSeconds(30));
                    assertThat(job.errorSummary())
                            .contains("192.168.*.*")
                            .doesNotContain("192.168.1.10")
                            .doesNotContain("visitor-001")
                            .doesNotContain("abc");
                });

        Instant retryInstant = NOW_INSTANT.plusSeconds(30);
        RiskAnalysisJobWorker secondAttempt = worker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                Clock.fixed(retryInstant, SHANGHAI),
                2,
                () -> "owner-b",
                () -> "trace-002"
        );

        assertThat(secondAttempt.runNext()).isTrue();
        assertThat(jobRepository.findByJobId("job-001"))
                .isPresent()
                .get()
                .satisfies(job -> {
                    assertThat(job.status()).isEqualTo(RiskAnalysisJobStatus.FAILED);
                    assertThat(job.attemptCount()).isEqualTo(2);
                    assertThat(job.nextRetryTime()).isNull();
                    assertThat(job.ownerToken()).isEmpty();
                    assertThat(job.leaseUntil()).isNull();
                });
    }

    @Test
    void usesGraphCompletionTimeForFailureAndRetryScheduling() {
        JdbcRiskAnalysisJobRepository jobRepository = mock(JdbcRiskAnalysisJobRepository.class);
        SecurityRiskGraphExecutor graphExecutor = mock(SecurityRiskGraphExecutor.class);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = mock(JdbcShortLinkRiskProfileRepository.class);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        Clock clock = mock(Clock.class);
        Instant completionInstant = NOW_INSTANT.plusSeconds(120);
        LocalDateTime completionTime = LocalDateTime.ofInstant(completionInstant, SHANGHAI);
        RiskAnalysisJob claimed = runningJob(1, "owner-a", "trace-001");
        when(clock.instant()).thenReturn(NOW_INSTANT, completionInstant);
        when(clock.getZone()).thenReturn(SHANGHAI);
        when(jobRepository.claimNext("owner-a", "trace-001", NOW, Duration.ofMinutes(5), 3))
                .thenReturn(Optional.of(claimed));
        when(groupRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(Optional.of(groupProfile()));
        when(shortLinkRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(List.of(profile("high", 92)));
        when(graphExecutor.execute(any())).thenThrow(new IllegalStateException("graph failed"));
        when(jobRepository.recordFailure(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                3,
                completionTime,
                completionTime.plusSeconds(30),
                "graph failed"
        )).thenReturn(true);
        RiskAnalysisJobWorker worker = worker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                clock,
                3,
                () -> "owner-a",
                () -> "trace-001"
        );

        assertThat(worker.runNext()).isTrue();

        verify(jobRepository).recordFailure(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                3,
                completionTime,
                completionTime.plusSeconds(30),
                "graph failed"
        );
    }

    @Test
    void reportsLeaseLossWhenSuccessfulCompletionCannotBeRecorded() {
        JdbcRiskAnalysisJobRepository jobRepository = mock(JdbcRiskAnalysisJobRepository.class);
        SecurityRiskGraphExecutor graphExecutor = mock(SecurityRiskGraphExecutor.class);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = mock(JdbcShortLinkRiskProfileRepository.class);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        RiskAnalysisJob claimed = runningJob(1, "owner-a", "trace-001");
        when(jobRepository.claimNext("owner-a", "trace-001", NOW, Duration.ofMinutes(5), 3))
                .thenReturn(Optional.of(claimed));
        when(groupRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(Optional.of(groupProfile()));
        when(shortLinkRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(List.of(profile("high", 92)));
        when(graphExecutor.execute(any())).thenReturn(runResult(claimed));
        when(jobRepository.recordSuccess(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                NOW
        )).thenReturn(false);
        RiskAnalysisJobWorker worker = worker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                Clock.fixed(NOW_INSTANT, SHANGHAI),
                3,
                () -> "owner-a",
                () -> "trace-001"
        );

        assertThatThrownBy(worker::runNext)
                .isInstanceOf(RiskAnalysisJobLeaseLostException.class)
                .hasMessageContaining(claimed.jobId());
    }

    @Test
    void reportsLeaseLossWhenFailureCompletionCannotBeRecorded() {
        JdbcRiskAnalysisJobRepository jobRepository = mock(JdbcRiskAnalysisJobRepository.class);
        SecurityRiskGraphExecutor graphExecutor = mock(SecurityRiskGraphExecutor.class);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = mock(JdbcShortLinkRiskProfileRepository.class);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        RiskAnalysisJob claimed = runningJob(1, "owner-a", "trace-001");
        IllegalStateException graphFailure = new IllegalStateException("graph failed");
        when(jobRepository.claimNext("owner-a", "trace-001", NOW, Duration.ofMinutes(5), 3))
                .thenReturn(Optional.of(claimed));
        when(groupRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(Optional.of(groupProfile()));
        when(shortLinkRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(List.of(profile("high", 92)));
        when(graphExecutor.execute(any())).thenThrow(graphFailure);
        when(jobRepository.recordFailure(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                3,
                NOW,
                NOW.plusSeconds(30),
                "graph failed"
        )).thenReturn(false);
        RiskAnalysisJobWorker worker = worker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                Clock.fixed(NOW_INSTANT, SHANGHAI),
                3,
                () -> "owner-a",
                () -> "trace-001"
        );

        assertThatThrownBy(worker::runNext)
                .isInstanceOf(RiskAnalysisJobLeaseLostException.class)
                .hasMessageContaining(claimed.jobId())
                .hasCause(graphFailure);
    }

    @Test
    void keepsLeaseAliveUntilGraphCompletionIsPersisted() {
        JdbcRiskAnalysisJobRepository jobRepository = mock(JdbcRiskAnalysisJobRepository.class);
        SecurityRiskGraphExecutor graphExecutor = mock(SecurityRiskGraphExecutor.class);
        JdbcShortLinkRiskProfileRepository shortLinkRepository = mock(JdbcShortLinkRiskProfileRepository.class);
        JdbcGroupRiskProfileRepository groupRepository = mock(JdbcGroupRiskProfileRepository.class);
        RiskAnalysisJobLeaseManager leaseManager = mock(RiskAnalysisJobLeaseManager.class);
        RiskAnalysisJobLeaseManager.Lease lease = mock(RiskAnalysisJobLeaseManager.Lease.class);
        Clock clock = Clock.fixed(NOW_INSTANT, SHANGHAI);
        RiskAnalysisJob claimed = runningJob(1, "owner-a", "trace-001");
        when(jobRepository.claimNext("owner-a", "trace-001", NOW, Duration.ofMinutes(5), 3))
                .thenReturn(Optional.of(claimed));
        when(groupRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(Optional.of(groupProfile()));
        when(shortLinkRepository.findByBatchIdAndGid(BATCH_ID, "gid-001"))
                .thenReturn(List.of(profile("high", 92)));
        when(graphExecutor.execute(any())).thenReturn(runResult(claimed));
        when(jobRepository.recordSuccess(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                NOW
        )).thenReturn(true);
        when(leaseManager.start(claimed, Duration.ofMinutes(5), clock)).thenReturn(lease);
        RiskAnalysisJobWorker worker = new RiskAnalysisJobWorker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                leaseManager,
                clock,
                Duration.ofMinutes(5),
                3,
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                10,
                "risk-analysis-worker",
                () -> "owner-a",
                () -> "trace-001"
        );

        assertThat(worker.runNext()).isTrue();

        InOrder order = inOrder(leaseManager, graphExecutor, lease, jobRepository);
        order.verify(leaseManager).start(claimed, Duration.ofMinutes(5), clock);
        order.verify(graphExecutor).execute(any(SecurityRiskGraphRequest.class));
        order.verify(lease).assertOwned();
        order.verify(jobRepository).recordSuccess(
                claimed.jobId(),
                claimed.ownerToken(),
                claimed.traceId(),
                claimed.attemptCount(),
                NOW
        );
        order.verify(lease).close();
    }

    private RiskAnalysisJobWorker worker(
            JdbcRiskAnalysisJobRepository jobRepository,
            SecurityRiskGraphExecutor graphExecutor,
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            Clock clock,
            int maxAttempts,
            java.util.function.Supplier<String> ownerTokenSupplier,
            java.util.function.Supplier<String> traceIdSupplier
    ) {
        RiskAnalysisJobLeaseManager leaseManager = mock(RiskAnalysisJobLeaseManager.class);
        RiskAnalysisJobLeaseManager.Lease lease = mock(RiskAnalysisJobLeaseManager.Lease.class);
        when(leaseManager.start(
                any(RiskAnalysisJob.class),
                eq(Duration.ofMinutes(5)),
                eq(clock)
        )).thenReturn(lease);
        return new RiskAnalysisJobWorker(
                jobRepository,
                graphExecutor,
                shortLinkRepository,
                groupRepository,
                leaseManager,
                clock,
                Duration.ofMinutes(5),
                maxAttempts,
                Duration.ofSeconds(30),
                Duration.ofMinutes(10),
                10,
                "risk-analysis-worker",
                ownerTokenSupplier,
                traceIdSupplier
        );
    }

    private RiskAnalysisJob pendingJob() {
        return new RiskAnalysisJob(
                "job-001",
                BATCH_ID,
                "gid-001",
                "security-risk-graph",
                "v1",
                RiskAnalysisJobStatus.PENDING,
                0,
                null,
                "",
                null,
                "risk-batch:" + BATCH_ID + ":gid-001",
                "",
                "",
                NOW,
                NOW
        );
    }

    private RiskAnalysisJob runningJob(int attemptCount, String ownerToken, String traceId) {
        return new RiskAnalysisJob(
                "job-001",
                BATCH_ID,
                "gid-001",
                "security-risk-graph",
                "v1",
                RiskAnalysisJobStatus.RUNNING,
                attemptCount,
                null,
                ownerToken,
                NOW.plusMinutes(5),
                "risk-batch:" + BATCH_ID + ":gid-001",
                traceId,
                "",
                NOW,
                NOW
        );
    }

    private AgentRunResult runResult(RiskAnalysisJob job) {
        return new AgentRunResult(
                job.sessionId(),
                job.traceId(),
                "ok",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private GroupRiskProfile groupProfile() {
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 10, 0);
        return new GroupRiskProfile(
                "gid-001",
                endTime.minusHours(2),
                endTime,
                3,
                1,
                1,
                1,
                0,
                0,
                60.0,
                92,
                82,
                RiskLevel.HIGH,
                List.of(RiskReasonCode.TRAFFIC_SPIKE),
                List.of(),
                List.of(new RiskTrendPoint(endTime.toLocalDate(), 82, RiskLevel.HIGH)),
                "",
                BATCH_ID
        );
    }

    private ShortLinkRiskProfile profile(String shortUri, int riskScore) {
        return profile("nurl.ink", shortUri, riskScore, 600);
    }

    private ShortLinkRiskProfile profile(
            String domain,
            String shortUri,
            int riskScore,
            int pv2h
    ) {
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 10, 0);
        return new ShortLinkRiskProfile(
                "gid-001",
                domain,
                shortUri,
                domain + "/" + shortUri,
                endTime.minusHours(2),
                endTime,
                new ShortLinkRiskMetrics(
                        pv2h,
                        50,
                        900,
                        300,
                        2100,
                        1200,
                        8.0,
                        0.82,
                        0.78,
                        0.50,
                        0.65,
                        0.60,
                        12.0,
                        0.74,
                        0.88
                ),
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE),
                RiskWatchStatus.NONE,
                List.of(),
                "",
                BATCH_ID
        );
    }

    private JdbcTemplate jdbcTemplate(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private JdbcRiskAnalysisJobRepository jobRepository(String databaseName) {
        JdbcTemplate jdbcTemplate = jdbcTemplate(databaseName);
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        assertThat(batchRepository.tryAcquire(
                BATCH_ID,
                NOW.minusHours(2),
                NOW,
                "batch-owner",
                NOW,
                Duration.ofMinutes(5)
        )).isTrue();
        assertThat(batchRepository.complete(new RiskProfileBatch(
                BATCH_ID,
                NOW.minusHours(2),
                NOW,
                RiskProfileBatchStatus.SUCCEEDED,
                "batch-owner",
                NOW.plusMinutes(5),
                0,
                0,
                0,
                0,
                List.of(),
                NOW,
                NOW
        ))).isTrue();
        return new JdbcRiskAnalysisJobRepository(jdbcTemplate);
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
