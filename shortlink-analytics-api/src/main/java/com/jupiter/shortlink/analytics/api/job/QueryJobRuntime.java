package com.jupiter.shortlink.analytics.api.job;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.UUID;
import java.util.concurrent.*;

/** A rejected local submission owns no lease; durable polling resumes after restart. */
@Configuration
@EnableScheduling
public class QueryJobRuntime {
    private final QueryJobService jobs;
    private final String owner = UUID.randomUUID().toString();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "analytics-query-worker");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    public QueryJobRuntime(QueryJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        for (int i = 0; i < 2; i++)
            try {
                workers.execute(
                        () -> {
                            try {
                                var lease =
                                        jobs.claim(owner + ":" + Thread.currentThread().getId());
                                if (lease != null) jobs.execute(lease);
                            } catch (RuntimeException e) {
                                org.slf4j.LoggerFactory.getLogger(getClass())
                                        .warn(
                                                "Query job attempt did not complete: {}",
                                                e.getClass().getSimpleName());
                            }
                        });
            } catch (RejectedExecutionException full) {
                break;
            }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        try {
            jobs.cleanup();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Query job cleanup unavailable: {}", e.getClass().getSimpleName());
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
