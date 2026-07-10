package com.nageoffer.shortlink.agent.riskanalysis.job;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RiskAnalysisJobLeaseManager {

    private final JdbcRiskAnalysisJobRepository repository;

    private final ScheduledExecutorService scheduler;

    @Autowired
    public RiskAnalysisJobLeaseManager(JdbcRiskAnalysisJobRepository repository) {
        this(repository, newScheduler());
    }

    RiskAnalysisJobLeaseManager(
            JdbcRiskAnalysisJobRepository repository,
            ScheduledExecutorService scheduler
    ) {
        this.repository = repository;
        this.scheduler = scheduler;
    }

    public Lease start(RiskAnalysisJob job, Duration leaseDuration, Clock clock) {
        ManagedLease lease = new ManagedLease(job, leaseDuration, clock);
        long heartbeatDelayNanos = Math.max(1L, leaseDuration.dividedBy(3).toNanos());
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(
                lease::renew,
                heartbeatDelayNanos,
                heartbeatDelayNanos,
                TimeUnit.NANOSECONDS
        );
        lease.attach(heartbeat);
        return lease;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    public interface Lease extends AutoCloseable {

        void assertOwned();

        @Override
        void close();
    }

    private final class ManagedLease implements Lease {

        private final RiskAnalysisJob job;

        private final Duration leaseDuration;

        private final Clock clock;

        private final AtomicReference<LocalDateTime> leaseUntil;

        private final AtomicReference<RiskAnalysisJobLeaseLostException> leaseLoss = new AtomicReference<>();

        private ScheduledFuture<?> heartbeat;

        private ManagedLease(RiskAnalysisJob job, Duration leaseDuration, Clock clock) {
            this.job = job;
            this.leaseDuration = leaseDuration;
            this.clock = clock;
            this.leaseUntil = new AtomicReference<>(job.leaseUntil());
        }

        private void attach(ScheduledFuture<?> heartbeat) {
            this.heartbeat = heartbeat;
        }

        private void renew() {
            LocalDateTime now = LocalDateTime.now(clock);
            try {
                boolean renewed = repository.renewLease(
                        job.jobId(),
                        job.ownerToken(),
                        job.traceId(),
                        job.attemptCount(),
                        now,
                        leaseDuration
                );
                if (renewed) {
                    leaseUntil.set(now.plus(leaseDuration));
                } else {
                    leaseLoss.compareAndSet(null, new RiskAnalysisJobLeaseLostException(job.jobId()));
                }
            } catch (RuntimeException ex) {
                leaseLoss.compareAndSet(null, new RiskAnalysisJobLeaseLostException(job.jobId(), ex));
            }
        }

        @Override
        public void assertOwned() {
            RiskAnalysisJobLeaseLostException exception = leaseLoss.get();
            if (exception != null) {
                throw exception;
            }
            LocalDateTime deadline = leaseUntil.get();
            if (deadline == null || !LocalDateTime.now(clock).isBefore(deadline)) {
                RiskAnalysisJobLeaseLostException expired =
                        new RiskAnalysisJobLeaseLostException(job.jobId());
                leaseLoss.compareAndSet(null, expired);
                throw leaseLoss.get();
            }
        }

        @Override
        public void close() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
        }
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "risk-analysis-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }
}
