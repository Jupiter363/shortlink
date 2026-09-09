/*
 * Controlled adaptation of Meituan-Dianping Leaf Segment, Apache License 2.0.
 * Upstream commit and unmodified references: module UPSTREAM.md.
 * Project changes: unified Range cursor, immutable segment generations, bounded resources and waits.
 */
package com.jupiter.shortlink.id;

import static com.jupiter.shortlink.id.IdGenerationException.Reason.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Leaf-style Current/Next lifecycle. No JDBC call or Future wait runs under allocationLock.
 * Construct one instance per process/global namespace, with a dedicated bounded SegmentStore.
 */
public final class SegmentIdGenerator implements IdGenerator {
    private final SegmentStore store;
    private final GeneratorOptions options;
    private final ThreadPoolExecutor refillExecutor;
    private final LongSupplier ticker;
    private final ReentrantLock allocationLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final StepController steps;
    private final LongAdder issued = new LongAdder();
    private final LongAdder rangeReservations = new LongAdder();
    private final LongAdder discarded = new LongAdder();
    private final LongAdder databaseAttempts = new LongAdder();
    private final LongAdder refillFailures = new LongAdder();
    private final LongAdder executorRejections = new LongAdder();
    private Segment current;
    private Segment next;
    private volatile LoadAttempt inFlight;
    private boolean spaceExhausted;
    private long publishedHighWater = 1;

    public SegmentIdGenerator(SegmentStore store) {
        this(store, GeneratorOptions.defaults());
    }

    public SegmentIdGenerator(SegmentStore store, GeneratorOptions options) {
        this(store, options, newExecutor(), System::nanoTime);
    }

    // Package-private injection is restricted to a real bounded ThreadPoolExecutor in tests.
    SegmentIdGenerator(
            SegmentStore store,
            GeneratorOptions options,
            ThreadPoolExecutor executor,
            LongSupplier ticker) {
        this.store = Objects.requireNonNull(store);
        this.options = Objects.requireNonNull(options);
        this.refillExecutor = Objects.requireNonNull(executor);
        this.ticker = Objects.requireNonNull(ticker);
        this.steps = new StepController(options.stepPolicy());
    }

