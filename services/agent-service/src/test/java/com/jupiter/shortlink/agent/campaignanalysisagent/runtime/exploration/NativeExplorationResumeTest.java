package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.ExecutionKey;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.Observation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeExplorationAdapter.RegisteredTool;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Native continuation proof only. The same trusted in-memory ledger stands in for P1 persistence. */
class NativeExplorationResumeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Limits LIMITS = new Limits(1, 4096, 1024, 16384, 8, Duration.ofSeconds(3));

    @Test
    void restoredAdapterUsesOnePendingPairAndOneTrustedReadyObservationWithoutResubmittingOrReplayingInputs() throws Exception {
        var scenario = waiting(new InspectingSaver());
        var writesWhileWaiting = List.copyOf(scenario.saver.writes);
        var waitingModel = new ScriptedExplorationChatModel();
        var beforeReady = adapter(scenario, waitingModel);
        assertThat(beforeReady.resume()).containsEntry("status", "WAITING");
        assertThat(beforeReady.resume()).containsEntry("status", "WAITING");
        assertThat(scenario.saver.writes).containsExactlyElementsOf(writesWhileWaiting);
        assertThat(waitingModel.callCount()).isZero();

        var facts = scenario.ledger.publishReady("observation-1", "artifact-1");
        var resumedModel = new ScriptedExplorationChatModel(prompt -> {
            assertCanonicalPrompt(prompt, facts);
            return ScriptedExplorationChatModel.text("Evidence has completed");
        });
        var restored = adapter(scenario, resumedModel);

        assertThat(restored.resume()).containsEntry("status", "CANDIDATE")
                .containsEntry("artifactIds", List.of("artifact-1"));
        assertThat(resumedModel.callCount()).isEqualTo(1);
        assertThat(scenario.dispatches.get()).isEqualTo(1);
        assertThat(scenario.ledger.resumeAcknowledgements()).isEqualTo(1);
        var completedWrites = List.copyOf(scenario.saver.writes);
        scenario.ledger.publishReady("observation-1", "artifact-1");
        var replayModel = new ScriptedExplorationChatModel();
        var replay = adapter(scenario, replayModel);
        assertThat(replay.resume()).containsEntry("status", "CANDIDATE");
        assertThat(replay.invoke("Caller text must not become a second input")).containsEntry("status", "CANDIDATE");
        assertThat(replayModel.callCount()).isZero();
        assertThat(scenario.saver.writes).containsExactlyElementsOf(completedWrites);
        assertThat(scenario.ledger.resumeAcknowledgements()).isEqualTo(1);
        assertThat(scenario.saver.writes).allSatisfy(write -> assertThat(write.length()).isLessThan(32 * 1024));
    }

    @ParameterizedTest(name = "trusted receipt corruption {0} fails closed")
    @ValueSource(strings = {"identity", "job", "duplicate"})
    void damagedCanonicalFactsNeverReachTheModelOrTool(String corruption) throws Exception {
        var scenario = waiting(new InspectingSaver());
        var facts = scenario.ledger.publishReady("observation-1", "artifact-1");
        var receipt = facts.readyReceipts().get(0);
        var identity = facts.identity();
        var receipts = facts.readyReceipts();
        if ("identity".equals(corruption))
            identity = new ExecutionKey("another-tenant", "alice", 1, "session", "run", "plan", 1,
                    "step", "policy-v1", "runner-v1", "topology-v1");
        else if ("job".equals(corruption))
            receipts = List.of(new ExplorationLedger.ReadyReceipt(receipt.observationId(), receipt.actionId(),
                    "different-job", receipt.artifactId()));
        else receipts = List.of(receipt, receipt);
        scenario.ledger.replaceReadyForCorruptionTest(new ExplorationLedger.ResumeFacts(identity,
                facts.originalInput(), facts.pendingCalls(), receipts));
        var writes = List.copyOf(scenario.saver.writes);
        var model = new ScriptedExplorationChatModel();

        assertThat(adapter(scenario, model).resume()).containsEntry("status", "FAILED")
                .containsEntry("reason", "RESUME_CONTEXT_INVALID");
        assertThat(model.callCount()).isZero();
        assertThat(scenario.dispatches.get()).isEqualTo(1);
        assertThat(scenario.ledger.resumeAcknowledgements()).isZero();
        assertThat(scenario.saver.writes).containsExactlyElementsOf(writes);
    }

    @ParameterizedTest(name = "native pairing corruption {0} fails closed")
    @ValueSource(strings = {"duplicate", "orphan"})
    void damagedCheckpointPairingCannotBeSilentlyRepairedByAnotherToolSubmission(String corruption) throws Exception {
        var scenario = waiting(new InspectingSaver());
        scenario.ledger.publishReady("observation-1", "artifact-1");
        var checkpoint = scenario.saver.get(config(scenario.key)).orElseThrow();
        @SuppressWarnings("unchecked")
        var messages = new ArrayList<>((List<Message>) checkpoint.getState().get("messages"));
        ToolResponseMessage pending = messages.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
        if ("duplicate".equals(corruption)) messages.add(pending);
        else messages.removeIf(message -> message instanceof AssistantMessage assistant && assistant.hasToolCalls());
        var damagedState = new LinkedHashMap<>(checkpoint.getState());
        damagedState.put("messages", messages);
        var damaged = Checkpoint.builder().id(checkpoint.getId()).state(damagedState)
                .nodeId(checkpoint.getNodeId()).nextNodeId(checkpoint.getNextNodeId()).build();
        scenario.saver.put(RunnableConfig.builder().threadId(scenario.key.threadId())
                .checkPointId(checkpoint.getId()).build(), damaged);
        var model = new ScriptedExplorationChatModel();

        assertThat(adapter(scenario, model).resume()).containsEntry("status", "FAILED")
                .containsEntry("reason", "RESUME_CONTEXT_INVALID");
        assertThat(model.callCount()).isZero();
        assertThat(scenario.dispatches.get()).isEqualTo(1);
        assertThat(scenario.ledger.resumeAcknowledgements()).isZero();
    }

    @Test
    void checkpointWriteFailureDoesNotConsumeReadyFactsAndRetryContainsOneObservation() throws Exception {
        var scenario = waiting(new InspectingSaver());
        var facts = scenario.ledger.publishReady("observation-1", "artifact-1");
        scenario.saver.failNextReadyWrite = true;
        var failedModel = new ScriptedExplorationChatModel();

        assertThatThrownBy(() -> adapter(scenario, failedModel).resume()).isInstanceOf(Exception.class);
        assertThat(failedModel.callCount()).isZero();
        assertThat(scenario.ledger.readyToResume()).contains(facts);
        assertThat(scenario.ledger.resumeAcknowledgements()).isZero();
        var retryModel = new ScriptedExplorationChatModel(prompt -> {
            assertCanonicalPrompt(prompt, facts);
            return ScriptedExplorationChatModel.text("Recovered completion");
        });

        assertThat(adapter(scenario, retryModel).resume()).containsEntry("status", "CANDIDATE");
        assertThat(retryModel.callCount()).isEqualTo(1);
        assertThat(scenario.ledger.resumeAcknowledgements()).isEqualTo(1);
        assertThat(scenario.dispatches.get()).isEqualTo(1);
    }

    @Test
    void modelCannotResubmitTheCompletedRequestWithANewCallIdOrDifferentJsonWhitespace() throws Exception {
        var scenario = waiting(new InspectingSaver());
        scenario.ledger.publishReady("observation-1", "artifact-1");
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.toolCalls(
                new AssistantMessage.ToolCall("new-model-id", "function", "query", "{ \"gid\" : \"scope\" }")));

        assertThat(adapter(scenario, model).resume()).containsEntry("status", "FAILED")
                .containsEntry("reason", "RESUME_TOOL_RESUBMISSION_REJECTED");
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(scenario.dispatches.get()).isEqualTo(1);
        assertThat(scenario.ledger.acceptedCalls()).containsExactly("original-call:query");
    }

    @Test
    void completedEarlierAssistantMayReuseTheCurrentPendingCallIdWithoutChangingItsActionBinding() throws Exception {
        var scenario = waiting(new InspectingSaver(), true);
        var facts = scenario.ledger.publishReady("observation-1", "artifact-1");
        var model = new ScriptedExplorationChatModel(prompt -> {
            assertCanonicalPrompt(prompt, facts);
            return ScriptedExplorationChatModel.text("Current job completed");
        });

        assertThat(adapter(scenario, model).resume()).containsEntry("status", "CANDIDATE")
                .containsEntry("artifactIds", List.of("artifact-old", "artifact-1"));
        assertThat(model.callCount()).isEqualTo(1);
        assertThat(scenario.dispatches.get()).isEqualTo(2);
        assertThat(scenario.ledger.resumeAcknowledgements()).isEqualTo(1);
    }

    private static void assertCanonicalPrompt(Prompt prompt, ExplorationLedger.ResumeFacts facts) {
        var messages = prompt.getInstructions();
        var assistantCalls = messages.stream().filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast).filter(AssistantMessage::hasToolCalls).toList();
        assertThat(assistantCalls).hasSize(1);
        assertThat(assistantCalls.get(0).getText()).isEqualTo("Checking authorized evidence");
        assertThat(assistantCalls.get(0).getToolCalls()).containsExactly(
                new AssistantMessage.ToolCall("original-call", "function", "query", "{\"gid\":\"scope\"}"));
        var responses = messages.stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo("original-call");
        assertThat(responses.get(0).responseData()).isEqualTo("{\"status\":\"PENDING\",\"jobId\":\"job-1\"}");
        var userMessages = messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).toList();
        assertThat(userMessages).hasSize(2);
        assertThat(userMessages.get(0).getText()).isEqualTo("Analyze the frozen scope");
        try {
            var observed = JSON.readTree(userMessages.get(1).getText());
            assertThat(observed.path("status").asText()).isEqualTo("READY");
            assertThat(observed.path("actionId").asText()).isEqualTo(facts.pendingCalls().get(0).actionId());
            assertThat(observed.path("jobId").asText()).isEqualTo("job-1");
            assertThat(observed.path("artifactId").asText()).isEqualTo("artifact-1");
            assertThat(userMessages.get(1).getMetadata()).containsEntry("source", "trusted_ledger");
        } catch (Exception invalid) { throw new AssertionError(invalid); }
    }

    private static Scenario waiting(InspectingSaver saver) throws Exception {
        return waiting(saver, false);
    }

    private static Scenario waiting(InspectingSaver saver, boolean earlierCompletedCallUsesSameId) throws Exception {
        var key = new ExecutionKey("tenant", "alice", 1, "session", "run", "plan", 1,
                "step", "policy-v1", "runner-v1", "topology-v1");
        var ledger = new ExplorationTestLedger(key);
        var dispatched = new AtomicInteger();
        var callback = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("query").description("Authorized fixture query")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"gid\":{\"type\":\"string\"}}}").build();
            }
            @Override public String call(String input) { dispatched.incrementAndGet(); return input; }
        };
        var tool = new RegisteredTool(callback, raw -> raw.contains("old-scope")
                ? Observation.ready("artifact-old") : Observation.pending("job-1"));
        var scenario = new Scenario(key, ledger, saver, dispatched, tool);
        var model = new ScriptedExplorationChatModel();
        if (earlierCompletedCallUsesSameId) model.thenReturn(ScriptedExplorationChatModel.toolCalls(
                new AssistantMessage.ToolCall("original-call", "function", "query", "{\"gid\":\"old-scope\"}")));
        model.thenReturn(ScriptedExplorationChatModel.response(
                AssistantMessage.builder().content("Checking authorized evidence").toolCalls(List.of(
                        new AssistantMessage.ToolCall("original-call", "function", "query", "{\"gid\":\"scope\"}"))).build(),
                "tool_calls"));
        assertThat(adapter(scenario, model).invoke("Analyze the frozen scope")).containsEntry("status", "WAITING");
        assertThat(model.callCount()).isEqualTo(earlierCompletedCallUsesSameId ? 2 : 1);
        return scenario;
    }

    private static NativeExplorationAdapter adapter(Scenario scenario, ScriptedExplorationChatModel model) {
        return new NativeExplorationAdapter(scenario.key, scenario.ledger, model, List.of(scenario.tool),
                scenario.saver, Runnable::run, LIMITS);
    }

    private static RunnableConfig config(ExecutionKey key) { return RunnableConfig.builder().threadId(key.threadId()).build(); }

    private record Scenario(ExecutionKey key, ExplorationTestLedger ledger, InspectingSaver saver,
                            AtomicInteger dispatches, RegisteredTool tool) {}

    private static final class InspectingSaver extends MemorySaver {
        final List<String> writes = new ArrayList<>();
        boolean failNextReadyWrite;
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values,
                Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values,
                Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        private void capture(Checkpoint checkpoint) throws Exception {
            String value = AgentStateSerializerFactory.create().objectMapper().writeValueAsString(checkpoint.getState());
            if (failNextReadyWrite && value.contains("trusted_action_observation")) {
                failNextReadyWrite = false;
                throw new IllegalStateException("Synthetic checkpoint write failure");
            }
            writes.add(value);
        }
    }
}
