package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-local, all-or-nothing admission in front of an existing executor.
 *
 * <p>One shared instance must cover all callers sharing the configured capacity. FIFO waiters
 * contain only bounded identifiers and permit counts: there is deliberately no per-submission
 * Callable, prompt, page, or result. The shared worker resolves identifiers <em>after</em> admission
 * and must finish loading, processing, and persisting its result before returning. It must not
 * launch detached work and return while that work still uses the admitted resources.
 *
 * <p>Demand describes the maximum simultaneous resources for this unit of work. It is reserved
 * atomically, so a worker never holds one permit while waiting for another. A caller needing a
 * different demand must finish/yield and resubmit a lightweight reference, not recursively submit
 * and wait from inside the worker. FIFO fairness applies to accepted references across Runs;
 * authentication, Run coordination, durable BLOCKED state, and retry timing remain caller duties.
 *
 * <p>The delegate must execute an accepted Runnable or throw on rejection; silently dropping tasks
 * is unsupported, and rejection must mean the Runnable was not retained. Standard CallerRunsPolicy
 * is supported without bypassing admission, including
 * its shutdown rejection case. Close this wrapper before shutting down its externally owned
 * executor. A ThreadPoolExecutor's cancelled queued task is removed before its permits are
 * returned. Other executors must still consume accepted cancelled Runnables: cancellation alone
 * does not release their queue reservations. Use graceful shutdown, or consume the cancelled
 * Runnables returned by shutdownNow; silently discarding them cannot be accounted for by a
 * generic Executor wrapper. Changing the rejection policy after construction is unsupported.
 */
public final class ProcessCapacityExecutor implements AutoCloseable {
    private static final int MAX_REFERENCE_LENGTH = 256;

    public record Limits(int activeAdvances, int models, int largePayloads, int maxQueued) {
        public Limits {
            if (activeAdvances <= 0 || models <= 0 || largePayloads <= 0 || maxQueued < 0) {
                throw new IllegalArgumentException("Capacities must be positive; maxQueued must be nonnegative");
            }
        }
    }

    public record Demand(int activeAdvances, int models, int largePayloads) {
        public Demand {
            if (activeAdvances < 0 || models < 0 || largePayloads < 0
                    || (long) activeAdvances + models + largePayloads == 0) {
                throw new IllegalArgumentException("Demand must be nonnegative and request at least one permit");
            }
        }
    }

    public record WorkRef(String runId, String workId) {
        public WorkRef {
            requireReference(runId, "runId");
            requireReference(workId, "workId");
        }

        private static void requireReference(String value, String field) {
            if (value == null || value.isBlank() || value.length() > MAX_REFERENCE_LENGTH) {
                throw new IllegalArgumentException(field + " must be a nonblank reference of at most "
                        + MAX_REFERENCE_LENGTH + " characters");
            }
        }
    }

    @FunctionalInterface
    public interface RefWorker {
        /** Resolve and load data here, not before calling submit. Persist output before returning. */
        void execute(WorkRef reference) throws Exception;
    }

    public enum RejectionReason { QUEUE_FULL, CLOSED, EXECUTOR_REJECTED }

    public static final class CapacityRejectedException extends RejectedExecutionException {
        private final RejectionReason reason;

        private CapacityRejectedException(RejectionReason reason, Throwable cause) {
            super("Local capacity admission rejected: " + reason, cause);
            this.reason = reason;
        }

        public RejectionReason reason() {
            return reason;
        }
    }

    /** Includes admitted work waiting in the delegate, and cancelled workers that have not exited. */
    public record Snapshot(int activeAdvances, int models, int largePayloads,
                           int queued, int dispatched, int running, boolean closed) {}

    private enum TaskState { QUEUED, READY, SUBMITTING, DISPATCHED, RUNNING, FINISHED }

    private final Object monitor = new Object();
    private final Limits limits;
    private final Executor executor;
    private final RefWorker worker;
    private final ArrayDeque<WorkTask> waiting = new ArrayDeque<>();
    private final ArrayDeque<WorkTask> ready = new ArrayDeque<>();
    private final Set<WorkTask> outstanding = new HashSet<>();
    private final AtomicInteger drainRequests = new AtomicInteger();
    private int activeAdvances;
    private int models;
    private int largePayloads;
    private boolean closed;

