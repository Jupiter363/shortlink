package com.nageoffer.shortlink.agent.migration;

import com.nageoffer.shortlink.agent.harness.checkpoint.GraphSessionExecutionCoordinator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSessionExecutionCoordinatorTest {

    @Test
    void sameSessionGraphRunsNeverOverlap() throws Exception {
        GraphSessionExecutionCoordinator coordinator = new GraphSessionExecutionCoordinator(8);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximumInFlight = new AtomicInteger();

        try {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> callUnchecked(
                    coordinator,
                    "campaign-analysis:v1:session-001",
                    () -> {
                        recordConcurrency(inFlight, maximumInFlight);
                        firstEntered.countDown();
                        await(releaseFirst);
                        inFlight.decrementAndGet();
                        return null;
                    }
            ), executor);
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                secondAttempted.countDown();
                callUnchecked(coordinator, "campaign-analysis:v1:session-001", () -> {
                    recordConcurrency(inFlight, maximumInFlight);
                    inFlight.decrementAndGet();
                    return null;
                });
            }, executor);
            assertThat(secondAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone())
                    .as("the second invocation must wait for the same session lock")
                    .isFalse();
            releaseFirst.countDown();

            assertThatCode(() -> CompletableFuture.allOf(first, second).get(2, TimeUnit.SECONDS))
                    .doesNotThrowAnyException();
            assertThat(maximumInFlight).hasValue(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedSessionRunReleasesTheLockForTheNextRequest() throws Exception {
        GraphSessionExecutionCoordinator coordinator = new GraphSessionExecutionCoordinator(1);

        assertThatThrownBy(() -> coordinator.execute("security-risk:v1:session-001", () -> {
            throw new IllegalStateException("graph failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("graph failed");
        assertThat(coordinator.execute("security-risk:v1:session-001", () -> "recovered"))
                .isEqualTo("recovered");
    }

    private void recordConcurrency(AtomicInteger inFlight, AtomicInteger maximumInFlight) {
        int current = inFlight.incrementAndGet();
        maximumInFlight.accumulateAndGet(current, Math::max);
    }

    private void callUnchecked(GraphSessionExecutionCoordinator coordinator, String threadKey,
                               java.util.concurrent.Callable<Void> operation) {
        try {
            coordinator.execute(threadKey, operation);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }
}