    private static ThreadPoolExecutor newExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "shortlink-id-refill");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public long nextId() {
        return take(1).get(0).startInclusive();
    }

    @Override
    public List<IdRange> reserveRanges(int count) {
        return take(count);
    }

    private List<IdRange> take(int count) {
        if (count < 1 || count > options.maxReservationSize()) {
            throw new IllegalArgumentException("count outside configured reservation limit");
        }
        long deadline = System.nanoTime() + options.requestTimeout().toNanos();
        List<IdRange> ranges = new ArrayList<>(Math.min(4, options.maxRangesPerReservation()));
        int remaining = count;
        try {
            while (remaining > 0) {
                CompletableFuture<Void> waitFor = null;
                acquire(deadline);
                try {
                    ensureOpen();
                    if (current != null && current.remaining() == 0) {
                        steps.consumed(
                                ticker.getAsLong() - current.activatedAt,
                                ticker.getAsLong(),
                                current.range.size());
                        current = null;
                    }
                    if (current == null && next != null) {
                        current = next;
                        next = null;
                        current.activatedAt = ticker.getAsLong();
                    }
                    if (current == null) {
                        if (spaceExhausted)
                            throw failure(SPACE_EXHAUSTED, "Global ID space exhausted");
                        waitFor = ensureRefillLocked().completion;
                    } else {
                        if (ranges.size() == options.maxRangesPerReservation()) {
                            throw failure(
                                    UNAVAILABLE,
                                    "Reservation exceeded bounded range count; consumed IDs"
                                            + " discarded");
                        }
                        int amount = (int) Math.min(remaining, current.remaining());
                        long start = current.cursor;
                        current.cursor += amount;
                        ranges.add(new IdRange(start, current.cursor));
                        rangeReservations.increment();
                        remaining -= amount;
                        if (next == null
                                && !spaceExhausted
                                && current.range.endExclusive() < IdRange.ID_LIMIT
                                && current.remaining() * 100
                                        <= current.range.size()
                                                * options.prefetchRemainingPercent()) {
                            ensureRefillLocked();
                        }
                    }
                } finally {
                    allocationLock.unlock();
                }
                if (waitFor != null) await(waitFor, deadline);
            }
            issued.add(count);
            return List.copyOf(ranges);
        } catch (RuntimeException failure) {
            discarded.add(count - remaining);
            throw failure;
        }
    }

    private LoadAttempt ensureRefillLocked() {
        if (inFlight != null && !inFlight.completion.isDone()) return inFlight;
        LoadAttempt attempt = new LoadAttempt(steps.currentStep());
        inFlight = attempt;
        try {
            refillExecutor.execute(
                    () -> {
                        try {
                            refill(attempt);
                        } catch (Error fatal) {
                            // Even an executor task dying abnormally must release the single-flight
                            // gate.
                            attempt.completion.completeExceptionally(fatal);
                            throw fatal;
                        }
                    });
        } catch (RejectedExecutionException rejected) {
            executorRejections.increment();
            inFlight = null;
            attempt.completion.completeExceptionally(
                    new IdGenerationException(
                            UNAVAILABLE, "Refill executor rejected task", rejected));
        }
        return attempt;
    }

    private void refill(LoadAttempt attempt) {
        IdRange range = null;
        RuntimeException lastFailure = null;
        for (int retry = 0; retry < options.refillAttempts() && !closed.get(); retry++) {
            try {
                databaseAttempts.increment();
                IdRange candidate = store.reserve(attempt.requestedSize);
                if (candidate == null
                        || candidate.size() > attempt.requestedSize
                        || (candidate.size() < attempt.requestedSize
                                && candidate.endExclusive() != IdRange.ID_LIMIT)) {
                    throw failure(CONFIGURATION, "Store returned invalid grant size");
                }
                range = candidate;
                break;
            } catch (RuntimeException failed) {
                lastFailure = failed;
                refillFailures.increment();
                if (failed instanceof IdGenerationException problem
                        && (problem.reason() == SPACE_EXHAUSTED
                                || problem.reason() == CONFIGURATION)) break;
                if (retry + 1 < options.refillAttempts()) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(options.retryBackoff().toNanos());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        lastFailure =
                                new IdGenerationException(
                                        INTERRUPTED, "Refill interrupted", interrupted);
                        break;
                    }
                }
            }
        }
        // Publication is short and bounded; a committed grant that cannot be published is burned.
        boolean locked = false;
        try {
            locked = allocationLock.tryLock(options.lockTimeout().toNanos(), TimeUnit.NANOSECONDS);
            if (!locked) throw failure(TIMEOUT, "Refill publication lock timed out");
            if (closed.get() || inFlight != attempt)
                throw failure(CLOSED, "Refill no longer active");
            if (range == null) {
                if (lastFailure instanceof IdGenerationException problem
                        && problem.reason() == SPACE_EXHAUSTED) {
                    spaceExhausted = true;
                }
                throw lastFailure != null
                        ? lastFailure
                        : failure(CLOSED, "Generator closed during refill");
            }
            if (next != null) throw failure(CONFIGURATION, "Next segment already populated");
            if (range.startInclusive() < publishedHighWater) {
                throw failure(
                        CONFIGURATION,
                        "Store returned a range below an already published high-water mark");
            }
            publishedHighWater = range.endExclusive();
            if (current == null) {
                current = new Segment(range, ticker.getAsLong());
            } else {
                next = new Segment(range, 0);
            }
            range = null;
            inFlight = null;
            attempt.completion.complete(null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            attempt.completion.completeExceptionally(
                    new IdGenerationException(
                            INTERRUPTED, "Refill publication interrupted", interrupted));
        } catch (RuntimeException failed) {
            attempt.completion.completeExceptionally(failed);
        } finally {
            if (locked) {
                if (inFlight == attempt) inFlight = null;
                allocationLock.unlock();
            }
            if (range != null) discarded.add(range.size());
        }
    }

    private void acquire(long deadline) {
        ensureOpen();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw failure(TIMEOUT, "Reservation deadline exceeded");
        try {
            if (!allocationLock.tryLock(
                    Math.min(remaining, options.lockTimeout().toNanos()), TimeUnit.NANOSECONDS)) {
                throw failure(TIMEOUT, "Allocation lock timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IdGenerationException(INTERRUPTED, "Reservation interrupted", interrupted);
        }
    }

    private void await(CompletableFuture<Void> completion, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw failure(TIMEOUT, "Reservation deadline exceeded");
        try {
            completion.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IdGenerationException(INTERRUPTED, "Reservation interrupted", interrupted);
        } catch (TimeoutException timedOut) {
            throw new IdGenerationException(
                    TIMEOUT, "Refill did not finish before request deadline", timedOut);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IdGenerationException known) throw known;
            throw new IdGenerationException(UNAVAILABLE, "Refill failed", failed.getCause());
        }
    }

    public void configureStep(StepPolicy replacement) {
        Objects.requireNonNull(replacement);
        acquire(System.nanoTime() + options.lockTimeout().toNanos());
        try {
            ensureOpen();
            steps.configure(replacement);
        } finally {
            allocationLock.unlock();
        }
    }

    public GeneratorSnapshot snapshot() {
        try {
            if (!allocationLock.tryLock(options.lockTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                throw failure(TIMEOUT, "Snapshot lock timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IdGenerationException(INTERRUPTED, "Snapshot interrupted", interrupted);
        }
        try {
            return new GeneratorSnapshot(
                    issued.sum(),
                    rangeReservations.sum(),
                    discarded.sum(),
                    databaseAttempts.sum(),
                    refillFailures.sum(),
                    executorRejections.sum(),
                    steps.currentStep(),
                    steps.changes(),
                    current == null ? 0 : current.remaining(),
                    next != null,
                    inFlight != null && !inFlight.completion.isDone(),
                    closed.get());
        } finally {
            allocationLock.unlock();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + options.closeTimeout().toNanos();
        LoadAttempt attempt = inFlight;
        if (attempt != null)
            attempt.completion.completeExceptionally(failure(CLOSED, "Generator closed"));
        refillExecutor.shutdownNow();
        try {
            if (allocationLock.tryLock(
                    Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                try {
                    if (current != null) discarded.add(current.remaining());
                    if (next != null) discarded.add(next.remaining());
                    current = null;
                    next = null;
                    inFlight = null;
                } finally {
                    allocationLock.unlock();
                }
            }
            refillExecutor.awaitTermination(
                    Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw failure(CLOSED, "Generator closed");
    }

    private static IdGenerationException failure(
            IdGenerationException.Reason reason, String message) {
        return new IdGenerationException(reason, message);
    }

    private static final class Segment {
        final IdRange range;
        long cursor;
        long activatedAt;

        Segment(IdRange range, long activatedAt) {
            this.range = range;
            this.cursor = range.startInclusive();
            this.activatedAt = activatedAt;
        }

        long remaining() {
            return range.endExclusive() - cursor;
        }
    }

    private static final class LoadAttempt {
        final int requestedSize;
        final CompletableFuture<Void> completion = new CompletableFuture<>();

        LoadAttempt(int requestedSize) {
            this.requestedSize = requestedSize;
        }
    }
}