    public ProcessCapacityExecutor(Limits limits, Executor executor, RefWorker worker) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.worker = Objects.requireNonNull(worker, "worker");
        if (executor instanceof ThreadPoolExecutor pool
                && (pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.DiscardPolicy
                || pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.DiscardOldestPolicy)) {
            throw new IllegalArgumentException("Executor must not silently discard admitted work");
        }
    }

    /**
     * Queue saturation/closure rejects synchronously, without loading data. Delegate rejection is
     * reported by the returned Future. Future cancellation or get(timeout) never proves worker exit.
     */
    public Future<Void> submit(WorkRef reference, Demand demand) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(demand, "demand");
        if (demand.activeAdvances() > limits.activeAdvances() || demand.models() > limits.models()
                || demand.largePayloads() > limits.largePayloads()) {
            throw new IllegalArgumentException("Demand exceeds configured capacity");
        }
        WorkTask task = new WorkTask(reference, demand);
        synchronized (monitor) {
            if (closed) {
                throw new CapacityRejectedException(RejectionReason.CLOSED, null);
            }
            if (waiting.isEmpty() && fits(demand)) {
                reserve(task);
                ready.addLast(task);
            } else {
                if (waiting.size() >= limits.maxQueued()) {
                    throw new CapacityRejectedException(RejectionReason.QUEUE_FULL, null);
                }
                waiting.addLast(task);
            }
            outstanding.add(task);
        }
        drain();
        return task;
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            int dispatched = 0;
            int running = 0;
            for (WorkTask task : outstanding) {
                if (task.state == TaskState.READY || task.state == TaskState.SUBMITTING
                        || task.state == TaskState.DISPATCHED) dispatched++;
                if (task.state == TaskState.RUNNING) running++;
            }
            return new Snapshot(activeAdvances, models, largePayloads,
                    waiting.size(), dispatched, running, closed);
        }
    }

    /** Cancels work; permits remain occupied until removal or actual Runnable consumption is proven. */
    @Override
    public void close() {
        ArrayList<WorkTask> tasks;
        synchronized (monitor) {
            closed = true;
            tasks = new ArrayList<>(outstanding);
        }
        for (WorkTask task : tasks) task.cancel(true);
    }

    private boolean fits(Demand demand) {
        return demand.activeAdvances() <= limits.activeAdvances() - activeAdvances
                && demand.models() <= limits.models() - models
                && demand.largePayloads() <= limits.largePayloads() - largePayloads;
    }

    private void reserve(WorkTask task) {
        activeAdvances += task.demand.activeAdvances();
        models += task.demand.models();
        largePayloads += task.demand.largePayloads();
        task.state = TaskState.READY;
    }

    private void finish(WorkTask task) {
        if (task.state == TaskState.FINISHED) return;
        if (task.state == TaskState.QUEUED) {
            waiting.remove(task);
        } else {
            activeAdvances -= task.demand.activeAdvances();
            models -= task.demand.models();
            largePayloads -= task.demand.largePayloads();
        }
        ready.remove(task);
        outstanding.remove(task);
        task.state = TaskState.FINISHED;
    }

    private WorkTask nextReady() {
        synchronized (monitor) {
            if (closed) return null;
            if (!ready.isEmpty()) return ready.removeFirst();
            WorkTask head = waiting.peekFirst();
            if (head == null || !fits(head.demand)) return null;
            waiting.removeFirst();
            reserve(head);
            return head;
        }
    }

    // Coalesced, event-driven draining avoids recursive CallerRuns completion and polling threads.
    private void drain() {
        if (drainRequests.getAndIncrement() != 0) return;
        int observed = 1;
        do {
            WorkTask task;
            while ((task = nextReady()) != null) dispatch(task);
            observed = drainRequests.addAndGet(-observed);
        } while (observed != 0);
    }

    private void dispatch(WorkTask task) {
        synchronized (monitor) {
            if (task.state != TaskState.READY) return;
            // Cancellation cannot free this reservation while execute() may still enqueue it.
            task.state = TaskState.SUBMITTING;
        }
        try {
            if (executor instanceof ThreadPoolExecutor pool && pool.isShutdown()) {
                throw new RejectedExecutionException("Delegate executor is shut down");
            }
            executor.execute(task);
            synchronized (monitor) {
                if (task.state == TaskState.SUBMITTING) task.state = TaskState.DISPATCHED;
            }
            if (task.isCancelled()) cancelBeforeStart(task);
            // CallerRunsPolicy silently discards after shutdown, including a race with execute().
            if (executor instanceof ThreadPoolExecutor pool && pool.isShutdown()) {
                // If it was accepted before shutdown, remove its actual queue entry first.
                pool.remove(task);
                rejectBeforeStart(task, new RejectedExecutionException("Delegate executor is shut down"));
            }
        } catch (RuntimeException | Error ex) {
            rejectBeforeStart(task, ex);
        }
    }

    private void rejectBeforeStart(WorkTask task, Throwable cause) {
        synchronized (monitor) {
            if (task.state != TaskState.SUBMITTING && task.state != TaskState.DISPATCHED) return;
            finish(task);
        }
        task.fail(new CapacityRejectedException(RejectionReason.EXECUTOR_REJECTED, cause));
        drain();
    }

    private void cancelBeforeStart(WorkTask task) {
        boolean removeFromDelegate;
        synchronized (monitor) {
            if (task.state == TaskState.QUEUED || task.state == TaskState.READY) {
                finish(task); // Not handed to the delegate, so no stale Runnable can remain there.
                removeFromDelegate = false;
            } else if (task.state == TaskState.DISPATCHED && executor instanceof ThreadPoolExecutor) {
                removeFromDelegate = true;
            } else {
                // SUBMITTING is rechecked after execute returns. Generic delegates and running
                // tasks retain their reservation until run() actually consumes/exits the task.
                return;
            }
        }
        if (removeFromDelegate) {
            if (!((ThreadPoolExecutor) executor).remove(task)) return;
            synchronized (monitor) {
                if (task.state == TaskState.DISPATCHED) finish(task);
            }
        }
        drain();
    }

    private final class WorkTask extends FutureTask<Void> {
        private final WorkRef reference;
        private final Demand demand;
        private TaskState state = TaskState.QUEUED;

        private WorkTask(WorkRef reference, Demand demand) {
            super(() -> { worker.execute(reference); return null; });
            this.reference = reference;
            this.demand = demand;
        }

        @Override
        public void run() {
            boolean stop;
            synchronized (monitor) {
                if (state != TaskState.SUBMITTING && state != TaskState.DISPATCHED) return;
                stop = closed || isCancelled();
                // A cancelled Runnable has now really been consumed by the delegate. Its finally
                // must run even though FutureTask.run() would skip a cancelled Callable.
                state = TaskState.RUNNING;
            }
            try {
                if (stop) cancel(false);
                else super.run();
            } finally {
                synchronized (monitor) {
                    finish(this);
                }
                drain();
            }
        }

        @Override
        protected void done() {
            if (!isCancelled()) return;
            cancelBeforeStart(this);
        }

        private void fail(RuntimeException cause) {
            setException(cause);
        }
    }
}
