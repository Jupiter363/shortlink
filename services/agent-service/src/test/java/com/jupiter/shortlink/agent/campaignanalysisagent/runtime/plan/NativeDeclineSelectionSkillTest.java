package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionCallTest.*;
import static org.junit.jupiter.api.Assertions.*;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;

/** One actual native two-turn Skill journey; only authority/job responses and the ChatModel are scripted. */
@Timeout(45)
class NativeDeclineSelectionSkillTest {
    static final String PROMPT = "Find observed declines in the authorized frozen scope";
    private static final String TOOL_CALL_ID = "native-decline-call";
    private static final NativeExplorationAdapter.Limits LIMITS = new NativeExplorationAdapter.Limits(
            0, 4096, 32768, 32768, 8, Duration.ofSeconds(10));

    @Test
    void nativeSkillResumesOnlyAfterNamedCompletionAndRetainsOnePendingPairAndOneReadyObservation() throws Exception {
        var f = new CallFixture(false);
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql"),
                new ClassPathResource("sql/migration/V20260920_13__campaign_skill_observation.sql")).execute(f.base.jdbc.getDataSource());
        assertNull(f.initialCall); assertNull(f.callSpec);
        assertEquals(0, f.count("campaign_model_response", RUN));
        assertEquals(0, f.count("campaign_exploration_call", RUN));
        var model = new SkillModel(f);
        StepPermit resumedStep = null;
        boolean originalExited = false;
        try {
            assertEquals("WAITING", nativeAdapter(f, f.step, model).invoke(PROMPT).get("status"));
            assertEquals(1, model.calls.get()); assertEquals(2, f.gateway.submits);
            CallRecord call = onlyCall(f, f.token);
            assertFalse(call.callbackActive(), "Native callback really exits after publishing the pending observation");
            assertEquals(CampaignExplorationCallStore.CallState.RETURNED, call.state());
            var waiting = f.invocations.invocation(f.token, call.spec().callId()).orElseThrow();
            assertEquals(CampaignSkillInvocationStore.State.WAITING, waiting.state());
            assertTrue(waiting.outputs().isEmpty()); assertNull(waiting.completionId());
            List<ChildRecord> originalJobs = f.children(f.token).stream().filter(c -> c.spec().mode() == ChildMode.ASYNC).toList();
            assertEquals(2, originalJobs.size());
            assertTrue(originalJobs.stream().allMatch(c -> c.state() == ChildState.WAITING && c.jobId() != null));

            // Empty native checkpoints cannot restart the model or re-enter the still-waiting Skill.
            assertEquals("WAITING", nativeAdapter(f, f.step, model).invoke(PROMPT).get("status"));
            assertEquals(1, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(0, f.gateway.reads());
            f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-skill", f.auth);
            f.steps.callbackExited(f.step); originalExited = true;
            RunToken writer = f.steps.acquireRun(f.token);
            var delegate = f.adapter();
            var targets = delegate.resultTargets(writer);
            var receiver = new StatisticsJobResultReceiver(f.runs, f.base.results, f.gateway, CLOCK, 1);
            var baseline = originalJobs.stream().filter(c -> f.period(c).equals("baseline")).findFirst().orElseThrow();
            var target = originalJobs.stream().filter(c -> f.period(c).equals("target")).findFirst().orElseThrow();
            assertEquals(StatisticsJobResultReceiver.Outcome.READY,
                    receiver.receive(writer, baseline.spec().childId(), PRINCIPAL, targets.get(baseline.spec().childId()),
                            () -> delegate.reauthorize(writer, baseline.spec().childId())).outcome());
            assertEquals(StepStatus.WAITING, f.steps.refreshWaiting(writer, STEP).status());
            assertEquals(1, model.calls.get()); assertEquals(0, f.finalCount());
            assertEquals(StatisticsJobResultReceiver.Outcome.READY,
                    receiver.receive(writer, target.spec().childId(), PRINCIPAL, targets.get(target.spec().childId()),
                            () -> delegate.reauthorize(writer, target.spec().childId())).outcome());
            assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, STEP).status());
            resumedStep = f.steps.beginStep(writer, STEP);

