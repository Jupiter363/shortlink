package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessCapacityExecutorTest {
    private static final Demand ALL = new Demand(1, 1, 1);

    @Test
    void configurationAndReferencesAreValidatedWithoutLoadingWork() {
        assertThatThrownBy(() -> new Limits(0, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(1, 1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Demand(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Demand(1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkRef(" ", "work")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkRef("run", "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
        AtomicInteger loads = new AtomicInteger();
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 0), Runnable::run, ref -> loads.incrementAndGet())) {
            assertThatThrownBy(() -> capacity.submit(ref(1), new Demand(2, 1, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertIdle(capacity);
            assertThat(loads).hasValue(0);
        }
    }

    @Test
    void allPermitsAreAcquiredAtomicallyAndAcceptedRunsDoNotBarge() throws Exception {
        ManualExecutor delegate = new ManualExecutor();
        List<String> loaded = new ArrayList<>();
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 2), delegate, ref -> loaded.add(ref.runId()))) {
            Future<Void> model = capacity.submit(ref(1), new Demand(0, 1, 0));
            Future<Void> firstWaiting = capacity.submit(ref(2), ALL);
            Future<Void> secondWaiting = capacity.submit(ref(3), new Demand(1, 0, 1));
            Snapshot waiting = capacity.snapshot();
            assertThat(waiting.activeAdvances()).isZero();
            assertThat(waiting.models()).isEqualTo(1);
            assertThat(waiting.largePayloads()).isZero();
            assertThat(waiting.queued()).isEqualTo(2);
            assertThat(delegate.submissions).isEqualTo(1);
            assertThat(loaded).isEmpty();

            // Head cancellation immediately makes the next Run eligible, without polling.
            assertThat(firstWaiting.cancel(false)).isTrue();
            assertThat(delegate.submissions).isEqualTo(2);
            assertThat(capacity.snapshot().activeAdvances()).isEqualTo(1);
            assertThat(capacity.snapshot().largePayloads()).isEqualTo(1);
            delegate.runAll();
            model.get(1, TimeUnit.SECONDS);
            secondWaiting.get(1, TimeUnit.SECONDS);
            assertThat(loaded).containsExactly("run-1", "run-3");
            assertIdle(capacity);
        }
    }

    @Test
    void queueIsBoundedAndCancelledQueuedReferencesNeverLoadPayloads() throws Exception {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger loads = new AtomicInteger();
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 1), delegate, ref -> loads.incrementAndGet())) {
            Future<Void> first = capacity.submit(ref(1), ALL);
            Future<Void> cancelled = capacity.submit(ref(2), ALL);
            assertThatThrownBy(() -> capacity.submit(ref(3), ALL))
                    .isInstanceOfSatisfying(CapacityRejectedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo(RejectionReason.QUEUE_FULL));
            assertThat(loads).hasValue(0);
            assertThat(cancelled.cancel(true)).isTrue();
            assertThat(cancelled.cancel(true)).isFalse();
            assertThat(capacity.snapshot().queued()).isZero();
            Future<Void> replacement = capacity.submit(ref(4), ALL);
            delegate.runAll();
            first.get(1, TimeUnit.SECONDS);
            replacement.get(1, TimeUnit.SECONDS);
            assertThat(loads).hasValue(2);
            assertIdle(capacity);
        }
    }

    @Test
    void genericDelegateCancellationRetainsReservationUntilRunnableIsConsumed() throws Exception {
        ManualExecutor delegate = new ManualExecutor();
        List<String> loaded = new ArrayList<>();
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 0), delegate, ref -> loaded.add(ref.runId()))) {
            Future<Void> cancelled = capacity.submit(ref(1), ALL);
            assertThat(cancelled.cancel(false)).isTrue();
            assertThat(capacity.snapshot().dispatched()).isEqualTo(1);
            assertThat(capacity.snapshot().activeAdvances()).isEqualTo(1);
            assertThat(capacity.snapshot().queued()).isZero();
            for (int i = 0; i < 1000; i++) {
                int number = i + 2;
                assertThatThrownBy(() -> capacity.submit(ref(number), ALL))
                        .isInstanceOf(CapacityRejectedException.class);
            }
            assertThat(delegate.tasks).hasSize(1);
            assertThat(delegate.submissions).isEqualTo(1);
            delegate.runAll(); // Consumption, not cancellation, releases this unknown delegate's slot.
            assertIdle(capacity);
            Future<Void> next = capacity.submit(ref(2), ALL);
            delegate.runAll();
            next.get(1, TimeUnit.SECONDS);
            assertThat(cancelled.cancel(true)).isFalse();
            assertThat(loaded).containsExactly("run-2");
            assertIdle(capacity);
        }
    }

    @Test
    void repeatedCancellationCannotGrowTheThreadPoolDelegateQueueBeyondAdmission() throws Exception {
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loaded = new AtomicInteger();
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(2, 2, 2, 0), delegate, ref -> {
            loaded.incrementAndGet();
            entered.countDown();
            awaitIgnoringInterrupts(release);
        });
        try {
            Future<Void> first = capacity.submit(ref(1), ALL);
            await(entered);
            for (int i = 0; i < 1000; i++) {
                Future<Void> cancelled = capacity.submit(ref(i + 2), ALL);
                assertThat(delegate.getQueue()).hasSize(1);
                assertThat(cancelled.cancel(false)).isTrue();
                assertThat(delegate.getQueue()).isEmpty();
                assertThat(capacity.snapshot().activeAdvances()).isEqualTo(1);
                assertThat(capacity.snapshot().dispatched()).isZero();
            }
            assertThat(loaded).hasValue(1);
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            delegate.submit(() -> {}).get(2, TimeUnit.SECONDS); // Actual wrapper exit, not just Future completion.
            assertIdle(capacity);
        } finally {
            release.countDown();
            capacity.close();
            stop(delegate);
        }
    }

    @Test
    void cancellationDuringThreadPoolHandoffWaitsForEnqueueThenRemovesTheRealEntry() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch handingOff = new CountDownLatch(1);
        CountDownLatch allowEnqueue = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable task) {
                if (submissions.incrementAndGet() == 2) {
                    handingOff.countDown();
                    awaitIgnoringInterrupts(allowEnqueue);
                }
                super.execute(task);
            }
        };
        List<String> loaded = new ArrayList<>();
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(1, 1, 1, 1), delegate, ref -> {
            loaded.add(ref.runId());
            if (ref.runId().equals("run-1")) {
                firstEntered.countDown();
                awaitIgnoringInterrupts(releaseFirst);
            }
        });
        try {
            Future<Void> first = capacity.submit(ref(1), ALL);
            await(firstEntered);
            Future<Void> cancelled = capacity.submit(ref(2), ALL);
            releaseFirst.countDown();
            await(handingOff);
            assertThat(cancelled.cancel(false)).isTrue();
            assertThat(delegate.getQueue()).isEmpty(); // remove() at this point cannot prove consumption.
            assertThat(capacity.snapshot().dispatched()).isEqualTo(1);
            assertThat(capacity.snapshot().models()).isEqualTo(1);
            Future<Void> next = capacity.submit(ref(3), ALL);
            assertThat(capacity.snapshot().queued()).isEqualTo(1);

            allowEnqueue.countDown();
            first.get(2, TimeUnit.SECONDS);
            next.get(2, TimeUnit.SECONDS);
            delegate.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertThat(delegate.getQueue()).isEmpty();
            assertThat(loaded).containsExactly("run-1", "run-3");
            assertIdle(capacity);
        } finally {
            releaseFirst.countDown();
            allowEnqueue.countDown();
            capacity.close();
            stop(delegate);
        }
    }

    @Test
    void genericHandoffCancellationKeepsTheSlotUntilConsumptionThenDrainsTheNextReference() throws Exception {
        ManualExecutor storage = new ManualExecutor();
        CountDownLatch handingOff = new CountDownLatch(1);
        CountDownLatch allowEnqueue = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        Executor delegate = task -> {
            if (submissions.incrementAndGet() == 2) {
                handingOff.countDown();
                awaitIgnoringInterrupts(allowEnqueue);
            }
            storage.execute(task);
        };
        ExecutorService consumer = Executors.newSingleThreadExecutor();
        List<String> loaded = new ArrayList<>();
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 1), delegate, ref -> loaded.add(ref.runId()));
        try {
            Future<Void> first = capacity.submit(ref(1), ALL);
            Future<Void> cancelled = capacity.submit(ref(2), ALL);
            Future<?> consuming = consumer.submit(storage::runNext);
            await(handingOff);
            assertThat(cancelled.cancel(false)).isTrue();
            assertThat(capacity.snapshot().dispatched()).isEqualTo(1);
            assertThat(capacity.snapshot().models()).isEqualTo(1);
            Future<Void> next = capacity.submit(ref(3), ALL);
            allowEnqueue.countDown();
            consuming.get(2, TimeUnit.SECONDS);

            assertThat(storage.tasks).hasSize(1);
            assertThat(capacity.snapshot().dispatched()).isEqualTo(1);
            assertThat(capacity.snapshot().queued()).isEqualTo(1);
            assertThat(loaded).containsExactly("run-1");
            storage.runAll();
            first.get(1, TimeUnit.SECONDS);
            next.get(1, TimeUnit.SECONDS);
            assertThat(loaded).containsExactly("run-1", "run-3");
            assertIdle(capacity);
        } finally {
            allowEnqueue.countDown();
            capacity.close();
            stop(consumer);
            storage.runAll(); // A generic delegate must consume accepted cancelled Runnables on close.
        }
    }

    @Test
    void timeoutAndCancellationKeepPermitsUntilInterruptIgnoringWorkerActuallyExits() throws Exception {
        ExecutorService delegate = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        CountDownLatch nextEntered = new CountDownLatch(1);
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(1, 1, 1, 1), delegate, ref -> {
            if (ref.runId().equals("run-1")) {
                entered.countDown();
                awaitIgnoringInterrupts(allowExit);
            } else {
                nextEntered.countDown();
            }
        });
        try {
            Future<Void> first = capacity.submit(ref(1), ALL);
            await(entered);
            assertThatThrownBy(() -> first.get(1, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(first.cancel(true)).isTrue();
            assertThat(first.isDone()).isTrue();
            assertThat(capacity.snapshot().running()).isEqualTo(1);
            assertThat(capacity.snapshot().models()).isEqualTo(1);
            Future<Void> next = capacity.submit(ref(2), ALL);
            assertThat(capacity.snapshot().queued()).isEqualTo(1);
            assertThat(nextEntered.getCount()).isEqualTo(1);
            assertThat(first.cancel(true)).isFalse();

            allowExit.countDown();
            await(nextEntered);
            next.get(2, TimeUnit.SECONDS);
        } finally {
            allowExit.countDown();
            capacity.close();
            stop(delegate);
        }
        assertIdle(capacity);
    }

    @Test
    void manyRunsRespectConfiguredConcurrencyAndOnlyLoadAfterAdmission() throws Exception {
        ExecutorService delegate = Executors.newFixedThreadPool(8);
        CountDownLatch firstWave = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger loaded = new AtomicInteger();
        AtomicReference<ProcessCapacityExecutor> owner = new AtomicReference<>();
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(5, 3, 4, 30), delegate, ref -> {
            Snapshot beforeLoad = owner.get().snapshot();
            assertThat(beforeLoad.activeAdvances()).isBetween(1, 5);
            assertThat(beforeLoad.models()).isBetween(1, 3);
            assertThat(beforeLoad.largePayloads()).isBetween(1, 4);
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            // A queued reference has no page object; resolution/allocation happens only here.
            byte[] page = new byte[4096];
            page[0] = 1;
            loaded.incrementAndGet();
            firstWave.countDown();
            try {
                await(release);
                assertThat(page[0]).isEqualTo((byte) 1);
            } finally {
                active.decrementAndGet();
            }
        });
        owner.set(capacity);
        try {
            List<Future<Void>> runs = new ArrayList<>();
            for (int i = 0; i < 24; i++) runs.add(capacity.submit(ref(i), ALL));
            await(firstWave);
            assertThat(capacity.snapshot().queued()).isEqualTo(21);
            assertThat(loaded).hasValue(3);
            release.countDown();
            for (Future<Void> run : runs) run.get(3, TimeUnit.SECONDS);
            assertThat(loaded).hasValue(24);
            assertThat(maximum).hasValue(3);
        } finally {
            release.countDown();
            capacity.close();
            stop(delegate);
        }
        assertIdle(capacity);
    }

    @Test
    void workerFailureStillAdmitsTheNextRun() throws Exception {
        ManualExecutor delegate = new ManualExecutor();
        List<String> loaded = new ArrayList<>();
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(1, 1, 1, 1), delegate, ref -> {
            loaded.add(ref.runId());
            if (ref.runId().equals("run-1")) throw new IllegalStateException("fixture failure");
        })) {
            Future<Void> failing = capacity.submit(ref(1), ALL);
            Future<Void> succeeding = capacity.submit(ref(2), ALL);
            delegate.runAll();
            assertThatThrownBy(() -> failing.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(IllegalStateException.class);
            succeeding.get(1, TimeUnit.SECONDS);
            assertThat(loaded).containsExactly("run-1", "run-2");
            assertIdle(capacity);
        }
    }

    @Test
    void delegateRejectionReturnsAllPermitsAndDoesNotLoadWork() {
        AtomicInteger loaded = new AtomicInteger();
        Executor rejecting = task -> { throw new RejectedExecutionException("fixture rejection"); };
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 0), rejecting, ref -> loaded.incrementAndGet())) {
            for (int i = 0; i < 3; i++) {
                Future<Void> rejected = capacity.submit(ref(i), ALL);
                assertThatThrownBy(() -> rejected.get(1, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CapacityRejectedException.class);
                assertIdle(capacity);
            }
            assertThat(loaded).hasValue(0);
        }
    }

    @Test
    void delegateSubmissionErrorDoesNotStrandTheDrainOrItsReservedPermits() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger loaded = new AtomicInteger();
        Executor delegate = task -> {
            if (submissions.getAndIncrement() == 0) throw new AssertionError("fixture executor failure");
            task.run();
        };
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 0), delegate, ref -> loaded.incrementAndGet())) {
            Future<Void> failed = capacity.submit(ref(1), ALL);
            assertThatThrownBy(() -> failed.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CapacityRejectedException.class);
            assertIdle(capacity);
            capacity.submit(ref(2), ALL).get(1, TimeUnit.SECONDS);
            assertThat(loaded).hasValue(1);
            assertIdle(capacity);
        }
    }

    @Test
    void callerRunsCannotBypassTheSharedPermitLimit() throws Exception {
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        ExecutorService submitter = Executors.newSingleThreadExecutor();
        CountDownLatch delegateOccupied = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<Thread> submittingThread = new AtomicReference<>();
        AtomicReference<Thread> workingThread = new AtomicReference<>();
        delegate.execute(() -> { delegateOccupied.countDown(); awaitIgnoringInterrupts(releaseDelegate); });
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(1, 1, 1, 0), delegate, ref -> {
            workingThread.set(Thread.currentThread());
            workerEntered.countDown();
            awaitIgnoringInterrupts(releaseWorker);
        });
        try {
            await(delegateOccupied);
            Future<Future<Void>> submitted = submitter.submit(() -> {
                submittingThread.set(Thread.currentThread());
                return capacity.submit(ref(1), ALL);
            });
            await(workerEntered);
            assertThat(workingThread.get()).isSameAs(submittingThread.get());
            assertThatThrownBy(() -> capacity.submit(ref(2), ALL)).isInstanceOf(CapacityRejectedException.class);
            assertThat(capacity.snapshot().models()).isEqualTo(1);
            releaseWorker.countDown();
            submitted.get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS);
            assertIdle(capacity);
        } finally {
            releaseWorker.countDown();
            releaseDelegate.countDown();
            capacity.close();
            stop(submitter);
            stop(delegate);
        }
    }

    @Test
    void callerRunsShutdownRaceCannotSilentlyLeakAdmittedPermits() {
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy()) {
            @Override
            public void execute(Runnable task) {
                shutdown(); // Happens after wrapper's pre-dispatch check; CallerRuns silently drops.
                super.execute(task);
            }
        };
        AtomicInteger loaded = new AtomicInteger();
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 0), delegate, ref -> loaded.incrementAndGet())) {
            Future<Void> rejected = capacity.submit(ref(1), ALL);
            assertThatThrownBy(() -> rejected.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CapacityRejectedException.class);
            assertThat(loaded).hasValue(0);
            assertIdle(capacity);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void silentlyDiscardingExecutorPoliciesAreRefused() {
        ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.DiscardPolicy());
        try {
            assertThatThrownBy(() -> new ProcessCapacityExecutor(new Limits(1, 1, 1, 0), delegate, ref -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
            delegate.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
            assertThatThrownBy(() -> new ProcessCapacityExecutor(new Limits(1, 1, 1, 0), delegate, ref -> {}))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            delegate.shutdownNow();
        }
    }

    @Test
    void fifoDrainIsEventDrivenAndDoesNotRecursivelyRunLongInlineChains() throws Exception {
        ManualExecutor initiallyQueued = new ManualExecutor();
        AtomicBoolean inline = new AtomicBoolean();
        List<String> order = new ArrayList<>();
        Executor delegate = task -> { if (inline.get()) task.run(); else initiallyQueued.execute(task); };
        try (ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 1024), delegate, ref -> order.add(ref.runId()))) {
            List<Future<Void>> tasks = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i <= 1024; i++) {
                tasks.add(capacity.submit(ref(i), ALL));
                expected.add(ref(i).runId());
            }
            assertThat(initiallyQueued.submissions).isEqualTo(1);
            assertThat(order).isEmpty();
            inline.set(true);
            initiallyQueued.runAll();
            for (Future<Void> task : tasks) task.get(1, TimeUnit.SECONDS);
            assertThat(order).containsExactlyElementsOf(expected);
            assertIdle(capacity);
        }
    }

    @Test
    void closeCancelsQueuedAndDispatchedReferencesWithoutLoadingThem() {
        ManualExecutor delegate = new ManualExecutor();
        AtomicInteger loaded = new AtomicInteger();
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(
                new Limits(1, 1, 1, 1), delegate, ref -> loaded.incrementAndGet());
        Future<Void> dispatched = capacity.submit(ref(1), ALL);
        Future<Void> queued = capacity.submit(ref(2), ALL);
        capacity.close();
        capacity.close();
        assertThat(capacity.snapshot().closed()).isTrue();
        assertThat(capacity.snapshot().queued()).isZero();
        assertThat(capacity.snapshot().dispatched()).isEqualTo(1);
        assertThat(capacity.snapshot().models()).isEqualTo(1);
        assertThat(delegate.tasks).hasSize(1);
        delegate.runAll();
        assertThat(dispatched.isCancelled()).isTrue();
        assertThat(queued.isCancelled()).isTrue();
        assertThat(loaded).hasValue(0);
        assertIdle(capacity);
        assertThatThrownBy(() -> capacity.submit(ref(3), ALL))
                .isInstanceOfSatisfying(CapacityRejectedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(RejectionReason.CLOSED));
    }

    @Test
    void closeDoesNotTreatAnInterruptIgnoringWorkerAsExited() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger loaded = new AtomicInteger();
        ProcessCapacityExecutor capacity = new ProcessCapacityExecutor(new Limits(1, 1, 1, 1), delegate, ref -> {
            loaded.incrementAndGet();
            entered.countDown();
            awaitIgnoringInterrupts(allowExit);
        });
        try {
            Future<Void> running = capacity.submit(ref(1), ALL);
            await(entered);
            Future<Void> queued = capacity.submit(ref(2), ALL);
            capacity.close();
            assertThat(running.isCancelled()).isTrue();
            assertThat(queued.isCancelled()).isTrue();
            assertThat(capacity.snapshot().running()).isEqualTo(1);
            assertThat(capacity.snapshot().models()).isEqualTo(1);
            assertThat(capacity.snapshot().queued()).isZero();
        } finally {
            allowExit.countDown();
            capacity.close();
            stop(delegate);
        }
        assertThat(loaded).hasValue(1);
        assertIdle(capacity);
    }

    private static WorkRef ref(int number) {
        return new WorkRef("run-" + number, "work-" + number);
    }

    private static void assertIdle(ProcessCapacityExecutor capacity) {
        Snapshot snapshot = capacity.snapshot();
        assertThat(snapshot.activeAdvances()).isZero();
        assertThat(snapshot.models()).isZero();
        assertThat(snapshot.largePayloads()).isZero();
        assertThat(snapshot.queued()).isZero();
        assertThat(snapshot.dispatched()).isZero();
        assertThat(snapshot.running()).isZero();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("fixture latch must be released").isTrue();
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    assertThat(remaining).as("fixture must not hang").isPositive();
                    assertThat(latch.await(remaining, TimeUnit.NANOSECONDS)).isTrue();
                    return;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private int submissions;

        @Override
        public void execute(Runnable command) {
            submissions++;
            tasks.addLast(command);
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) task.run();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }
}
