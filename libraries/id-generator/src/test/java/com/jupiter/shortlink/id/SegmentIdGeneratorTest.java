package com.jupiter.shortlink.id;

import static com.jupiter.shortlink.id.IdGenerationException.Reason.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(15)
class SegmentIdGeneratorTest {
    static GeneratorOptions options(int step) {
        return new GeneratorOptions(
                new StepPolicy(
                        false,
                        step,
                        1,
                        1_000_000,
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30),
                        2,
                        Duration.ofMinutes(5)),
                20,
                10_000,
                128,
                Duration.ofSeconds(2),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                1,
                Duration.ofMillis(5));
    }

    static final class MemoryStore implements SegmentStore {
        final AtomicLong highWater;
        final List<IdRange> grants = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> requests = Collections.synchronizedList(new ArrayList<>());

        MemoryStore(long initial) {
            highWater = new AtomicLong(initial);
        }

        @Override
        public synchronized IdRange reserve(int requested) {
            long start = highWater.get();
            if (start == IdRange.ID_LIMIT) throw new IdGenerationException(SPACE_EXHAUSTED, "full");
            long end = start + Math.min(requested, IdRange.ID_LIMIT - start);
            highWater.set(end);
            IdRange range = new IdRange(start, end);
            grants.add(range);
            requests.add(requested);
            return range;
        }
    }

    @Test
    void crossesSegmentsInRangesAndDoesNotMutateReturnedGenerations() {
        MemoryStore store = new MemoryStore(1);
        try (SegmentIdGenerator generator = new SegmentIdGenerator(store, options(300))) {
            List<IdRange> first = generator.reserveRanges(500);
            assertEquals(List.of(new IdRange(1, 301), new IdRange(301, 501)), first);
            assertThrows(UnsupportedOperationException.class, () -> first.add(new IdRange(1, 2)));
            assertEquals(501, generator.nextId());
            for (int i = 0; i < 20; i++) generator.reserveRanges(400);
            assertEquals(new IdRange(1, 301), first.get(0));
            GeneratorSnapshot snapshot = generator.snapshot();
            assertEquals(8501, snapshot.issuedIds());
            assertTrue(snapshot.reservedRanges() < snapshot.issuedIds() / 100);
        }
    }

    @Test
    void mixedSingleAndRangeAcrossInstancesHaveNoOverlap() throws Exception {
        MemoryStore store = new MemoryStore(1);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        List<IdRange> returned = Collections.synchronizedList(new ArrayList<>());
        try (SegmentIdGenerator first = new SegmentIdGenerator(store, options(113));
                SegmentIdGenerator second = new SegmentIdGenerator(store, options(197))) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                SegmentIdGenerator generator = worker % 2 == 0 ? first : second;
                tasks.add(
                        callers.submit(
                                () -> {
                                    for (int n = 0; n < 80; n++) {
                                        if (n % 2 == 0) {
                                            long id = generator.nextId();
                                            returned.add(new IdRange(id, id + 1));
                                        } else returned.addAll(generator.reserveRanges(17));
                                    }
                                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            returned.sort(Comparator.comparingLong(IdRange::startInclusive));
            for (int i = 1; i < returned.size(); i++) {
                assertTrue(returned.get(i - 1).endExclusive() <= returned.get(i).startInclusive());
            }
            assertEquals(8L * 40 * 18, returned.stream().mapToLong(IdRange::size).sum());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void coldInitializationIsSingleFlight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        MemoryStore storage = new MemoryStore(1);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            awaitLatch(release);
                            return storage.reserve(count);
                        },
                        options(100))) {
            List<Future<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(callers.submit(generator::nextId));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertTrue(
                    generator
                            .snapshot()
                            .refillRunning()); // initialization does not own allocation lock
            release.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> task : tasks) ids.add(task.get(3, TimeUnit.SECONDS));
            assertEquals(8, ids.stream().distinct().count());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void slowPrefetchDoesNotHoldAllocationLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        MemoryStore store = new MemoryStore(1);
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                awaitLatch(release);
                            }
                            return store.reserve(count);
                        },
                        options(100))) {
            assertEquals(80, generator.reserveRanges(80).stream().mapToLong(IdRange::size).sum());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(81, generator.nextId());
            assertTrue(generator.snapshot().refillRunning());
            release.countDown();
            assertEquals(100, generator.reserveRanges(100).stream().mapToLong(IdRange::size).sum());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedPartialReservationBurnsItsConsumedRanges() {
        MemoryStore store = new MemoryStore(1);
        AtomicBoolean healthy = new AtomicBoolean(false);
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            if (store.highWater.get() > 1 && !healthy.get())
                                throw new IdGenerationException(DATABASE, "injected");
                            return store.reserve(count);
                        },
                        options(300))) {
            assertThrows(IdGenerationException.class, () -> generator.reserveRanges(500));
            assertEquals(300, generator.snapshot().discardedIds());
            assertEquals(0, generator.snapshot().issuedIds());
            healthy.set(true);
            assertEquals(301, generator.nextId());
        }
    }

    @Test
    void requestTimeoutLeavesOneRefillAndRecoversWhenItCompletes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        GeneratorOptions normal = options(100);
        GeneratorOptions shortWait =
                new GeneratorOptions(
                        normal.stepPolicy(),
                        20,
                        1000,
                        32,
                        Duration.ofMillis(40),
                        Duration.ofMillis(20),
                        Duration.ofMillis(100),
                        1,
                        Duration.ofMillis(1));
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            calls.incrementAndGet();
                            awaitLatch(release);
                            return new IdRange(1, 101);
                        },
                        shortWait)) {
            assertEquals(
                    TIMEOUT, assertThrows(IdGenerationException.class, generator::nextId).reason());
            assertEquals(
                    TIMEOUT, assertThrows(IdGenerationException.class, generator::nextId).reason());
            assertEquals(1, calls.get());
            release.countDown();
            assertEquals(1, generator.nextId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void threadInterruptionIsPreservedAndDoesNotResetTheRefill() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<IdGenerationException.Reason> reason = new AtomicReference<>();
        AtomicBoolean flag = new AtomicBoolean();
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            entered.countDown();
                            awaitLatch(release);
                            return new IdRange(1, 101);
                        },
                        options(100))) {
            Thread caller =
                    new Thread(
                            () -> {
                                try {
                                    generator.nextId();
                                } catch (IdGenerationException failed) {
                                    reason.set(failed.reason());
                                    flag.set(Thread.currentThread().isInterrupted());
                                }
                            });
            caller.start();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1000);
            assertFalse(caller.isAlive());
            assertEquals(INTERRUPTED, reason.get());
            assertTrue(flag.get());
            release.countDown();
            assertEquals(1, generator.nextId());
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectionClearsSingleFlightAndNextRequestCanRecover() {
        AtomicBoolean reject = new AtomicBoolean(true);
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(1),
                        new ThreadPoolExecutor.AbortPolicy()) {
                    @Override
                    public void execute(Runnable command) {
                        if (reject.getAndSet(false))
                            throw new RejectedExecutionException("injected");
                        super.execute(command);
                    }
                };
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        new MemoryStore(1), options(100), executor, System::nanoTime)) {
            assertEquals(
                    UNAVAILABLE,
                    assertThrows(IdGenerationException.class, generator::nextId).reason());
            assertEquals(1, generator.nextId());
            assertEquals(1, generator.snapshot().executorRejections());
        }
    }

    @Test
    void finalShortTailAndClosedGeneratorFailWithoutWrapping() {
        MemoryStore store = new MemoryStore(IdRange.ID_LIMIT - 3);
        SegmentIdGenerator generator = new SegmentIdGenerator(store, options(100));
        assertEquals(
                List.of(new IdRange(IdRange.ID_LIMIT - 3, IdRange.ID_LIMIT)),
                generator.reserveRanges(3));
        assertEquals(
                SPACE_EXHAUSTED,
                assertThrows(IdGenerationException.class, generator::nextId).reason());
        assertEquals(IdRange.ID_LIMIT, store.highWater.get());
        generator.close();
        generator.close();
        assertEquals(CLOSED, assertThrows(IdGenerationException.class, generator::nextId).reason());
        assertTrue(generator.snapshot().closed());
    }

    @Test
    void closeWakesPendingCallerAndNeverPublishesLateDatabaseResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService callers = Executors.newSingleThreadExecutor();
        SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            entered.countDown();
                            // Simulate a driver ignoring interrupt; the caller/close still have
                            // finite deadlines.
                            boolean done = false;
                            while (!done) {
                                try {
                                    done = release.await(1, TimeUnit.SECONDS);
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return new IdRange(1, 101);
                        },
                        options(100));
        try {
            Future<?> caller =
                    callers.submit(
                            () ->
                                    assertEquals(
                                            CLOSED,
                                            assertThrows(
                                                            IdGenerationException.class,
                                                            generator::nextId)
                                                    .reason()));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            long before = System.nanoTime();
            generator.close();
            assertTrue(System.nanoTime() - before < Duration.ofSeconds(2).toNanos());
            caller.get(1, TimeUnit.SECONDS);
            release.countDown();
            assertTrue(generator.snapshot().closed());
            assertEquals(0, generator.snapshot().issuedIds());
        } finally {
            release.countDown();
            generator.close();
            callers.shutdownNow();
        }
    }

    @Test
    void aStoreThatRegressesCannotRepublishOldIds() {
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(count -> new IdRange(1, 101), options(100))) {
            assertEquals(100, generator.reserveRanges(100).stream().mapToLong(IdRange::size).sum());
            assertEquals(
                    CONFIGURATION,
                    assertThrows(IdGenerationException.class, generator::nextId).reason());
            assertEquals(100, generator.snapshot().issuedIds());
        }
    }

    @Test
    void reconfigurationOnlyChangesFutureDatabaseRequests() throws Exception {
        MemoryStore store = new MemoryStore(1);
        try (SegmentIdGenerator generator = new SegmentIdGenerator(store, options(100))) {
            assertEquals(1, generator.nextId());
            generator.configureStep(
                    new StepPolicy(
                            false,
                            20,
                            1,
                            100,
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(2),
                            2,
                            Duration.ZERO));
            assertEquals(List.of(new IdRange(2, 101)), generator.reserveRanges(99));
            assertEquals(101, generator.nextId());
            assertEquals(List.of(100, 20), store.requests);
        }
    }

    @Test
    void invalidInputAndInvalidStoreGrantCannotAllocate() {
        AtomicInteger calls = new AtomicInteger();
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        count -> {
                            calls.incrementAndGet();
                            return new IdRange(1, 1000);
                        },
                        options(100))) {
            assertThrows(IllegalArgumentException.class, () -> generator.reserveRanges(0));
            assertThrows(IllegalArgumentException.class, () -> generator.reserveRanges(10_001));
            assertEquals(0, calls.get());
            assertEquals(
                    CONFIGURATION,
                    assertThrows(IdGenerationException.class, generator::nextId).reason());
            assertEquals(0, generator.snapshot().issuedIds());
        }
    }

    static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new AssertionError("test barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IdGenerationException(INTERRUPTED, "test interrupted", interrupted);
        }
    }
}