            // Both jobs are READY, but only the server-owned Skill continuation can satisfy its output contract.
            assertEquals("WAITING", nativeAdapter(f, resumedStep, model).invoke(PROMPT).get("status"));
            assertEquals(1, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(0, f.finalCount());
            var continuation = delegate.beginContinuation(resumedStep, call.spec().callId(), waiting.rowVersion());
            InvocationRecord completion;
            try { completion = delegate.execute(continuation); }
            finally { f.calls.callbackExited(continuation); }
            assertEquals(CampaignSkillInvocationStore.State.COMPLETED, completion.state());
            assertEquals(OUTPUTS, completion.outputs().keySet()); assertNotNull(completion.completionId());
            assertEquals(2, f.calls.call(writer, continuation.callId()).orElseThrow().attemptVersion());
            assertFalse(f.calls.call(writer, continuation.callId()).orElseThrow().callbackActive());
            assertEquals(2, f.finalCount()); assertEquals(1, model.calls.get());
            model.completion = completion; model.call = call;

            var answer = nativeAdapter(f, resumedStep, model).invoke(PROMPT);
            assertEquals("CANDIDATE", answer.get("status")); assertNotEquals("SUCCEEDED", answer.get("status"));
            Set<String> outputIds = new HashSet<>(); completion.outputs().values().forEach(ref -> outputIds.add(ref.artifactId()));
            assertEquals(outputIds, new HashSet<>((List<?>) answer.get("artifactIds")));
            assertEquals(2, model.calls.get()); assertEquals(2, f.count("campaign_model_response", RUN));
            assertEquals(1, f.count("campaign_exploration_call", RUN));
            assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads); assertEquals(0, f.gateway.recoveries);
            ChildRecord model2 = f.children(writer).stream().filter(c -> c.spec().mode() == ChildMode.MODEL
                    && c.spec().modelInvocation().turnIndex() == 2).findFirst().orElseThrow();
            var visible = model2.spec().modelInvocation().inputs();
            for (ArtifactRef ref : completion.outputs().values()) {
                ArtifactMetadata actual = f.runs.inspectArtifact(OWNER, ref.artifactId(), f.auth);
                assertEquals(ref, actual.ref());
                assertTrue(visible.values().contains(actual), "Every named output must be actual model-visible evidence");
            }
            assertTrue(visible.values().stream().anyMatch(metadata -> metadata.ref().artifactId().equals(f.scopeArtifact)));
            for (ChildRecord original : originalJobs) {
                ChildRecord ready = f.runs.child(writer, original.spec().childId()).orElseThrow();
                assertEquals(original.spec(), ready.spec()); assertEquals(original.jobId(), ready.jobId());
                assertEquals(ChildState.READY, ready.state());
            }
            assertTrue(f.children(writer).stream().filter(c -> c.spec().mode() == ChildMode.LOCAL)
                    .allMatch(c -> c.attemptVersion() == 1 && c.state() == ChildState.READY));
            assertEquals("CANDIDATE", nativeAdapter(f, resumedStep, model).invoke(PROMPT).get("status"));
            assertEquals(2, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);

            f.allowed.set(false);
            try {
                var denied = nativeAdapter(f, resumedStep, model).invoke(PROMPT);
                assertEquals("BLOCKED", denied.get("status"));
            } catch (SecurityException | IllegalStateException denied) { /* Current authority must prevent historical evidence consumption. */ }
            assertEquals(2, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
            f.allowed.set(true);
            assertEquals("CANDIDATE", nativeAdapter(f, resumedStep, model).invoke(PROMPT).get("status"));
            assertEquals(model2, f.runs.child(writer, model2.spec().childId()).orElseThrow());
            assertEquals(completion, f.invocations.readCompletion(writer, call.spec().callId(), f.auth));
            assertEquals(2, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
        } finally {
            f.allowed.set(true);
            if (resumedStep != null) f.steps.callbackExited(resumedStep);
            if (!originalExited) f.steps.callbackExited(f.step);
        }
        for (String table : List.of("campaign_child_ledger", "campaign_step_ledger", "campaign_exploration_call"))
            assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE callback_active=TRUE", Integer.class));
    }

    static NativeExplorationAdapter nativeAdapter(CallFixture f, StepPermit step, ChatModel model) throws Exception {
        return nativeAdapter(f, step, model, null);
    }

    static NativeExplorationAdapter nativeAdapter(CallFixture f, StepPermit step, ChatModel model,
                                                  CampaignExplorationCandidateStore candidates) throws Exception {
        return nativeAdapter(f, nativeLedger(f, step, candidates, model), model);
    }

