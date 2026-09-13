package com.jupiter.shortlink.agent.securityriskagent.graph;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.jupiter.shortlink.agent.riskanalysis.job.RiskAnalysisJobLeaseManager;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskAnalysisInput;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

class SecurityRiskExecutionDeadlineTest {
    @Test
    void neverEndingSourceIsCancelledAndItsLateNodesCannotPersistOrSaveResults() throws Exception {
        var store = mock(GraphCheckpointStore.class);
        var executor = executor(store, 150);
        var actualGraph = (CompiledGraph) ReflectionTestUtils.getField(executor, "graph");
        var graph = mock(CompiledGraph.class);
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenAnswer(invocation -> {
            captured.set(new LinkedHashMap<>(invocation.getArgument(0)));
            return Flux.<NodeOutput>never().doOnCancel(() -> cancelled.set(true));
        });
        ReflectionTestUtils.setField(executor, "graph", graph);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> executor.execute(request(true)))
                        .isInstanceOf(IllegalStateException.class).hasStackTraceContaining("deadline exceeded"));
        assertThat(cancelled).isTrue();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(executor, "executions")).isEmpty();
        verifyNoInteractions(store);
        var lateState = new OverAllState(captured.get());
        for (String node : List.of("risk_event_persist", "risk_auto_action", "response_compose")) {
            assertThatThrownBy(() -> actualGraph.getNodeAction(node).apply(lateState,
                    RunnableConfig.builder().threadId("late").build()).join())
                    .hasStackTraceContaining("no longer active");
        }
    }

    @Test
    void intermediateNodeEmissionsDoNotResetTheTotalDeadline() {
        var executor = executor(mock(GraphCheckpointStore.class), 220);
        var graph = mock(CompiledGraph.class);
        AtomicInteger emissions = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(
                Flux.interval(Duration.ofMillis(25)).map(index -> {
                    emissions.incrementAndGet();
                    return NodeOutput.of("progress", "risk", new OverAllState(Map.of()), null);
                }).doOnCancel(() -> cancelled.set(true)));
        ReflectionTestUtils.setField(executor, "graph", graph);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> executor.execute(request(true))).hasStackTraceContaining("deadline exceeded"));
        assertThat(emissions.get()).isGreaterThan(1);
        assertThat(cancelled).isTrue();
    }

    @Test
    void synchronousSubscriptionCannotHoldTheCallerAndLateCompletionCannotSaveCheckpoint() throws Exception {
        var store = mock(GraphCheckpointStore.class);
        var executor = executor(store, 180);
        var graph = mock(CompiledGraph.class);
        CountDownLatch subscribed = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenAnswer(invocation -> {
            subscribed.countDown();
            // Deliberately ignores cancellation to model an uncooperative legacy adapter.
            try {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return Flux.just(NodeOutput.of("end", "risk", new OverAllState(Map.of("answer", "late")), null));
            } finally { finished.countDown(); }
        });
        ReflectionTestUtils.setField(executor, "graph", graph);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> executor.execute(request(true))).hasStackTraceContaining("deadline exceeded"));
            assertThat(subscribed.getCount()).isZero();
        } finally { release.countDown(); }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(store);
    }

    @Test
    void finalCheckpointWriteIsInsideTheSameDeadlineAndCannotHoldTheSession() throws Exception {
        var store = mock(GraphCheckpointStore.class);
        var executor = executor(store, 250);
        var graph = mock(CompiledGraph.class);
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenAnswer(invocation -> {
            Map<String, Object> state = new LinkedHashMap<>(invocation.getArgument(0));
            state.put("answer", "ready");
            return Flux.just(NodeOutput.of("end", "risk", new OverAllState(state), null));
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return null;
            } finally { finished.countDown(); }
        }).when(store).save(any());
        ReflectionTestUtils.setField(executor, "graph", graph);
        try {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(() -> executor.execute(request(true))).hasStackTraceContaining("deadline exceeded"));
            assertThat(entered.getCount()).isZero();
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(executor, "executions")).isEmpty();
        } finally { release.countDown(); }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        verify(store, times(1)).save(any()); // Already dispatched writes cannot be recalled.
    }

    @Test
    void timeoutReleasesInteractiveSessionAndBatchAttemptsNeverReuseNativeStateEvenWithSameTrace() {
        var executor = executor(mock(GraphCheckpointStore.class), 200);
        var graph = mock(CompiledGraph.class);
        List<String> threadIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger attempts = new AtomicInteger();
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenAnswer(invocation -> {
            RunnableConfig config = invocation.getArgument(1);
            threadIds.add(config.threadId().orElseThrow());
            if (attempts.getAndIncrement() == 0) return Flux.<NodeOutput>never();
            Map<String, Object> state = new LinkedHashMap<>(invocation.getArgument(0));
            state.put("answer", "done");
            return Flux.just(NodeOutput.of("end", "risk", new OverAllState(state), null));
        });
        ReflectionTestUtils.setField(executor, "graph", graph);
        assertThat(executor.execute(request(false)).warnings()).isNotEmpty();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThat(executor.execute(request(false)).answer()).isEqualTo("done"));
        assertThat(threadIds.get(0)).isEqualTo(threadIds.get(1));
        var first = executor.execute(request(true));
        var second = executor.execute(request(true));
        assertThat(threadIds.get(2)).isNotEqualTo(threadIds.get(3));
        assertThat(first.sessionId()).isEqualTo(second.sessionId()).isEqualTo("same-session");
        assertThat(first.traceId()).isEqualTo(second.traceId()).isEqualTo("stable-trace");
    }

    @Test
    void graphCapturesWorkerLeaseBeforeCrossingReactiveThreadsAndRejectsExpiredExecution() {
        var executor = executor(mock(GraphCheckpointStore.class), 1000);
        var lease = mock(RiskAnalysisJobLeaseManager.Lease.class);
        when(lease.remainingExecutionTime()).thenReturn(Duration.ofMillis(100));
        var graph = mock(CompiledGraph.class);
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.never());
        ReflectionTestUtils.setField(executor, "graph", graph);
        try (var ignored = RiskAnalysisJobLeaseManager.bindExecution(lease)) {
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                // Thread-local binding is established on the actual caller thread.
                try (var inner = RiskAnalysisJobLeaseManager.bindExecution(lease)) {
                    assertThatThrownBy(() -> executor.execute(request(true))).hasStackTraceContaining("deadline exceeded");
                }
            });
        }
        assertThat(RiskAnalysisJobLeaseManager.currentExecution()).isNull();
        verify(lease, atLeastOnce()).assertExecutionActive();
    }

    private static DefaultSecurityRiskGraphExecutor executor(GraphCheckpointStore store, long timeout) {
        var properties = new AgentProperties();
        properties.getGraph().setCheckpointEnabled(true);
        properties.getRisk().getAnalysis().setExecutionTimeoutMillis(timeout);
        return new DefaultSecurityRiskGraphExecutor(mock(LlmChatClient.class), store, properties,
                new AgentToolRegistry(List.of()));
    }

    private static SecurityRiskGraphRequest request(boolean batch) {
        return new SecurityRiskGraphRequest("same-session", "Jupiter", "analyze risk", "stable-trace",
                batch ? new RiskAnalysisInput("batch", "owned", LocalDateTime.of(2026, 9, 14, 0, 0), List.of()) : null,
                new AgentPrincipal("2", "Jupiter", 1, false));
    }
}
