package com.nageoffer.shortlink.agent.riskanalysis.job;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskAnalysisJobLeaseManagerTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Instant NOW_INSTANT = Instant.parse("2026-07-10T02:00:00Z");

    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, SHANGHAI);

    @Test
    void renewsLeaseOnScheduledHeartbeatAndCancelsOnClose() {
        JdbcRiskAnalysisJobRepository repository = mock(JdbcRiskAnalysisJobRepository.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );
        when(repository.renewLease(
                "job-001",
                "owner-a",
                "trace-001",
                1,
                NOW,
                Duration.ofMinutes(5)
        )).thenReturn(true);
        RiskAnalysisJobLeaseManager manager = new RiskAnalysisJobLeaseManager(repository, scheduler);
        RiskAnalysisJobLeaseManager.Lease lease = manager.start(
                runningJob(),
                Duration.ofMinutes(5),
                Clock.fixed(NOW_INSTANT, SHANGHAI)
        );
        org.mockito.ArgumentCaptor<Runnable> heartbeatCaptor =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(
                heartbeatCaptor.capture(),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );

        heartbeatCaptor.getValue().run();
        lease.assertOwned();
        lease.close();

        verify(repository).renewLease(
                "job-001",
                "owner-a",
                "trace-001",
                1,
                NOW,
                Duration.ofMinutes(5)
        );
        verify(future).cancel(false);
    }

    @Test
    void marksLeaseLostWhenHeartbeatRenewalLosesTheOwnershipCas() {
        JdbcRiskAnalysisJobRepository repository = mock(JdbcRiskAnalysisJobRepository.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );
        when(repository.renewLease(
                "job-001",
                "owner-a",
                "trace-001",
                1,
                NOW,
                Duration.ofMinutes(5)
        )).thenReturn(false);
        RiskAnalysisJobLeaseManager manager = new RiskAnalysisJobLeaseManager(repository, scheduler);
        RiskAnalysisJobLeaseManager.Lease lease = manager.start(
                runningJob(),
                Duration.ofMinutes(5),
                Clock.fixed(NOW_INSTANT, SHANGHAI)
        );
        org.mockito.ArgumentCaptor<Runnable> heartbeatCaptor =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(
                heartbeatCaptor.capture(),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );

        heartbeatCaptor.getValue().run();

        assertThatThrownBy(lease::assertOwned)
                .isInstanceOf(RiskAnalysisJobLeaseLostException.class)
                .hasMessageContaining("job-001");
    }

    @Test
    void marksLeaseLostWhenHeartbeatRenewalThrows() {
        JdbcRiskAnalysisJobRepository repository = mock(JdbcRiskAnalysisJobRepository.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );
        IllegalStateException databaseFailure = new IllegalStateException("database unavailable");
        when(repository.renewLease(
                "job-001",
                "owner-a",
                "trace-001",
                1,
                NOW,
                Duration.ofMinutes(5)
        )).thenThrow(databaseFailure);
        RiskAnalysisJobLeaseManager manager = new RiskAnalysisJobLeaseManager(repository, scheduler);
        RiskAnalysisJobLeaseManager.Lease lease = manager.start(
                runningJob(),
                Duration.ofMinutes(5),
                Clock.fixed(NOW_INSTANT, SHANGHAI)
        );
        org.mockito.ArgumentCaptor<Runnable> heartbeatCaptor =
                org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(
                heartbeatCaptor.capture(),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );

        heartbeatCaptor.getValue().run();

        assertThatThrownBy(lease::assertOwned)
                .isInstanceOf(RiskAnalysisJobLeaseLostException.class)
                .hasMessageContaining("job-001")
                .hasCause(databaseFailure);
    }

    @Test
    void reportsLeaseLossWhenTheKnownLeaseDeadlineHasElapsed() {
        JdbcRiskAnalysisJobRepository repository = mock(JdbcRiskAnalysisJobRepository.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class),
                anyLong(),
                anyLong(),
                eq(TimeUnit.NANOSECONDS)
        );
        RiskAnalysisJobLeaseManager manager = new RiskAnalysisJobLeaseManager(repository, scheduler);
        RiskAnalysisJobLeaseManager.Lease lease = manager.start(
                runningJob(),
                Duration.ofMinutes(5),
                Clock.fixed(NOW_INSTANT.plus(Duration.ofMinutes(5)), SHANGHAI)
        );

        assertThatThrownBy(lease::assertOwned)
                .isInstanceOf(RiskAnalysisJobLeaseLostException.class)
                .hasMessageContaining("job-001");
    }

    private RiskAnalysisJob runningJob() {
        return new RiskAnalysisJob(
                "job-001",
                "risk-profile:batch-001",
                "gid-001",
                "security-risk-graph",
                "v1",
                RiskAnalysisJobStatus.RUNNING,
                1,
                null,
                "owner-a",
                NOW.plusMinutes(5),
                "risk-batch:risk-profile:batch-001:gid-001",
                "trace-001",
                "",
                NOW,
                NOW
        );
    }
}
