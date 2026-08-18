package com.jupiter.shortlink.agent.harness.checkpoint;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes runs sharing a native Graph thread key.
 *
 * <p>Spring AI Alibaba's saver persists a thread's checkpoints in order. A
 * bounded stripe set prevents two requests for the same session from loading
 * and writing the same checkpoint chain concurrently without retaining one
 * lock object for every session ever seen.</p>
 */
public final class GraphSessionExecutionCoordinator {

    private static final int DEFAULT_STRIPE_COUNT = 64;
    private static final GraphSessionExecutionCoordinator GLOBAL =
            new GraphSessionExecutionCoordinator(DEFAULT_STRIPE_COUNT);

    private final ReentrantLock[] stripes;

    public GraphSessionExecutionCoordinator() {
        this(DEFAULT_STRIPE_COUNT);
    }

    public GraphSessionExecutionCoordinator(int stripeCount) {
        if (stripeCount < 1) {
            throw new IllegalArgumentException("stripeCount must be positive");
        }
        this.stripes = new ReentrantLock[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            this.stripes[i] = new ReentrantLock();
        }
    }

    public static GraphSessionExecutionCoordinator global() {
        return GLOBAL;
    }

    public <T> T execute(String threadKey, Callable<T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        ReentrantLock lock = stripes[stripeIndex(threadKey)];
        lock.lock();
        try {
            return operation.call();
        } finally {
            lock.unlock();
        }
    }

    private int stripeIndex(String threadKey) {
        return Math.floorMod(Objects.requireNonNull(threadKey, "threadKey").hashCode(), stripes.length);
    }
}
