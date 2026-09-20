package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity;

/**
 * Actual callback lifetime within one admitted advance. This is not a resource pool or executor.
 * Enter before actual work. A callback that already owns input/context while queued must enter
 * before submission and retain its lease until the accepted Runnable's actual finally; only a
 * proven executor rejection permits immediate cleanup. Such queues must not silently discard
 * accepted work. Future cancellation/timeout must never close another worker's lease.
 */
public final class ProcessExecutionScope {
    private final Object monitor = new Object();
    private int active;
    private boolean closed;

    /** A sealed scope cannot start a late queued model, Graph node or tool callback. */
    public Lease enter() {
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("PROCESS_EXECUTION_SCOPE_CLOSED");
            active = Math.addExact(active, 1);
            return new Lease();
        }
    }

    /**
     * Seal admission, then wait for actual finally signals. The caller must first close its own
     * lease. An interrupt requests cancellation, but is not proof that another callback exited;
     * preserve that signal after all already-entered callbacks have actually returned.
     */
    public void closeAndAwaitActualExit() {
        boolean interrupted = Thread.interrupted();
        try {
            synchronized (monitor) {
                closed = true;
                while (active != 0) {
                    try { monitor.wait(); }
                    catch (InterruptedException cancelled) { interrupted = true; }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public int activeCount() { synchronized (monitor) { return active; } }
    public boolean isClosed() { synchronized (monitor) { return closed; } }

    public final class Lease implements AutoCloseable {
        private boolean exited;
        private Lease() {}

        /** Idempotent to permit nested cleanup, but only the actual worker's finally may call it. */
        @Override public void close() {
            synchronized (monitor) {
                if (exited) return;
                exited = true;
                active--;
                if (active == 0) monitor.notifyAll();
            }
        }
    }
}
