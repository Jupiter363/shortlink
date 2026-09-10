package com.jupiter.shortlink.admin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Never pass the Throwable itself: Feign messages include URLs, headers and response bodies. */
public final class AdminDependencyDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(AdminDependencyDiagnostics.class);
    private static final Gate FEIGN = new Gate(), IDENTITY = new Gate();
    private AdminDependencyDiagnostics() {}

    public static void feignFailure(Throwable error, int upstreamStatus, int mappedStatus) {
        if (mappedStatus != 503) return;
        if (!FEIGN.acquire(System.nanoTime())) return;
        LOG.warn("admin_dependency_failure stage=feign exception={} cause={} upstream_status={} mapped_status={} suppressed={}",
                error.getClass().getSimpleName(), rootType(error), upstreamStatus, mappedStatus,
                FEIGN.suppressed.getAndSet(0));
    }

    public static void identityUnavailable(Throwable error) {
        if (!IDENTITY.acquire(System.nanoTime())) return;
        LOG.warn("admin_dependency_failure stage=identity exception={} cause={} mapped_status=503 suppressed={}",
                error.getClass().getSimpleName(), rootType(error), IDENTITY.suppressed.getAndSet(0));
    }

    static final class Gate {
        final AtomicLong last = new AtomicLong(Long.MIN_VALUE);
        final AtomicLong suppressed = new AtomicLong();
        boolean acquire(long now) {
            for (;;) {
                long previous = last.get();
                if (previous != Long.MIN_VALUE && now - previous < TimeUnit.SECONDS.toNanos(10)) {
                    suppressed.incrementAndGet();
                    return false;
                }
                if (last.compareAndSet(previous, now)) return true;
            }
        }
    }

    private static String rootType(Throwable error) {
        Throwable current = error;
        // Defensive bound also handles a cyclic cause graph, without retaining arbitrary data.
        for (int i = 0; i < 8 && current.getCause() != null && current.getCause() != current; i++) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }
}
