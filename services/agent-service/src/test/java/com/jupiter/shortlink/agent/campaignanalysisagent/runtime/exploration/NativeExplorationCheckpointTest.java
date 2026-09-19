package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.ExecutionKey;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.Observation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.RegisteredTool;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Exercises native graphs and every saver write; no database, model service or advisor is involved. */
class NativeExplorationCheckpointTest {
    private static final String PARENT_SENTINEL = "PARENT_ONLY_SENTINEL";
    private static final String RAW_MARKER = "RAW_TOOL_SOURCE_CUT_MUST_STAY_IN_ARTIFACT";
    private static final Limits LIMITS = new Limits(2, 4096, 1024, 16384, 8, Duration.ofSeconds(5));

    @Test
    void nativeAsNodeWithoutContentsStillCopiesNonMessageParentStateIntoChildCheckpoints() throws Exception {
        var saver = new ObservingMemorySaver();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.text("child completed"));
        ReactAgent child = ReactAgent.builder().name("native_checkpoint_child").model(model)
                .saver(saver).releaseThread(false).stateSerializer(AgentStateSerializerFactory.create())
                .outputKey("childResult").build();
        var parent = new StateGraph("native-parent-negative", Map::of, AgentStateSerializerFactory.create())
                .addNode("native_checkpoint_child", child.asNode(false, false))
                .addEdge(StateGraph.START, "native_checkpoint_child").addEdge("native_checkpoint_child", StateGraph.END)
                .compile(compileConfig(saver));
        String parentThread = "native-parent-thread";

        parent.invoke(new LinkedHashMap<>(Map.of(PARENT_SENTINEL, "private-parent-value",
                "messages", List.of(new UserMessage("Parent-only input")))), config(parentThread)).orElseThrow();

