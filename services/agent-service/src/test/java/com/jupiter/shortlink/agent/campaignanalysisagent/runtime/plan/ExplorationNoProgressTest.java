package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionCallTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.InvocationRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;

/** Exact read-only proposal deduplication over actual native MODEL/CALL/Skill and paged job receipts. */
@Timeout(60)
class ExplorationNoProgressTest {
    private static final String INPUT = NativeDeclineSelectionSkillTest.PROMPT;
    private static final String FIRST = "first-selection", SECOND = "second-selection";
    private static final String STOP = "EXPLORATION_NO_PROGRESS";
    private static final ExplorationRepeatPolicy REPEAT = new ExplorationRepeatPolicy("readonly-repeat/v1", Set.of(FrozenDeclineSelection.REF));

    @Test
    void reorderedExactProposalStopsBeforeAnotherCallAndPreservesOriginalEvidenceAcrossReopen() throws Exception {
        var f = new Flow();
        String reordered = JSON.writeValueAsString(reverse(JSON.readTree(f.base.arguments)));
        assertNotEquals(f.base.arguments, reordered);
        assertEquals(JSON.readTree(f.base.arguments), JSON.readTree(reordered));
        var model = new SequenceModel(prompt -> tool(FIRST, f.base.arguments), prompt -> {
            f.assertReadyHistory(prompt, FIRST, f.completions.get(0));
            return tool(SECOND, reordered);
        });
        try {
            assertEquals("WAITING", f.session(model).adapter().invoke(INPUT).get("status"));
            CallRecord source = f.pendingCall();
            InvocationRecord completion = f.completePending(model);
            Map<String, ArtifactMetadata> original = f.metadata(completion);
            var stoppedSession = f.session(model);
            var stopped = stoppedSession.adapter().invoke(INPUT);
            assertEquals("BLOCKED", stopped.get("status")); assertEquals(STOP, stopped.get("reason"));
            assertEquals(2, model.count.get()); assertEquals(1, f.base.count("campaign_exploration_call", RUN));
            assertEquals(2, f.base.gateway.submits); assertEquals(2, f.base.gateway.pageReads);
            assertEquals(0, f.base.gateway.recoveries); assertEquals(1, f.stopCount());
            assertEquals(2, f.base.count("campaign_model_response", RUN));
            assertEquals(2, f.proposals());
            assertEquals(1, f.base.base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_proposal WHERE run_id=? AND decision='STOP'",
                    Integer.class, RUN));
            var secondModel = f.modelChild(2);
            assertTrue(secondModel.spec().modelInvocation().inputs().values().containsAll(original.values()));
            assertEquals(original, f.metadata(completion));
            assertEquals(2, f.base.finalCount());
            assertStoppedPair(stoppedSession.ledger(), source, completion);
            assertEquals(1, stoppedSession.saver().responses(SECOND).size(), "The actual native checkpoint closes the rejected tool call exactly once");
            JsonNode nativeReceipt = JSON.readTree(stoppedSession.saver().responses(SECOND).get(0).responseData());
            assertEquals(STOP, nativeReceipt.path("code").asText()); assertFalse(nativeReceipt.path("executed").asBoolean(true));

            var fresh = f.session(model); // Same still-active permit, empty saver, fresh ledger; no re-arm of a blocked Step.
            assertEquals("BLOCKED", fresh.adapter().invoke(INPUT).get("status"));
            assertStoppedPair(fresh.ledger(), source, completion);
            assertEquals(2, model.count.get()); assertEquals(2, f.base.gateway.submits); assertEquals(2, f.base.gateway.pageReads);
            assertEquals(1, f.stopCount()); assertEquals(original, f.metadata(completion));
            f.base.allowed.set(false);
            try {
                assertThrows(SecurityException.class, fresh.ledger()::canonicalMessages);
                assertThrows(SecurityException.class, () -> f.base.invocations.readCompletion(f.token, source.spec().callId(), f.base.auth));
            } finally { f.base.allowed.set(true); }
            assertEquals(2, model.count.get()); assertEquals(2, f.base.gateway.submits);

            f.base.steps.settle(f.permit, StepStatus.BLOCKED, Map.of(), STOP, f.base.auth);
            f.exit();
            RunToken writer = f.base.steps.acquireRun(f.token);
            assertEquals(StepStatus.BLOCKED, f.base.steps.step(writer, STEP).orElseThrow().status());
            assertEquals(STOP, f.base.steps.step(writer, STEP).orElseThrow().reason());
            assertEquals(StepStatus.BLOCKED, f.base.steps.refreshWaiting(writer, STEP).status());
            assertEquals(1, f.stopCount()); assertEquals(2, f.proposals());
            assertEquals(completion, f.base.invocations.readCompletion(writer, source.spec().callId(), f.base.auth));
            assertEquals(original, f.metadata(completion));
            assertEquals(2, model.count.get()); assertEquals(2, f.base.gateway.submits);
        } finally { f.close(); }
    }

    @Test
    void changedApprovedMetricCreatesANewCallWhileItsOriginalWaitingJobsResumeWithoutFalseProgressStop() throws Exception {
        var f = new Flow();
        var changed = JSON.readTree(f.base.arguments);
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("parameters")).put("metric", "UV");
        String uvArguments = JSON.writeValueAsString(reverse(changed));
        var model = new SequenceModel(prompt -> tool(FIRST, f.base.arguments), prompt -> {
            f.assertReadyHistory(prompt, FIRST, f.completions.get(0));
            return tool(SECOND, uvArguments);
        }, prompt -> {
            f.assertReadyHistory(prompt, FIRST, f.completions.get(0));
            f.assertReadyHistory(prompt, SECOND, f.completions.get(1));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("The two requested observed metrics were evaluated."))));
        });
        try {
            assertEquals("WAITING", f.session(model).adapter().invoke(INPUT).get("status"));
            f.completePending(model);
            assertEquals("WAITING", f.session(model).adapter().invoke(INPUT).get("status"));
            assertEquals(2, model.count.get()); assertEquals(4, f.base.gateway.submits);
            assertEquals(2, f.base.count("campaign_exploration_call", RUN)); assertEquals(0, f.stopCount());
            CallRecord uvCall = f.pendingCall();
            assertEquals("UV", JSON.readTree(uvCall.spec().arguments()).path("parameters").path("metric").asText());
            assertEquals("WAITING", f.session(model).adapter().invoke(INPUT).get("status"));
            assertEquals(2, model.count.get()); assertEquals(4, f.base.gateway.submits);
            f.completePending(model);
            var finalSession = f.session(model);
            assertEquals("CANDIDATE", finalSession.adapter().invoke(INPUT).get("status"));
            assertEquals(3, model.count.get()); assertEquals(4, f.base.gateway.submits); assertEquals(4, f.base.gateway.pageReads);
            assertEquals(0, f.base.gateway.recoveries); assertEquals(0, f.stopCount());
            assertEquals(2, f.proposals()); assertEquals(4, f.base.finalCount());
            assertEquals(2, f.base.base.jdbc.queryForObject("SELECT COUNT(DISTINCT fingerprint) FROM campaign_exploration_proposal WHERE run_id=? AND decision='ADMITTED'",
                    Integer.class, RUN));
            assertEquals(2, f.base.calls.call(f.token, uvCall.spec().callId()).orElseThrow().attemptVersion());
            assertTrue(f.base.children(f.token).stream().filter(child -> child.spec().mode() == ChildMode.ASYNC)
                    .allMatch(child -> child.state() == ChildState.READY && child.jobId() != null));
            assertTrue(f.base.children(f.token).stream().filter(child -> child.spec().mode() == ChildMode.LOCAL)
                    .allMatch(child -> child.state() == ChildState.READY && child.attemptVersion() == 1));
            assertEquals("CANDIDATE", f.session(model).adapter().invoke(INPUT).get("status"));
            assertEquals(3, model.count.get()); assertEquals(4, f.base.gateway.submits); assertEquals(4, f.base.gateway.pageReads);
        } finally { f.close(); }
    }

    private static void assertStoppedPair(JdbcExplorationLedger ledger, CallRecord source, InvocationRecord completion) throws Exception {
        var messages = ledger.canonicalMessages();
        var responses = messages.stream().filter(message -> "tool".equals(message.role()) && SECOND.equals(message.toolCallId())).toList();
        assertEquals(1, responses.size());
        assertEquals(FrozenDeclineSelection.REF.name(), responses.get(0).toolName());
        JsonNode rejected = JSON.readTree(responses.get(0).text());
        assertEquals(STOP, rejected.path("code").asText()); assertFalse(rejected.path("executed").asBoolean(true));
        assertEquals(source.spec().callId(), rejected.path("sourceCallId").asText());
        completion.outputs().forEach((name, ref) -> {
            assertEquals(ref.artifactId(), rejected.path("outputs").path(name).path("artifactId").asText());
            assertEquals(ref.payloadHash(), rejected.path("outputs").path(name).path("payloadHash").asText());
        });
        assertEquals(1, messages.stream().filter(message -> "assistant".equals(message.role()))
                .flatMap(message -> message.toolCalls().stream()).filter(call -> SECOND.equals(call.id())).count());
        assertEquals(responses.get(0), ledger.terminalCallResponse().orElseThrow());
    }

    private record Session(JdbcExplorationLedger ledger, NativeExplorationAdapter adapter, CapturingSaver saver) {}

    private static final class Flow implements AutoCloseable {
        final CallFixture base = new CallFixture(false);
        final List<InvocationRecord> completions = new ArrayList<>();
        RunToken token = base.token;
        StepPermit permit = base.step;
        Flow() throws Exception {
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql"),
                    new ClassPathResource("sql/migration/V20260920_13__campaign_skill_observation.sql"),
                    new ClassPathResource("sql/migration/V20260920_15__campaign_exploration_progress.sql")).execute(base.base.jdbc.getDataSource());
        }
        Session session(ChatModel model) throws Exception {
            var tool = DeclineSelectionExplorationSkill.definition();
            var configuration = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIGURATION, null,
                    List.of(new ModelInvocationRegistry.ToolDefinition(tool.name(), tool.description(), JSON.readTree(tool.inputSchema()))),
                    Map.of("scope", base.runs.inspectArtifact(OWNER, base.scopeArtifact, base.auth)), Instant.ofEpochMilli(EXPIRY));
            var ledger = new JdbcExplorationLedger(base.base.jdbc, base.base.transactions, CLOCK, base.runs, base.steps, base.calls,
                    permit, base.models, configuration, Map.of(FrozenDeclineSelection.REF.name(), FrozenDeclineSelection.REF), base.auth,
                    ExplorationBudgetPolicy.defaults(), new DeclineSelectionArtifactProjection(base.runs,
                            new JdbcCampaignDeclineSelectionStore(base.base.jdbc, base.base.transactions, CLOCK, base.runs)), base.invocations, null, REPEAT);
            var saver = new CapturingSaver();
            var adapter = new NativeExplorationAdapter(ledger.identity(), ledger, model,
                    List.of(new DeclineSelectionExplorationSkill(base.adapter()).registration()), saver, Runnable::run,
                    new NativeExplorationAdapter.Limits(0, 4096, 32768, 32768, 8, Duration.ofSeconds(10)), ledger);
            return new Session(ledger, adapter, saver);
        }
        CallRecord pendingCall() {
            var ids = base.base.jdbc.query("SELECT call_id FROM campaign_skill_invocation WHERE run_id=? AND invocation_state='WAITING'",
                    (rs, row) -> rs.getString(1), RUN);
            assertEquals(1, ids.size()); return base.calls.call(token, ids.get(0)).orElseThrow();
        }
        InvocationRecord completePending(ChatModel model) throws Exception {
            CallRecord call = pendingCall();
            InvocationRecord waiting = base.invocations.invocation(token, call.spec().callId()).orElseThrow();
            var original = base.children(token).stream().filter(child -> child.spec().actionId().equals(call.spec().actionId())
                    && child.spec().mode() == ChildMode.ASYNC).toList();
            assertEquals(2, original.size()); assertTrue(original.stream().allMatch(child -> child.state() == ChildState.WAITING));
            base.steps.settle(permit, StepStatus.WAITING, Map.of(), "awaiting-skill", base.auth);
            exit(); token = base.steps.acquireRun(token);
            var delegate = base.adapter(); var targets = delegate.resultTargets(token);
            var receiver = new StatisticsJobResultReceiver(base.runs, base.base.results, base.gateway, CLOCK, 1);
            for (ChildRecord child : original) {
                var received = receiver.receive(token, child.spec().childId(), PRINCIPAL, targets.get(child.spec().childId()),
                        () -> delegate.reauthorize(token, child.spec().childId()));
                assertEquals(StatisticsJobResultReceiver.Outcome.READY, received.outcome(), received.code());
                var actual = base.runs.child(token, child.spec().childId()).orElseThrow();
                assertEquals(child.spec(), actual.spec()); assertEquals(child.jobId(), actual.jobId());
            }
            assertEquals(StepStatus.READY, base.steps.refreshWaiting(token, STEP).status());
            permit = base.steps.beginStep(token, STEP);
            assertEquals("WAITING", session(model).adapter().invoke(INPUT).get("status"), "Job receipt alone must not invent Skill completion");
            InvocationRecord completion = delegate.continueInvocation(permit, call.spec().callId(), waiting.rowVersion());
            assertEquals(CampaignSkillInvocationStore.State.COMPLETED, completion.state());
            assertFalse(base.calls.call(token, call.spec().callId()).orElseThrow().callbackActive());
            completions.add(completion); return completion;
        }
        void assertReadyHistory(Prompt prompt, String toolId, InvocationRecord completion) {
            var responses = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .filter(response -> toolId.equals(response.id())).toList();
            assertEquals(1, responses.size()); assertTrue(responses.get(0).responseData().contains("PENDING"));
            String history = prompt.getInstructions().toString();
            assertTrue(history.contains(completion.completionId()));
            completion.outputs().values().forEach(ref -> assertTrue(history.contains(ref.artifactId())));
        }
        Map<String, ArtifactMetadata> metadata(InvocationRecord completion) {
            Map<String, ArtifactMetadata> result = new TreeMap<>();
            completion.outputs().forEach((name, ref) -> result.put(name, base.runs.inspectArtifact(OWNER, ref.artifactId(), base.auth)));
            return result;
        }
        ChildRecord modelChild(long turn) { return base.children(token).stream().filter(child -> child.spec().mode() == ChildMode.MODEL
                && child.spec().modelInvocation().turnIndex() == turn).findFirst().orElseThrow(); }
        long stopCount() { return base.base.jdbc.queryForObject("SELECT stop_events FROM campaign_exploration_progress WHERE run_id=?", Long.class, RUN); }
        int proposals() { return base.count("campaign_exploration_proposal", RUN); }
        void exit() { if (permit != null) { base.steps.callbackExited(permit); permit = null; } }
        public void close() {
            base.allowed.set(true); exit();
            for (String table : List.of("campaign_child_ledger", "campaign_step_ledger", "campaign_exploration_call"))
                assertEquals(0, base.base.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static final class SequenceModel implements ChatModel {
        final List<Function<Prompt, ChatResponse>> turns;
        final AtomicInteger count = new AtomicInteger();
        @SafeVarargs SequenceModel(Function<Prompt, ChatResponse>... turns) { this.turns = List.of(turns); }
        @Override public ChatResponse call(Prompt prompt) {
            int index = count.getAndIncrement(); assertTrue(index < turns.size(), "No repeated model slot may call the provider");
            return turns.get(index).apply(prompt);
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("scripted-model").build(); }
    }
    private static ChatResponse tool(String id, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(id, "function", FrozenDeclineSelection.REF.name(), arguments))).build())));
    }
    private static Object reverse(JsonNode node) {
        if (node.isObject()) {
            var keys = new ArrayList<String>(); node.fieldNames().forEachRemaining(keys::add); keys.sort(Comparator.reverseOrder());
            Map<String, Object> result = new LinkedHashMap<>(); keys.forEach(key -> result.put(key, reverse(node.get(key)))); return result;
        }
        if (node.isArray()) { List<Object> values = new ArrayList<>(); node.forEach(value -> values.add(reverse(value))); return values; }
        return JSON.convertValue(node, Object.class);
    }
    private static final class CapturingSaver extends MemorySaver {
        List<?> messages = List.of();
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) { capture(checkpoint); }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) { capture(checkpoint); }
        private void capture(Checkpoint checkpoint) { if (checkpoint.getState().get("messages") instanceof List<?> list) messages = List.copyOf(list); }
        List<ToolResponseMessage.ToolResponse> responses(String id) {
            return messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream()).filter(response -> id.equals(response.id())).toList();
        }
    }
}
