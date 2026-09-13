package com.jupiter.shortlink.agent.riskanalysis.job;

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

    private static final ThreadLocal<Lease> CURRENT_EXECUTION = new ThreadLocal<>();

    private final JdbcRiskAnalysisJobRepository repository;

    private final ScheduledExecutorService scheduler;

    private final Duration executionTimeout;

    @Autowired
    public RiskAnalysisJobLeaseManager(JdbcRiskAnalysisJobRepository repository,
            com.jupiter.shortlink.agent.infrastructure.config.AgentProperties properties) {
        this(repository, newScheduler(), Duration.ofMillis(properties.getRisk().getAnalysis().getExecutionTimeoutMillis()));
    }

    public RiskAnalysisJobLeaseManager(JdbcRiskAnalysisJobRepository repository) {
        this(repository, newScheduler());
    }

    RiskAnalysisJobLeaseManager(
            JdbcRiskAnalysisJobRepository repository,
            ScheduledExecutorService scheduler
    ) {
        this(repository, scheduler, Duration.ofSeconds(120));
    }

    RiskAnalysisJobLeaseManager(JdbcRiskAnalysisJobRepository repository,
            ScheduledExecutorService scheduler, Duration executionTimeout) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.executionTimeout = executionTimeout;
    }

    /** Captured by the Graph before it crosses the reactive execution boundary. */
    public static Lease currentExecution() {
        return CURRENT_EXECUTION.get();
    }

    public static ExecutionScope bindExecution(Lease lease) {
        Lease previous = CURRENT_EXECUTION.get();
        CURRENT_EXECUTION.set(lease);
        return () -> {
            if (previous == null) CURRENT_EXECUTION.remove();
            else CURRENT_EXECUTION.set(previous);
        };
    }

    public interface ExecutionScope extends AutoCloseable {
        @Override void close();
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

        default void assertExecutionActive() { assertOwned(); }

        default Duration remainingExecutionTime() { return Duration.ofSeconds(120); }

        @Override
        void close();
    }

    private final class ManagedLease implements Lease {

        private final RiskAnalysisJob job;

        private final Duration leaseDuration;

        private final Clock clock;

        private final AtomicReference<LocalDateTime> leaseUntil;

        private final AtomicReference<RiskAnalysisJobLeaseLostException> leaseLoss = new AtomicReference<>();

        private final long executionDeadlineNanos;

        private volatile boolean closed;

        private ScheduledFuture<?> heartbeat;

        private ManagedLease(RiskAnalysisJob job, Duration leaseDuration, Clock clock) {
            this.job = job;
            this.leaseDuration = leaseDuration;
            this.clock = clock;
            this.leaseUntil = new AtomicReference<>(job.leaseUntil());
            Duration budget = executionTimeout.compareTo(leaseDuration) < 0 ? executionTimeout : leaseDuration;
            this.executionDeadlineNanos = System.nanoTime() + budget.toNanos();
        }

        private void attach(ScheduledFuture<?> heartbeat) {
            this.heartbeat = heartbeat;
        }

        private void renew() {
            if (closed || System.nanoTime() >= executionDeadlineNanos) {
                if (heartbeat != null) heartbeat.cancel(false);
                return;
            }
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
                if (closed) return;
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
        public void assertExecutionActive() {
            assertOwned();
            if (closed || System.nanoTime() >= executionDeadlineNanos)
                throw new IllegalStateException("Risk analysis execution deadline exceeded");
        }

        @Override
        public Duration remainingExecutionTime() {
            return Duration.ofNanos(Math.max(0, executionDeadlineNanos - System.nanoTime()));
        }

        @Override
        public void close() {
            closed = true;
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