        assertThat(model.callCount()).isEqualTo(1);
        model.assertExhausted();
        List<SavedState> childWrites = saver.writes().stream()
                .filter(write -> !parentThread.equals(write.threadId())).toList();
        assertThat(childWrites).as("the shared native saver must observe a separate child checkpoint chain")
                .isNotEmpty().anySatisfy(write -> assertThat(write.keys()).contains(PARENT_SENTINEL));
        assertThat(childWrites).anySatisfy(write -> assertThat(write.stateText()).contains("private-parent-value"));
    }

    @Test
    void projectedNodeKeepsRawToolDataAndParentStateOutOfEveryNativeChildCheckpoint() throws Exception {
        var saver = new ObservingMemorySaver();
        var key = identity();
        var ledger = new ExplorationTestLedger(key);
        String raw = RAW_MARKER + "x".repeat(1_200_000);
        var persisted = new AtomicReference<String>();
        var toolCalls = new AtomicInteger();
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_large_evidence").description("Read test evidence")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String input) {
                toolCalls.incrementAndGet();
                return raw;
            }
        };
        var model = new ScriptedExplorationChatModel(
                prompt -> ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall(
                        "large-evidence-call", "function", "read_large_evidence", "{}")),
                prompt -> {
                    assertThat(prompt.getInstructions().toString()).contains("artifact-large-evidence")
                            .doesNotContain(RAW_MARKER, PARENT_SENTINEL);
                    return ScriptedExplorationChatModel.text("CHILD_INTERNAL_ANSWER");
                });
        var adapter = new NativeExplorationAdapter(key, ledger, model,
                List.of(new RegisteredTool(tool, value -> {
                    persisted.set(value);
                    return Observation.ready("artifact-large-evidence");
                })), saver, Runnable::run, LIMITS);
        CompiledGraph parent = new StateGraph("projected-parent", Map::of, AgentStateSerializerFactory.create())
                .addNode("explore", adapter.projectedNode())
                .addEdge(StateGraph.START, "explore").addEdge("explore", StateGraph.END)
                .compile(compileConfig(saver));

        var result = parent.invoke(new LinkedHashMap<>(Map.of(
                NativeExplorationAdapter.INPUT_KEY, "Read the authorized evidence",
                PARENT_SENTINEL, "private-parent-value")), config("projected-parent-thread")).orElseThrow();

        assertThat(toolCalls.get()).isEqualTo(1);
        assertThat(persisted.get()).isEqualTo(raw);
        assertThat(model.callCount()).isEqualTo(2);
        model.assertExhausted();
        assertThat(ledger.acceptedCalls()).containsExactly("large-evidence-call:read_large_evidence");
        assertThat(ledger.view().artifactIds()).containsExactly("artifact-large-evidence");
        assertThat(result.data().get(NativeExplorationAdapter.OUTPUT_KEY).toString())
                .contains("artifact-large-evidence").doesNotContain("CHILD_INTERNAL_ANSWER", RAW_MARKER);

        assertThat(saver.writes()).isNotEmpty().allSatisfy(write -> {
            assertThat(write.serializedBytes()).as("serialized checkpoint for %s/%s", write.threadId(), write.nodeId())
                    .isLessThan(64 * 1024);
            assertThat(write.stateText()).doesNotContain(RAW_MARKER);
        });
        List<SavedState> childWrites = saver.forThread(key.threadId());
        assertThat(childWrites).isNotEmpty().allSatisfy(write -> {
            assertThat(write.keys()).doesNotContain(PARENT_SENTINEL, NativeExplorationAdapter.INPUT_KEY);
            assertThat(write.stateText()).doesNotContain("private-parent-value", PARENT_SENTINEL);
        });
        assertThat(childWrites).anySatisfy(write -> assertThat(write.stateText()).contains("artifact-large-evidence"));
        assertThat(saver.forThread("projected-parent-thread")).isNotEmpty()
                .allSatisfy(write -> assertThat(write.stateText()).doesNotContain("CHILD_INTERNAL_ANSWER"));
        assertThat(model.prompts()).allSatisfy(prompt -> assertThat(prompt.getInstructions().toString())
                .doesNotContain(RAW_MARKER, PARENT_SENTINEL));
    }

    @ParameterizedTest(name = "shared saver isolates execution identity field {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void allExecutionIdentityFieldsIsolateNativeMessagesAndCheckpoints(int changedField) throws Exception {
        var saver = new ObservingMemorySaver();
        ExecutionKey firstKey = identity();
        ExecutionKey secondKey = changedIdentity(changedField);
        var firstLedger = new ExplorationTestLedger(firstKey);
        var secondLedger = new ExplorationTestLedger(secondKey);
        var firstModel = new ScriptedExplorationChatModel()
                .thenReturn(ScriptedExplorationChatModel.text("FIRST_EXECUTION_ONLY_RESPONSE"));
        var secondModel = new ScriptedExplorationChatModel(prompt -> {
            assertThat(prompt.getInstructions().toString()).contains("SECOND_EXECUTION_REQUEST")
                    .doesNotContain("FIRST_EXECUTION_ONLY_PROMPT", "FIRST_EXECUTION_ONLY_RESPONSE");
            return ScriptedExplorationChatModel.text("SECOND_EXECUTION_RESPONSE");
        });
        var first = new NativeExplorationAdapter(firstKey, firstLedger, firstModel, List.of(), saver, Runnable::run, LIMITS);
        var second = new NativeExplorationAdapter(secondKey, secondLedger, secondModel, List.of(), saver, Runnable::run, LIMITS);

        first.invoke("FIRST_EXECUTION_ONLY_PROMPT");
        second.invoke("SECOND_EXECUTION_REQUEST");

        assertThat(secondKey.threadId()).isNotEqualTo(firstKey.threadId());
        assertThat(firstModel.callCount()).isEqualTo(1);
        assertThat(secondModel.callCount()).isEqualTo(1);
        firstModel.assertExhausted();
        secondModel.assertExhausted();
        assertThat(saver.forThread(firstKey.threadId())).isNotEmpty().allSatisfy(write ->
                assertThat(write.stateText()).doesNotContain("SECOND_EXECUTION_REQUEST", "SECOND_EXECUTION_RESPONSE"));
        assertThat(saver.forThread(secondKey.threadId())).isNotEmpty().allSatisfy(write ->
                assertThat(write.stateText()).doesNotContain("FIRST_EXECUTION_ONLY_PROMPT", "FIRST_EXECUTION_ONLY_RESPONSE"));
        assertThat(saver.forThread(firstKey.threadId())).anySatisfy(write ->
                assertThat(write.stateText()).contains("FIRST_EXECUTION_ONLY_RESPONSE"));
        assertThat(saver.forThread(secondKey.threadId())).anySatisfy(write ->
                assertThat(write.stateText()).contains("SECOND_EXECUTION_RESPONSE"));
    }

    private static ExecutionKey identity() {
        return new ExecutionKey("tenant-1", "alice", 1, "session-1", "run-1", "plan-1", 1,
                "step-1", "policy-v1", "runner-v1", "topology-v1");
    }

    private static ExecutionKey changedIdentity(int field) {
        String[] values = {"tenant-1", "alice", "1", "session-1", "run-1", "plan-1", "1",
                "step-1", "policy-v1", "runner-v1", "topology-v1"};
        values[field] = field == 2 || field == 6 ? "2" : values[field] + "-other";
        return new ExecutionKey(values[0], values[1], Long.parseLong(values[2]), values[3], values[4],
                values[5], Integer.parseInt(values[6]), values[7], values[8], values[9], values[10]);
    }

    private static RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private static CompileConfig compileConfig(MemorySaver saver) {
        return CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build())
                .releaseThread(false).build();
    }

    private record SavedState(String threadId, String nodeId, String nextNodeId,
                              Set<String> keys, int serializedBytes, String stateText) {}

    /** Observes inserts and in-place updates at the time of each write, not only the final checkpoint. */
    private static final class ObservingMemorySaver extends MemorySaver {
        private final List<SavedState> writes = new ArrayList<>();

        @Override
        protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                Checkpoint checkpoint) throws Exception {
            capture(config, checkpoint);
        }

        @Override
        protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                Checkpoint checkpoint) throws Exception {
            capture(config, checkpoint);
        }

        private synchronized void capture(RunnableConfig config, Checkpoint checkpoint) throws Exception {
            byte[] serialized = AgentStateSerializerFactory.create().dataToBytes(checkpoint.getState());
            writes.add(new SavedState(config.threadId().orElse("<missing>"), checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(), Set.copyOf(checkpoint.getState().keySet()),
                    serialized.length, checkpoint.getState().toString()));
        }

        synchronized List<SavedState> writes() { return List.copyOf(writes); }

        synchronized List<SavedState> forThread(String threadId) {
            return writes.stream().filter(write -> threadId.equals(write.threadId())).toList();
        }
    }
}
