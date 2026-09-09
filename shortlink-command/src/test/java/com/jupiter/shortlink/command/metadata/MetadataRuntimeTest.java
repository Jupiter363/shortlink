package com.jupiter.shortlink.command.metadata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionSystemException;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Timeout(12)
class MetadataRuntimeTest {
    private final List<MetadataRuntime> runtimes = new ArrayList<>();
    private MetadataJobService jobs;
    private SafeMetadataFetcher fetcher;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void fixture() {
        jobs = mock(MetadataJobService.class);
        fetcher = mock(SafeMetadataFetcher.class);
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void close() {
        runtimes.forEach(MetadataRuntime::close);
    }

    private MetadataRuntime runtime(int workers, Supplier<Consumer<String, String>> factory) {
        var runtime =
                new MetadataRuntime(
                        jobs, fetcher, "unused:9092", "test", workers, registry, factory);
        runtimes.add(runtime);
        return runtime;
    }

    @SuppressWarnings("unchecked")
    private Consumer<String, String> consumer() {
        return mock(Consumer.class);
    }

    private ConsumerRecords<String, String> records() {
        return new ConsumerRecords<>(
                Map.of(
                        new TopicPartition(MetadataJobService.TOPIC, 0),
                        List.of(
                                new ConsumerRecord<>(
                                        MetadataJobService.TOPIC, 0, 40, "key", "valid"),
                                new ConsumerRecord<>(
                                        MetadataJobService.TOPIC, 0, 41, "key", "poison"))));
    }

    @Test
    void onePollCallsOneDurableBatchBeforeOffsetCommitAndExposesInputMetrics() {
        var consumer = consumer();
        when(consumer.poll(any(Duration.class))).thenReturn(records());
        when(jobs.acceptBatch(anyList())).thenReturn(new MetadataJobService.IntakeResult(1, 1));
        runtime(4, () -> consumer).intakePoll(consumer);
        var order = inOrder(jobs, consumer);
        order.verify(consumer).poll(Duration.ofMillis(500));
        order.verify(jobs)
                .acceptBatch(
                        List.of(
                                new MetadataJobService.IntakeRecord(
                                        "valid", MetadataJobService.TOPIC, 0, 40),
                                new MetadataJobService.IntakeRecord(
                                        "poison", MetadataJobService.TOPIC, 0, 41)));
        order.verify(consumer).commitSync(Duration.ofSeconds(3));
        verify(jobs, never()).accept(any(), any(), anyInt(), anyLong());
        assertEquals(
                1,
                registry.get("shortlink.metadata.intake.batches")
                        .tag("outcome", "committed")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.get("shortlink.metadata.intake.records")
                        .tag("outcome", "accepted")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.get("shortlink.metadata.intake.records")
                        .tag("outcome", "rejected")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.get("shortlink.metadata.intake.duration")
                        .tag("outcome", "committed")
                        .timer()
                        .count());
    }

    @Test
    void emptyPollDoesNoDatabaseOrOffsetWork() {
        var consumer = consumer();
        when(consumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        runtime(4, () -> consumer).intakePoll(consumer);
        verifyNoInteractions(jobs);
        verify(consumer, never()).commitSync(any(Duration.class));
    }

    @Test
    void unknownDatabaseCommitClosesConsumerAndReplaysTheBatchOnANewConsumer() throws Exception {
        verifyRecovery(true);
    }

    @Test
    void offsetCommitFailureAlsoRecreatesConsumerInsteadOfPollingPastUncommittedBatch()
            throws Exception {
        verifyRecovery(false);
    }

    private void verifyRecovery(boolean databaseFailure) throws Exception {
        var first = consumer();
        var second = consumer();
        when(first.poll(any(Duration.class))).thenReturn(records());
        CountDownLatch idle = new CountDownLatch(1);
        when(second.poll(any(Duration.class)))
                .thenReturn(records())
                .thenAnswer(
                        call -> {
                            try {
                                idle.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                            }
                            throw new WakeupException();
                        });
        doAnswer(
                        call -> {
                            idle.countDown();
                            return null;
                        })
                .when(second)
                .wakeup();
        if (databaseFailure) {
            when(jobs.acceptBatch(anyList()))
                    .thenThrow(new TransactionSystemException("unknown"))
                    .thenReturn(new MetadataJobService.IntakeResult(1, 1));
        } else {
            when(jobs.acceptBatch(anyList())).thenReturn(new MetadataJobService.IntakeResult(1, 1));
            doThrow(new CommitFailedException()).when(first).commitSync(any(Duration.class));
        }
        AtomicInteger creations = new AtomicInteger();
        var runtime = runtime(4, () -> creations.getAndIncrement() == 0 ? first : second);
        runtime.start();
        await(
                () ->
                        registry.get("shortlink.metadata.intake.offset.commits")
                                        .tag("outcome", "committed")
                                        .counter()
                                        .count()
                                == 1);
        runtime.close();
        assertEquals(2, creations.get());
        verify(first, times(1)).poll(any(Duration.class));
        verify(first).close(MetadataRuntime.CONSUMER_CLOSE_BUDGET);
        verify(first, never()).close();
        if (databaseFailure) verify(first, never()).commitSync(any(Duration.class));
        verify(second).commitSync(Duration.ofSeconds(3));
        verify(second).close(MetadataRuntime.CONSUMER_CLOSE_BUDGET);
        verify(jobs, times(2))
                .acceptBatch(
                        List.of(
                                new MetadataJobService.IntakeRecord(
                                        "valid", MetadataJobService.TOPIC, 0, 40),
                                new MetadataJobService.IntakeRecord(
                                        "poison", MetadataJobService.TOPIC, 0, 41)));
        assertEquals(1, registry.get("shortlink.metadata.intake.reconnects").counter().count());
    }

    @Test
    void shutdownWakesIntakeAndUsesAnExplicitBoundedConsumerClose() throws Exception {
        var consumer = consumer();
        CountDownLatch polling = new CountDownLatch(1), wakeup = new CountDownLatch(1);
        when(consumer.poll(any(Duration.class)))
                .thenAnswer(
                        call -> {
                            polling.countDown();
                            try {
                                wakeup.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                            }
                            throw new WakeupException();
                        });
        doAnswer(
                        call -> {
                            wakeup.countDown();
                            return null;
                        })
                .when(consumer)
                .wakeup();
        var runtime = runtime(4, () -> consumer);
        runtime.start();
        assertTrue(polling.await(2, TimeUnit.SECONDS));
        assertTimeout(Duration.ofSeconds(2), runtime::close);
        verify(consumer).close(Duration.ofSeconds(1));
        verifyNoInteractions(jobs);
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8})
    void oneDefaultScheduledTickRefillsBeyondTheConfiguredWorkerCount(int workers)
            throws Exception {
        assertEquals(
                "${shortlink.metadata.poll-millis:1000}",
                MetadataRuntime.class
                        .getMethod("poll")
                        .getAnnotation(Scheduled.class)
                        .fixedDelayString());
        var available = readyJobs(40);
        AtomicInteger completed = new AtomicInteger(),
                inFlight = new AtomicInteger(),
                max = new AtomicInteger();
        when(fetcher.fetch(anyString()))
                .thenAnswer(
                        call -> {
                            max.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                            try {
                                Thread.sleep(1);
                            } finally {
                                inFlight.decrementAndGet();
                            }
                            return new SafeMetadataFetcher.Metadata("title", null);
                        });
        doAnswer(
                        call -> {
                            completed.incrementAndGet();
                            return null;
                        })
                .when(jobs)
                .complete(any(), any());
        var runtime = runtime(workers, this::consumer);
        runtime.poll();
        await(() -> completed.get() == 40);
        assertTrue(available.isEmpty());
        assertTrue(max.get() <= workers);
        runtime.close();
        verify(jobs, times(40)).complete(any(), any());
    }

    @Test
    void eightWorkersReachTheirBoundWithoutPreclaimingRemainingJobsAndStopOnClose()
            throws Exception {
        var available = readyJobs(40);
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger(), peak = new AtomicInteger();
        when(fetcher.fetch(anyString()))
                .thenAnswer(
                        call -> {
                            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IOException("Test fetch release timed out");
                                }
                                return new SafeMetadataFetcher.Metadata("title", null);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("Test fetch interrupted", interrupted);
                            } finally {
                                inFlight.decrementAndGet();
                            }
                        });
        var runtime = runtime(8, this::consumer);
        try {
            runtime.poll();
            assertTrue(entered.await(2, TimeUnit.SECONDS), "All eight workers must enter fetch");
            assertEquals(8, inFlight.get());
            assertEquals(8, peak.get());

            for (int n = 0; n < 20; n++) runtime.poll();
            assertEquals(32, available.size(), "Only executing jobs may leave the ready queue");
            verify(jobs, times(1)).candidates(8);
            verify(jobs, times(8)).claim(anyString(), anyString());
            for (int n = 0; n < 8; n++) {
                verify(jobs, times(1)).claim(eq("job-" + n), anyString());
            }
            verify(fetcher, times(8)).fetch(anyString());

            // Close interrupts all blocked fetches while the release gate is still closed.
            assertTimeout(Duration.ofSeconds(2), runtime::close);
            assertEquals(0, inFlight.get());
            release.countDown();
            for (int n = 0; n < 20; n++) runtime.poll();
            assertEquals(8, peak.get());
            assertEquals(32, available.size(), "Shutdown must not claim queued database work");
            verify(jobs, times(1)).candidates(8);
            verify(jobs, times(8)).claim(anyString(), anyString());
            verify(fetcher, times(8)).fetch(anyString());
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    void aWorkerYieldsAtSixtyFourJobsEvenIfTheQueueNeverBecomesEmpty() throws Exception {
        var available = readyJobs(1000);
        AtomicInteger completed = new AtomicInteger();
        doAnswer(
                        call -> {
                            completed.incrementAndGet();
                            return null;
                        })
                .when(jobs)
                .complete(any(), any());
        var runtime = runtime(1, this::consumer);
        runtime.poll();
        await(() -> completed.get() == MetadataRuntime.MAX_DRAIN_JOBS);
        Thread.sleep(100);
        assertEquals(64, completed.get());
        assertEquals(936, available.size());
    }

    @Test
    void aSlowInFlightFetchFinishesButTheNextJobWaitsForTheNextTick() throws Exception {
        readyJobs(2);
        CountDownLatch completed = new CountDownLatch(1);
        when(fetcher.fetch(anyString()))
                .thenAnswer(
                        call -> {
                            Thread.sleep(1100);
                            return new SafeMetadataFetcher.Metadata("title", null);
                        });
        doAnswer(
                        call -> {
                            completed.countDown();
                            return null;
                        })
                .when(jobs)
                .complete(any(), any());
        runtime(1, this::consumer).poll();
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        verify(jobs, times(1)).claim(anyString(), anyString());
    }

    @Test
    void emptyQueueAndLostClaimYieldWithoutBusyPolling() throws Exception {
        var runtime = runtime(1, this::consumer);
        runtime.poll();
        Thread.sleep(100);
        verify(jobs, times(1)).candidates(1);
        when(jobs.candidates(1)).thenReturn(List.of("already-claimed"));
        runtime.poll();
        await(
                () ->
                        mockingDetails(jobs).getInvocations().stream()
                                .anyMatch(i -> i.getMethod().getName().equals("claim")));
        Thread.sleep(100);
        verify(jobs, times(2)).candidates(1);
        verify(jobs, times(1)).claim(anyString(), anyString());
        verifyNoInteractions(fetcher);
    }

    @Test
    void ordinaryFetchFailuresContinueRefillingOnlyAfterDurableFailureRecording() throws Exception {
        readyJobs(12);
        AtomicInteger persisted = new AtomicInteger();
        when(fetcher.fetch(anyString())).thenThrow(new IOException("upstream unavailable"));
        doAnswer(
                        call -> {
                            persisted.incrementAndGet();
                            return null;
                        })
                .when(jobs)
                .failed(any(), any());
        runtime(4, this::consumer).poll();
        await(() -> persisted.get() == 12);
        verify(jobs, never()).complete(any(), any());
    }

    @Test
    void failurePersistenceErrorsYieldInsteadOfSpinningOverTheSameReadyJob() throws Exception {
        readyJobs(4);
        when(fetcher.fetch(anyString())).thenThrow(new IOException("upstream unavailable"));
        doThrow(new TransactionSystemException("database unavailable"))
                .when(jobs)
                .failed(any(), any());
        runtime(1, this::consumer).poll();
        await(
                () ->
                        mockingDetails(jobs).getInvocations().stream()
                                .anyMatch(i -> i.getMethod().getName().equals("failed")));
        Thread.sleep(100);
        verify(jobs, times(1)).claim(anyString(), anyString());
        verify(jobs, times(1)).candidates(1);
    }

    @Test
    void completionPersistenceErrorsDoNotTurnIntoFetchFailureOrContinueTheQuantum()
            throws Exception {
        readyJobs(4);
        doThrow(new TransactionSystemException("unknown completion commit"))
                .when(jobs)
                .complete(any(), any());
        runtime(1, this::consumer).poll();
        await(
                () ->
                        mockingDetails(jobs).getInvocations().stream()
                                .anyMatch(i -> i.getMethod().getName().equals("complete")));
        Thread.sleep(100);
        verify(jobs, times(1)).claim(anyString(), anyString());
        verify(jobs, never()).failed(any(), any());
    }

    @Test
    void staleCompletionFenceYieldsWithoutReclassifyingTheJob() throws Exception {
        readyJobs(4);
        doThrow(new MetadataJobService.StaleLeaseException()).when(jobs).complete(any(), any());
        runtime(1, this::consumer).poll();
        await(
                () ->
                        mockingDetails(jobs).getInvocations().stream()
                                .anyMatch(i -> i.getMethod().getName().equals("complete")));
        Thread.sleep(100);
        verify(jobs, times(1)).claim(anyString(), anyString());
        verify(jobs, never()).failed(any(), any());
    }

    @Test
    void claimPersistenceErrorsYieldWithoutFetchingOrImmediateRetry() throws Exception {
        when(jobs.candidates(1)).thenReturn(List.of("job-0"));
        when(jobs.claim(anyString(), anyString()))
                .thenThrow(new TransactionSystemException("unknown claim"));
        runtime(1, this::consumer).poll();
        await(
                () ->
                        mockingDetails(jobs).getInvocations().stream()
                                .anyMatch(i -> i.getMethod().getName().equals("claim")));
        Thread.sleep(100);
        verify(jobs, times(1)).candidates(1);
        verify(jobs, times(1)).claim(anyString(), anyString());
        verifyNoInteractions(fetcher);
    }

    @Test
    void concurrentTicksDoNotClaimAnAlreadyActiveJob() throws Exception {
        var available = readyJobs(1);
        CountDownLatch fetched = new CountDownLatch(1), release = new CountDownLatch(1);
        when(fetcher.fetch(anyString()))
                .thenAnswer(
                        call -> {
                            fetched.countDown();
                            release.await(2, TimeUnit.SECONDS);
                            return new SafeMetadataFetcher.Metadata("title", null);
                        });
        var runtime = runtime(4, this::consumer);
        runtime.poll();
        assertTrue(fetched.await(2, TimeUnit.SECONDS));
        // Present a stale candidate snapshot while the job is active, as concurrent ticks can.
        when(jobs.candidates(4)).thenReturn(List.of("job-0"));
        try {
            for (int n = 0; n < 20; n++) runtime.poll();
            verify(jobs, times(1)).claim(eq("job-0"), anyString());
        } finally {
            when(jobs.candidates(4)).thenAnswer(call -> available.stream().limit(4).toList());
            release.countDown();
        }
    }

    @Test
    void shutdownDuringCandidateLookupPreventsSubmittingAnotherClaim() throws Exception {
        CountDownLatch queried = new CountDownLatch(1), release = new CountDownLatch(1);
        when(jobs.candidates(4))
                .thenAnswer(
                        call -> {
                            queried.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return List.of("late-job");
                        });
        var runtime = runtime(4, this::consumer);
        var scheduler = new Thread(runtime::poll);
        scheduler.start();
        assertTrue(queried.await(2, TimeUnit.SECONDS));
        runtime.close();
        release.countDown();
        scheduler.join(2000);
        assertFalse(scheduler.isAlive());
        runtime.poll();
        verify(jobs, never()).claim(anyString(), anyString());
    }

    @Test
    void shutdownWhileAnAlreadyAdmittedClaimCompletesDoesNotStartItsFetch() throws Exception {
        when(jobs.candidates(1)).thenReturn(List.of("job-0"));
        CountDownLatch claiming = new CountDownLatch(1);
        when(jobs.claim(anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            claiming.countDown();
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ignored) {
                            }
                            return lease("job-0");
                        });
        var runtime = runtime(1, this::consumer);
        runtime.poll();
        assertTrue(claiming.await(2, TimeUnit.SECONDS));
        assertTimeout(Duration.ofSeconds(2), runtime::close);
        verify(jobs, times(1)).claim(anyString(), anyString());
        verifyNoInteractions(fetcher);
    }

    private ConcurrentLinkedQueue<String> readyJobs(int count) {
        var available = new ConcurrentLinkedQueue<String>();
        for (int n = 0; n < count; n++) available.add("job-" + n);
        when(jobs.candidates(anyInt()))
                .thenAnswer(
                        call ->
                                available.stream()
                                        .limit(call.getArgument(0, Integer.class))
                                        .toList());
        when(jobs.claim(anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            String id = call.getArgument(0);
                            return available.remove(id) ? lease(id) : null;
                        });
        return available;
    }

    private MetadataJobService.Lease lease(String id) {
        return new MetadataJobService.Lease(
                id, 1, 1, 1, "digest", "https://example.org/" + id, 1, "owner", 1);
    }

    private static void await(BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(check.getAsBoolean(), "Condition did not complete within bounded wait");
    }
}
