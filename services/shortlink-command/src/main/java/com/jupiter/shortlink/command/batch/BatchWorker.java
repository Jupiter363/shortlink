package com.jupiter.shortlink.command.batch;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * No task queue and at most one active job per tenant per instance; DB leases arbitrate replicas.
 */
@Component
public class BatchWorker {
    private static final Logger log = LoggerFactory.getLogger(BatchWorker.class);
    private final BatchJobService jobs;
    private final BatchLimits limits;
    private final ThreadPoolExecutor executor;
    private final Set<Long> activeTenants = ConcurrentHashMap.newKeySet();
    private final String owner = UUID.randomUUID().toString();

    public BatchWorker(BatchJobService jobs, BatchLimits limits) {
        this.jobs = jobs;
        this.limits = limits;
        executor =
                new ThreadPoolExecutor(
                        limits.workers(),
                        limits.workers(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "batch-worker");
                            t.setDaemon(true);
                            return t;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${shortlink.batch.poll-millis:1000}")
    public void poll() {
        if (executor.isShutdown() || activeTenants.size() >= limits.workers()) return;
        for (BatchJobService.Candidate candidate :
                jobs.candidates(Math.min(128, limits.workers() * 4))) {
            if (activeTenants.size() >= limits.workers()) break;
            offer(candidate);
        }
    }

    /** Kafka is a hint only; saturated workers leave the durable job for the normal DB poll. */
    public boolean offer(BatchJobService.Candidate candidate) {
        if (executor.isShutdown()
                || activeTenants.size() >= limits.workers()
                || !activeTenants.add(candidate.tenantId())) return false;
        try {
            executor.execute(() -> run(candidate));
            return true;
        } catch (RejectedExecutionException e) {
            activeTenants.remove(candidate.tenantId());
            return false;
        }
    }

    private void run(BatchJobService.Candidate candidate) {
        BatchJobService.Lease lease = null;
        try {
            lease = jobs.claim(candidate.jobId(), owner);
            if (lease == null) {
                activeTenants.remove(candidate.tenantId());
                return;
            }
            BatchJobService.Lease claimed = lease;
            jobs.runAsync(claimed)
                    .whenComplete(
                            (ignored, failure) -> {
                                if (failure == null) {
                                    activeTenants.remove(candidate.tenantId());
                                    return;
                                }
                                // Completion can originate on the deadline timer. Never run JDBC
                                // there.
                                try {
                                    executor.execute(
                                            () -> {
                                                try {
                                                    recordFailure(candidate, claimed, failure);
                                                } finally {
                                                    activeTenants.remove(candidate.tenantId());
                                                }
                                            });
                                } catch (RejectedExecutionException unavailable) {
                                    activeTenants.remove(candidate.tenantId());
                                    log.warn(
                                            "Batch failure deferred to lease recovery for job {}",
                                            candidate.jobId());
                                }
                            });
        } catch (Exception failure) {
            try {
                recordFailure(candidate, lease, failure);
            } finally {
                activeTenants.remove(candidate.tenantId());
            }
        }
    }

    private void recordFailure(
            BatchJobService.Candidate candidate, BatchJobService.Lease lease, Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        if (failure instanceof BatchJobService.StaleLeaseException) return;
        log.warn(
                "Batch worker failed for job {} ({})",
                candidate.jobId(),
                failure.getClass().getSimpleName());
        if (lease != null) {
            try {
                jobs.failed(
                        lease,
                        failure instanceof Exception e ? e : new IllegalStateException(failure));
            } catch (Exception retryFailure) {
                log.warn("Batch failure persistence deferred for job {}", candidate.jobId());
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
