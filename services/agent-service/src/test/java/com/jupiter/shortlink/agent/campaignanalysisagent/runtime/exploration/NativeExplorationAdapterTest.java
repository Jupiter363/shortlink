package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.ExecutionKey;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.Observation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.RegisteredTool;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** The real native model/tool graph runs against local scripted models and a non-durable ledger. */
class NativeExplorationAdapterTest {
    private static final Limits LIMITS = new Limits(1, 4096, 1024, 16384, 8, Duration.ofSeconds(5));

    @Test
    void singleCallUsesOneNativeToolLoopAndOnlyAnArtifactReferenceReachesTheNextModel() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel()
                .thenReturn(ScriptedExplorationChatModel.toolCalls(call("call-1")))
                .then(prompt -> {
                    assertThat(dispatches.get()).isEqualTo(1);
                    var responses = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
                    assertThat(responses).hasSize(1);
                    assertThat(responses.get(0).id()).isEqualTo("call-1");
                    assertThat(responses.get(0).responseData()).contains("artifact-1").doesNotContain("raw-evidence");
                    return ScriptedExplorationChatModel.text("Evidence is ready");
                });
        var saver = new BoundarySaver();
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            dispatches.incrementAndGet();
            return "raw-evidence";
        }), Observation.ready("artifact-1"), saver, LIMITS);

        assertThat(adapter.invoke("Inspect the authorized scope")).containsEntry("status", "CANDIDATE")
                .containsEntry("artifactIds", List.of("artifact-1"));
        var originalWrites = List.copyOf(saver.checkpoints);
        assertThat(adapter.invoke("Repeat completed candidate")).containsEntry("status", "CANDIDATE");
        assertThat(saver.checkpoints).containsExactlyElementsOf(originalWrites);
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(ledger.acceptedCalls()).containsExactly("call-1:query");
        model.assertExhausted();
    }

    @Test
    void validMultiCallBatchHasZeroDispatchAndExactlyOnePairedRejectionPerIdBeforeRepair() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel()
                .thenReturn(ScriptedExplorationChatModel.toolCalls(call("batch-1"), call("batch-2")))
                .then(prompt -> {
                    assertThat(dispatches.get()).isZero();
                    var responses = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
                    assertThat(responses).extracting(ToolResponseMessage.ToolResponse::id)
                            .containsExactly("batch-1", "batch-2");
                    assertThat(responses).allSatisfy(response -> assertThat(response.responseData())
                            .isEqualTo("{\"executed\":false,\"code\":\"BATCH_REJECTED\"}"));
                    return ScriptedExplorationChatModel.toolCalls(call("repaired-1"));
                }).thenReturn(ScriptedExplorationChatModel.text("done"));
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            dispatches.incrementAndGet();
            return "data";
        }), Observation.ready("artifact-repaired"), new MemorySaver(), LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "CANDIDATE");
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(model.callCount()).isEqualTo(3);
        assertThat(ledger.acceptedCalls()).containsExactly("repaired-1:query");
        assertThat(ledger.repairs()).isEqualTo(1);
        model.assertExhausted();
    }

    @Test
    void rejectedBatchRepairBudgetPersistsAcrossResumeAndNeverDispatches() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel()
                .thenReturn(ScriptedExplorationChatModel.toolCalls(call("a"), call("b")))
                .thenReturn(ScriptedExplorationChatModel.toolCalls(call("c"), call("d")));
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            dispatches.incrementAndGet();
            return "data";
        }), Observation.ready("unused"), new MemorySaver(), LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "BLOCKED")
                .containsEntry("reason", "PROTOCOL_REPAIR_EXHAUSTED");
        assertThat(adapter.invoke("Resume")).containsEntry("status", "BLOCKED");
        assertThat(model.callCount()).isEqualTo(2);
        assertThat(dispatches.get()).isZero();
        assertThat(ledger.repairs()).isEqualTo(2);
        model.assertExhausted();
    }

    @ParameterizedTest(name = "malformed tool-call IDs: {0}")
    @MethodSource("malformedCalls")
    void malformedIdsStopBeforeAnyToolDispatch(String name, List<AssistantMessage.ToolCall> calls) throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel().thenReturn(
                ScriptedExplorationChatModel.toolCalls(calls.toArray(AssistantMessage.ToolCall[]::new)));
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            dispatches.incrementAndGet();
            return "data";
        }), Observation.ready("unused"), new MemorySaver(), LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "FAILED")
                .containsEntry("reason", "MODEL_PROTOCOL_INVALID");
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(dispatches.get()).isZero();
        assertThat(ledger.acceptedCalls()).isEmpty();
    }

    static Stream<Arguments> malformedCalls() {
        return Stream.of(Arguments.of("empty", List.of(call(""))),
                Arguments.of("blank", List.of(call("  "))),
                Arguments.of("null", List.of(call(null))),
                Arguments.of("duplicate", List.of(call("duplicate"), call("duplicate"))));
    }

    @Test
    void pendingObservationStopsBeforeAnyAdditionalModelOrToolCallIncludingResume() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(call("submit")));
        var saver = new BoundarySaver();
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            dispatches.incrementAndGet();
            return "job accepted";
        }), Observation.pending("job-1"), saver, LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "WAITING").containsEntry("jobId", "job-1");
        var originalWrites = List.copyOf(saver.checkpoints);
        assertThat(originalWrites).isNotEmpty();
        for (int resume = 0; resume < 5; resume++)
            assertThat(adapter.invoke("Resume without a receipt " + resume)).containsEntry("status", "WAITING");
        assertThat(saver.checkpoints).as("no START checkpoint or appended user message for a waiting step")
                .containsExactlyElementsOf(originalWrites);
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(ledger.view().activeCallbacks()).isZero();
    }

    @Test
    void queuedNativeTimeoutStillExecutesCleanupWithoutDispatchingTheExpiredCallback() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var queued = new ConcurrentLinkedQueue<Runnable>();
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(call("queued")));
        var callback = tool((input, context) -> {
            dispatches.incrementAndGet();
            return "must never execute";
        });
        var adapter = new NativeExplorationAdapter(key, ledger, model,
                List.of(new RegisteredTool(callback, raw -> Observation.ready("unused"))),
                new MemorySaver(), queued::add,
                new Limits(1, 4096, 1024, 16384, 8, Duration.ofMillis(100)));

        var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> adapter.invoke("Queued query"));

        assertThat(result).containsEntry("status", "BLOCKED").containsEntry("reason", "EXECUTION_UNRESOLVED");
        assertThat(queued).hasSize(1);
        assertThat(ledger.view().activeCallbacks()).as("the queued runnable has not exited yet").isEqualTo(1);
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(dispatches.get()).isZero();
        queued.remove().run();
        assertThat(ledger.view().activeCallbacks()).as("timed-out promise must not suppress runnable finally").isZero();
        assertThat(dispatches.get()).isZero();
        assertThat(ledger.view().artifactIds()).isEmpty();
        assertThat(adapter.invoke("Resume after cleanup")).containsEntry("status", "BLOCKED");
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    void rejectedExecutorBalancesAdmissionAndCannotTriggerAnotherModelTurn() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(call("rejected")));
        var callback = tool((input, context) -> {
            dispatches.incrementAndGet();
            return "unused";
        });
        var adapter = new NativeExplorationAdapter(key, ledger, model,
                List.of(new RegisteredTool(callback, raw -> Observation.ready("unused"))), new MemorySaver(),
                command -> { throw new RejectedExecutionException("test executor is closed"); }, LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "BLOCKED");
        assertThat(ledger.view().activeCallbacks()).isZero();
        assertThat(dispatches.get()).isZero();
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(ledger.acceptedCalls()).containsExactly("rejected:query");
    }

    @Test
    void callbackErrorMakesTheLedgerTerminalBeforeNativeErrorMappingCanContinueToTheModel() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(call("error")));
        var saver = new BoundarySaver();
        var adapter = adapter(key, ledger, model,
                tool((input, context) -> { throw new AssertionError("callback bug fixture"); }),
                Observation.ready("unused"), saver, LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "BLOCKED")
                .containsEntry("reason", "EXECUTION_UNRESOLVED");
        assertThat(ledger.view().activeCallbacks()).isZero();
        assertThat(ledger.view().artifactIds()).isEmpty();
        assertThat(model.callCount()).isEqualTo(1);
        var originalWrites = List.copyOf(saver.checkpoints);
        assertThat(adapter.invoke("Resume")).containsEntry("status", "BLOCKED");
        assertThat(saver.checkpoints).containsExactlyElementsOf(originalWrites);
        assertThat(model.callCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "oversized private callback {0} never enters native state")
    @ValueSource(strings = {"exception", "error"})
    void oversizedSensitiveCallbackFailureIsProjectedToFixedCodeBeforeAnyCheckpoint(String kind) throws Exception {
        String privateFailure = "TOOL_EXCEPTION_PRIVATE_TOKEN_" + "x".repeat(200_000);
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(call("private-error")));
        var saver = new BoundarySaver();
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            if ("error".equals(kind)) throw new AssertionError(privateFailure);
            throw new IllegalStateException(privateFailure);
        }), Observation.ready("unused"), saver, LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "BLOCKED")
                .containsEntry("reason", "EXECUTION_UNRESOLVED");
        assertThat(ledger.view().activeCallbacks()).isZero();
        assertThat(ledger.view().artifactIds()).isEmpty();
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(saver.checkpoints).isNotEmpty().allSatisfy(checkpoint -> {
            assertThat(checkpoint).doesNotContain("TOOL_EXCEPTION_PRIVATE_TOKEN_");
            assertThat(checkpoint.length()).isLessThan(32 * 1024);
        }).anySatisfy(checkpoint -> assertThat(checkpoint).contains("TOOL_EXECUTION_FAILED"));
        assertThat(model.prompts()).allSatisfy(prompt -> assertThat(prompt.getInstructions().toString())
                .doesNotContain("TOOL_EXCEPTION_PRIVATE_TOKEN_"));
    }

    @Test
    void nativeTimeoutKeepsTheCallbackPermitUntilActualExitAndRevokesItsLaterChildDispatch() throws Exception {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var firstDispatches = new AtomicInteger();
        var laterDispatches = new AtomicInteger();
        var lateFailure = new AtomicReference<Exception>();
        var nativeUpdateMap = new AtomicReference<Map<String, Object>>();
        var executor = Executors.newSingleThreadExecutor();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(call("slow")));
        var callback = tool((input, context) -> {
            var scope = (NativeExplorationAdapter.DispatchScope) context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
            try {
                var updates = ToolContextHelper.getStateForUpdate(context).orElseThrow();
                updates.put("TIMEOUT_STATE_SENTINEL", "waiting-only-in-native-map");
                nativeUpdateMap.set(updates);
                scope.dispatch(firstDispatches::incrementAndGet);
                entered.countDown();
                // Simulate a client that ignores native cancellation/interruption while a request is in flight.
                boolean waiting = true;
                while (waiting) {
                    try { release.await(); waiting = false; }
                    catch (InterruptedException ignored) { /* Deliberately continue the already-started operation. */ }
                }
                try { scope.dispatch(laterDispatches::incrementAndGet); }
                catch (Exception revoked) { lateFailure.set(revoked); }
                return "late raw receipt";
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally { exited.countDown(); }
        });
        var limits = new Limits(1, 4096, 1024, 16384, 8, Duration.ofMillis(150));
        var adapter = new NativeExplorationAdapter(key, ledger, model,
                List.of(new RegisteredTool(callback, raw -> Observation.ready("late-artifact"))),
                new MemorySaver(), executor, limits);
        try {
            var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> adapter.invoke("Slow query"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(result).containsEntry("status", "BLOCKED").containsEntry("reason", "EXECUTION_UNRESOLVED");
            assertThat(ledger.view().activeCallbacks()).isEqualTo(1);
            // AgentToolNode's timeout clears its state update map. Admission still consults the external ledger.
            assertThat(nativeUpdateMap.get()).doesNotContainKey("TIMEOUT_STATE_SENTINEL");
            assertThat(adapter.invoke("Resume while old callback is alive")).containsEntry("status", "BLOCKED");
            assertThat(model.callCount()).isEqualTo(1);
            release.countDown();
            assertThat(exited.await(3, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
            assertThat(firstDispatches.get()).isEqualTo(1);
            assertThat(laterDispatches.get()).isZero();
            assertThat(lateFailure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(ledger.view().activeCallbacks()).isZero();
            assertThat(ledger.view().artifactIds()).isEmpty();
            assertThat(ledger.view().status()).isEqualTo(ExplorationLedger.Status.BLOCKED);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest(name = "oversized model {0} is rejected before checkpoint")
    @ValueSource(strings = {"text", "arguments", "metadata", "batch"})
    void oversizedModelResponseIsRejectedBeforeItCanEnterAnyNativeCheckpoint(String field) throws Exception {
        String raw = "MODEL_OVERSIZE_MARKER_" + "x".repeat(200_000);
        AssistantMessage message = switch (field) {
            case "text" -> new AssistantMessage(raw);
            case "arguments" -> AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("large", "function", "query", raw))).build();
            case "metadata" -> AssistantMessage.builder().content("small").properties(Map.of("provider_reasoning", raw)).build();
            default -> AssistantMessage.builder().content("").toolCalls(java.util.stream.IntStream.range(0, 9)
                    .mapToObj(index -> call("call-" + index)).toList()).build();
        };
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var saver = new BoundarySaver();
        var dispatches = new AtomicInteger();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.response(message, "stop"));
        var adapter = adapter(key, ledger, model, tool((input, context) -> {
            dispatches.incrementAndGet();
            return "unused";
        }), Observation.ready("unused"), saver, LIMITS);

        assertThat(adapter.invoke("Inspect")).containsEntry("status", "FAILED")
                .containsEntry("reason", "MODEL_RESPONSE_REJECTED");
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(dispatches.get()).isZero();
        assertThat(saver.checkpoints).isNotEmpty().allSatisfy(value -> {
            assertThat(value).doesNotContain("MODEL_OVERSIZE_MARKER_");
            assertThat(value.length()).isLessThan(32 * 1024);
        });
    }

    @Test
    void oversizedInputIsRejectedBeforeNativeGraphAndMismatchedLedgerCannotBeMounted() {
        var key = key();
        var ledger = new ExplorationTestLedger(key);
        var model = new ScriptedExplorationChatModel();
        var adapter = new NativeExplorationAdapter(key, ledger, model, List.of(), new MemorySaver(), Runnable::run, LIMITS);
        assertThatThrownBy(() -> adapter.invoke("x".repeat(4097))).isInstanceOf(IllegalArgumentException.class);
        assertThat(model.callCount()).isZero();
        var other = new ExecutionKey("other", "alice", 1, "session", "run", "plan", 1,
                "step", "policy-v1", "runner-v1", "topology-v1");
        assertThatThrownBy(() -> new NativeExplorationAdapter(other, ledger, model, List.of(),
                new MemorySaver(), Runnable::run, LIMITS)).isInstanceOf(IllegalArgumentException.class);
    }

    private static NativeExplorationAdapter adapter(ExecutionKey key, ExplorationTestLedger ledger,
            ScriptedExplorationChatModel model, ToolCallback tool, Observation observation, MemorySaver saver, Limits limits) {
        return new NativeExplorationAdapter(key, ledger, model,
                List.of(new RegisteredTool(tool, raw -> observation)), saver, Runnable::run, limits);
    }

    private static ToolCallback tool(BiFunction<String, ToolContext, String> body) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("query").description("Read authorized test evidence")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String input) { return body.apply(input, new ToolContext(Map.of())); }
            @Override public String call(String input, ToolContext context) { return body.apply(input, context); }
        };
    }

    private static AssistantMessage.ToolCall call(String id) {
        return new AssistantMessage.ToolCall(id, "function", "query", "{}");
    }

    private static ExecutionKey key() {
        return new ExecutionKey("tenant", "alice", 1, "session", "run", "plan", 1,
                "step", "policy-v1", "runner-v1", "topology-v1");
    }

    private static final class BoundarySaver extends MemorySaver {
        final List<String> checkpoints = new ArrayList<>();
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values,
                Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values,
                Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        private void capture(Checkpoint checkpoint) throws Exception {
            checkpoints.add(AgentStateSerializerFactory.create().objectMapper().writeValueAsString(checkpoint.getState()));
        }
    }
}