    static JdbcExplorationLedger nativeLedger(CallFixture f, StepPermit step,
                                               CampaignExplorationCandidateStore candidates, ChatModel model) throws Exception {
        var tool = DeclineSelectionExplorationSkill.definition();
        var configuration = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIGURATION, null,
                List.of(new ModelInvocationRegistry.ToolDefinition(tool.name(), tool.description(), JSON.readTree(tool.inputSchema()))),
                Map.of("scope", f.runs.inspectArtifact(OWNER, f.scopeArtifact, f.auth)), Instant.ofEpochMilli(EXPIRY),
                NativeExplorationAdapter.generationOptions(model.getDefaultOptions()));
        return candidates == null ? new JdbcExplorationLedger(f.base.jdbc, f.base.transactions, CLOCK,
                new JdbcCampaignRunStore(f.base.jdbc, f.base.transactions, CLOCK),
                new JdbcCampaignStepStore(f.base.jdbc, f.base.transactions, CLOCK),
                new JdbcCampaignExplorationCallStore(f.base.jdbc, f.base.transactions, CLOCK), step, f.models, configuration,
                Map.of(FrozenDeclineSelection.REF.name(), FrozenDeclineSelection.REF), f.auth, ExplorationBudgetPolicy.defaults(),
                new DeclineSelectionArtifactProjection(f.runs,
                        new JdbcCampaignDeclineSelectionStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs)), f.invocations)
                : new JdbcExplorationLedger(f.base.jdbc, f.base.transactions, CLOCK,
                        new JdbcCampaignRunStore(f.base.jdbc, f.base.transactions, CLOCK),
                        new JdbcCampaignStepStore(f.base.jdbc, f.base.transactions, CLOCK),
                        new JdbcCampaignExplorationCallStore(f.base.jdbc, f.base.transactions, CLOCK), step, f.models, configuration,
                        Map.of(FrozenDeclineSelection.REF.name(), FrozenDeclineSelection.REF), f.auth, ExplorationBudgetPolicy.defaults(),
                        new DeclineSelectionArtifactProjection(f.runs,
                        new JdbcCampaignDeclineSelectionStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs)), f.invocations, candidates);
    }

    static NativeExplorationAdapter nativeAdapter(CallFixture f, JdbcExplorationLedger ledger, ChatModel model) {
        var wrapper = new DeclineSelectionExplorationSkill(f.adapter());
        return new NativeExplorationAdapter(ledger.identity(), ledger, model, List.of(wrapper.registration()),
                new MemorySaver(), Runnable::run, LIMITS, ledger);
    }

    static CallRecord onlyCall(CallFixture f, RunToken token) {
        List<String> ids = f.base.jdbc.query("SELECT call_id FROM campaign_exploration_call WHERE run_id=? AND revision=?",
                (rs, row) -> rs.getString(1), token.definition().runId(), token.definition().revision());
        assertEquals(1, ids.size()); return f.calls.call(token, ids.get(0)).orElseThrow();
    }

    static final class SkillModel implements ChatModel {
        final CallFixture f;
        final AtomicInteger calls = new AtomicInteger();
        volatile InvocationRecord completion;
        volatile CallRecord call;
        final java.util.function.Function<InvocationRecord, String> terminalText;
        final java.util.function.Consumer<Prompt> firstPrompt;
        SkillModel(CallFixture fixture) {
            this(fixture, ignored -> "The approved method produced selected members and their evidence; this remains a candidate analysis.");
        }
        SkillModel(CallFixture fixture, java.util.function.Function<InvocationRecord, String> terminalText) {
            this(fixture, terminalText, ignored -> {});
        }
        SkillModel(CallFixture fixture, java.util.function.Function<InvocationRecord, String> terminalText,
                   java.util.function.Consumer<Prompt> firstPrompt) {
            f = fixture; this.terminalText = terminalText; this.firstPrompt = firstPrompt;
        }
        @Override public ChatResponse call(Prompt prompt) {
            int turn = calls.incrementAndGet();
            if (turn == 1) {
                firstPrompt.accept(prompt);
                return response(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall(TOOL_CALL_ID, "function", FrozenDeclineSelection.REF.name(), f.arguments))).build());
            }
            assertEquals(2, turn, "A completed logical model slot must never invoke the model again");
            assertNotNull(completion); assertNotNull(call);
            var pending = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .filter(receipt -> TOOL_CALL_ID.equals(receipt.id()) && FrozenDeclineSelection.REF.name().equals(receipt.name())).toList();
            assertEquals(1, pending.size()); JsonNode pendingData = tree(pending.get(0).responseData());
            assertEquals("PENDING", pendingData.path("status").asText());
            assertEquals(call.spec().callId(), pendingData.path("skillCallId").asText());
            assertFalse(pendingData.has("jobId"), "A multi-job Skill is not one invented statistics job");
            var ready = prompt.getInstructions().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                    .map(UserMessage::getText).filter(text -> text.startsWith("{")).map(NativeDeclineSelectionSkillTest::tree)
                    .filter(value -> "trusted_action_observation".equals(value.path("type").asText())).toList();
            assertEquals(1, ready.size()); JsonNode observation = ready.get(0);
            assertEquals("READY", observation.path("status").asText());
            assertEquals(call.spec().callId(), observation.path("skillCallId").asText());
            assertEquals(call.spec().actionId(), observation.path("actionId").asText());
            assertEquals(completion.completionId(), observation.path("completionId").asText());
            assertFalse(observation.path("observationId").asText().isBlank());
            assertFalse(observation.has("artifactId"), "Named Skill outputs are not flattened to a fabricated single Artifact");
            JsonNode outputs = observation.path("outputs");
            Set<String> names = new HashSet<>(); outputs.fieldNames().forEachRemaining(names::add); assertEquals(OUTPUTS, names);
            completion.outputs().forEach((name, ref) -> {
                JsonNode actual = outputs.get(name);
                assertEquals(ref.artifactId(), actual.path("artifactId").asText());
                assertEquals(ref.payloadHash(), actual.path("payloadHash").asText());
                assertEquals(ref.type(), actual.path("type").asText());
                assertEquals(ref.schemaVersion(), actual.path("schemaVersion").asText());
                assertEquals(ref.scopeRef(), actual.path("scopeRef").asText());
                assertEquals(ref.periodsRef(), actual.path("periodsRef").asText());
            });
            JsonNode selection = observation.path("evidence").path("selectedEntities");
            JsonNode evidence = observation.path("evidence").path("selectionEvidence");
            for (var entry : Map.of("selectedEntities", selection, "selectionEvidence", evidence).entrySet()) {
                JsonNode projection = entry.getValue();
                assertEquals("decline-selection-artifact-projection/v1", projection.path("schemaVersion").asText());
                assertEquals(completion.outputs().get(entry.getKey()).artifactId(), projection.path("artifactId").asText());
                assertEquals(completion.outputs().get(entry.getKey()).payloadHash(), projection.path("payloadHash").asText());
                assertEquals("OBSERVED_ONLY", projection.path("quality").path("interpretation").asText());
                assertEquals("UNVERIFIED", projection.path("quality").path("collectionCompleteness").asText());
                assertEquals(2, projection.path("selection").path("candidateCount").asInt());
                assertEquals(1, projection.path("selection").path("selectedCount").asInt());
                assertTrue(projection.path("selection").path("selectionComplete").asBoolean());
                assertTrue(projection.path("preview").path("previewComplete").asBoolean());
                for (JsonNode row : projection.path("preview").path("rows")) {
                    assertEquals("OBSERVED_ONLY", row.path("explanation").asText());
                    assertTrue(row.path("reasonCodes").toString().contains("BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED"));
                    assertTrue(row.path("reasonCodes").toString().contains("TARGET_COLLECTION_COMPLETENESS_UNVERIFIED"));
                }
            }
            assertEquals("DELTA_ASC_LINK_ID_ASC", selection.path("preview").path("selection").asText());
            assertEquals(1, selection.path("preview").path("rows").size());
            JsonNode declined = selection.path("preview").path("rows").get(0);
            assertEquals(1, declined.path("linkId").asLong()); assertEquals(10, declined.path("baseline").asLong());
            assertEquals(3, declined.path("target").asLong()); assertEquals(-7, declined.path("delta").asLong());
            assertEquals("LINK_ID_ASC", evidence.path("preview").path("selection").asText());
            assertEquals(2, evidence.path("preview").path("rows").size());
            JsonNode increased = evidence.path("preview").path("rows").get(1);
            assertEquals(2, increased.path("linkId").asLong()); assertEquals(2, increased.path("delta").asLong());
            return response(new AssistantMessage(terminalText.apply(completion)));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("scripted-model").build(); }
        private static ChatResponse response(AssistantMessage message) { return new ChatResponse(List.of(new Generation(message))); }
    }

    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(invalid); }
    }
}
