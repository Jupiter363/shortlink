package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Contract;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual native agent, durable exploration/model/CALL/child stores and H2; no P0 ledger substitute. */
@Timeout(30)
class NativeDurableExplorationLedgerTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "Analyze the authorized evidence", CONFIG = "d".repeat(64);
    private static final String RAW = "BUSINESS_PAYLOAD_STAYS_IN_ARTIFACT";
    private static final String PAYLOAD = "{\"pv\":13,\"collectionQuality\":\"UNKNOWN\",\"detail\":\"" + RAW + "\"}";
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
    private static final String DEPENDENCY_SCHEMA = "{\"type\":\"object\",\"properties\":{\"artifactId\":{\"type\":\"string\"}},\"required\":[\"artifactId\"]}";
    private static final NativeExplorationAdapter.Limits LIMITS = new NativeExplorationAdapter.Limits(0, 4096, 4096, 32768, 8, Duration.ofSeconds(5));

    @Test
    void serverGenerationOptionsAreFrozenAcrossDurableToolTurnsAndConfigurationChangesCannotResume() throws Exception {
        var defaults = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                .model("approved-model").maxTokens(4096).temperature(0.2).build();
        var generation = NativeExplorationAdapter.generationOptions(defaults);
        var f = new Fixture(false, generation);
        var scripted = new ScriptedExplorationChatModel(
                prompt -> toolCall("approved-call", "read_first", "{}"),
                prompt -> ScriptedExplorationChatModel.text("Evidence received."));
        var model = new org.springframework.ai.chat.model.ChatModel() {
            @Override public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() { return defaults; }
            @Override public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                assertEquals("approved-model", prompt.getOptions().getModel());
                assertEquals(4096, prompt.getOptions().getMaxTokens());
                assertEquals(0.2, prompt.getOptions().getTemperature());
                return scripted.call(prompt);
            }
        };
        try {
            assertEquals("CANDIDATE", f.adapter(f.ledger(f.token, f.step), model, new InspectingSaver(f, false))
                    .invoke(PROMPT).get("status"));
            assertEquals(2, scripted.callCount()); assertEquals(1, f.firstCalls.get());
            var envelopes = f.jdbc.queryForList("SELECT model_invocation_json FROM campaign_child_ledger WHERE child_mode='MODEL'", String.class);
            assertEquals(2, envelopes.size());
            for (String envelope : envelopes) {
                var frozenRequest = ModelInvocationRegistry.decodeRequest(tree(envelope).path("requestJson").asText(),
                        ModelInvocationRegistry.Limits.defaults());
                assertEquals(generation, frozenRequest.generationOptions());
            }
            var original = f.configuration;
            var changed = new JdbcExplorationLedger.ModelConfiguration(original.modelRef(), original.modelVersion(),
                    original.configurationHash(), original.systemPrompt(), original.tools(), original.inputs(), original.expiresAt(),
                    NativeExplorationAdapter.generationOptions(org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                            .model("changed-model").maxTokens(4096).temperature(0.2).build()));
            var failure = assertThrows(IllegalStateException.class, () -> new JdbcExplorationLedger(f.jdbc, f.transactions,
                    CLOCK, f.runs, f.steps, f.calls, f.step, f.models, changed, f.executors, f.authorizer));
            assertEquals("EXPLORATION_CONFIGURATION_CHANGED", failure.getMessage());
            assertEquals(2, scripted.callCount()); assertEquals(1, f.firstCalls.get());
        } finally { f.steps.callbackExited(f.step); }
        f.exited();
    }

    @Test
    void emptyCheckpointRecoveryReusesCommittedModelTurnAndCompletedToolBeforeContinuingDependentAnalysis() throws Exception {
        var f = new Fixture(false);
        var model = new ScriptedExplorationChatModel(
                prompt -> toolCall("first-call", "read_first", "{}"),
                prompt -> {
                    assertToolReceipt(prompt, "first-call", "read_first", "artifact-first", 1);
                    return toolCall("second-call", "read_second", "{\"artifactId\":\"artifact-first\"}");
                },
                prompt -> {
                    assertToolReceipt(prompt, "first-call", "read_first", "artifact-first", 1);
                    assertToolReceipt(prompt, "second-call", "read_second", "artifact-second", 1);
                    return ScriptedExplorationChatModel.text("Candidate only. artifactId=untrusted-text-artifact");
                });
        var firstSaver = new InspectingSaver(f, true);
        try {
            var original = f.ledger(f.token, f.step);
            assertThrows(Exception.class, () -> f.adapter(original, model, firstSaver).invoke(PROMPT));
            assertTrue(firstSaver.failed.get(), "Native checkpoint failure must happen after MODEL turn 2 commits");
            assertEquals(2, model.callCount()); assertEquals(2, f.responses());
            assertEquals(1, f.firstCalls.get()); assertEquals(0, f.secondCalls.get());
            assertEquals(0, f.activeChildren()); assertEquals(0, f.activeCalls());
            ChildRecord completedFirst = f.runs.child(f.token, "child-first").orElseThrow();
            assertEquals(ChildState.READY, completedFirst.state()); assertEquals(1, completedFirst.attemptVersion());

            var restored = f.ledger(f.token, f.step); // New stores/ledger and an intentionally empty saver.
            var emptySaver = new InspectingSaver(f, false);
            var adapter = f.adapter(restored, model, emptySaver);
            Map<String, Object> result = adapter.invoke(PROMPT);
            assertEquals("CANDIDATE", result.get("status"));
            assertEquals(Set.of("artifact-first", "artifact-second"), new HashSet<>((List<?>) result.get("artifactIds")));
            assertNotEquals("SUCCEEDED", result.get("status"));
            assertEquals(3, model.callCount()); model.assertExhausted();
            assertEquals(3, f.responses()); assertEquals(3, f.modelChildren()); assertEquals(2, f.callCount());
            assertEquals(1, f.firstCalls.get()); assertEquals(1, f.secondCalls.get());
            assertEquals(completedFirst, f.runs.child(f.token, "child-first").orElseThrow());
            assertEquals(1, f.runs.child(f.token, "child-second").orElseThrow().attemptVersion());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE attempt_version>1", Integer.class));
            assertEquals(PAYLOAD, f.runs.readArtifact(OWNER, "artifact-first", f.authorizer).payloadJson());
            assertEquals(PAYLOAD, f.runs.readArtifact(OWNER, "artifact-second", f.authorizer).payloadJson());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE artifact_id='untrusted-text-artifact'", Integer.class));
            assertSafe(firstSaver, emptySaver);
            assertEquals("CANDIDATE", adapter.invoke(PROMPT).get("status"));
            assertEquals(3, model.callCount()); assertEquals(1, f.firstCalls.get()); assertEquals(1, f.secondCalls.get());
        } finally { f.steps.callbackExited(f.step); }
        f.exited();
    }

    @Test
    void newWriterAndStepResumeOriginalAsyncJobWithOnePendingPairAndOneAuthorizedReadyObservation() throws Exception {
        var f = new Fixture(true);
        var model = new ScriptedExplorationChatModel(
                prompt -> toolCall("async-call", "submit_async", "{}"),
                prompt -> {
                    assertToolReceipt(prompt, "async-call", "submit_async", "job-stable", 1);
                    assertEquals(1, occurrences(prompt.getInstructions().toString(), "trusted_action_observation"));
                    assertTrue(prompt.getInstructions().toString().contains("artifact-async"));
                    assertFalse(prompt.getInstructions().toString().contains(RAW));
                    return ScriptedExplorationChatModel.text("The job is ready; this remains a candidate answer.");
                });
        var originalSaver = new InspectingSaver(f, false);
        StepPermit restoredStep = null;
        boolean originalExited = false;
        try {
            var original = f.ledger(f.token, f.step);
            assertEquals("WAITING", f.adapter(original, model, originalSaver).invoke(PROMPT).get("status"));
            assertEquals(1, model.callCount()); assertEquals(1, f.submits.get());
            ChildRecord waiting = f.runs.child(f.token, "child-async").orElseThrow();
            assertEquals(ChildState.WAITING, waiting.state()); assertEquals("job-stable", waiting.jobId());
            assertFalse(waiting.callbackActive()); assertEquals(0, f.activeCalls());
            var waitingLedger = f.ledger(f.token, f.step);
            var noOpModel = new ScriptedExplorationChatModel();
            assertEquals("WAITING", f.adapter(waitingLedger, noOpModel, new InspectingSaver(f, false)).invoke(PROMPT).get("status"));
            assertEquals(0, noOpModel.callCount()); assertEquals(1, f.submits.get());
            f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-job", f.authorizer);
            f.steps.callbackExited(f.step);
            originalExited = true;

            RunToken writer = f.steps.acquireRun(f.token);
            DispatchPermit reconcile = f.runs.beginReconciliation(writer, "child-async");
            try { f.runs.publishReady(reconcile, artifact("artifact-async")); }
            finally { f.runs.callbackExited(reconcile); }
            assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, f.planStep.stepId()).status());
            restoredStep = f.steps.beginStep(writer, f.planStep.stepId());
            StepPermit currentStep = restoredStep;
            ChildRecord ready = f.runs.child(writer, "child-async").orElseThrow();
            assertEquals(waiting.spec(), ready.spec()); assertEquals(waiting.jobId(), ready.jobId());

            f.readable.set(false);
            try {
                var denied = f.adapter(f.ledger(writer, currentStep), model, new InspectingSaver(f, false)).invoke(PROMPT);
                assertEquals("BLOCKED", denied.get("status"));
            } catch (SecurityException | IllegalStateException denied) { /* Both preserve the unconsumed durable evidence. */ }
            assertEquals(1, model.callCount()); assertEquals(1, f.submits.get());
            f.readable.set(true);
            var emptySaver = new InspectingSaver(f, false);
            var restored = f.ledger(writer, currentStep);
            var adapter = f.adapter(restored, model, emptySaver);
            var result = adapter.invoke(PROMPT);
            assertEquals("CANDIDATE", result.get("status")); assertNotEquals("SUCCEEDED", result.get("status"));
            assertEquals(List.of("artifact-async"), result.get("artifactIds"));
            assertEquals(2, model.callCount()); model.assertExhausted();
            assertEquals(1, f.submits.get()); assertEquals(1, f.callCount()); assertEquals(2, f.modelChildren());
            assertEquals(ready, f.runs.child(writer, "child-async").orElseThrow());
            assertEquals("job-stable", ready.jobId()); assertEquals(PAYLOAD, f.runs.readArtifact(OWNER, "artifact-async", f.authorizer).payloadJson());
            assertSafe(originalSaver, emptySaver);
            assertEquals("CANDIDATE", adapter.invoke(PROMPT).get("status"));
            assertEquals(2, model.callCount()); assertEquals(1, f.submits.get());
        } finally {
            f.readable.set(true);
            if (restoredStep != null) f.steps.callbackExited(restoredStep);
            if (!originalExited) f.steps.callbackExited(f.step);
        }
        f.exited();
    }

    private static org.springframework.ai.chat.model.ChatResponse toolCall(String id, String name, String arguments) {
        return ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall(id, "function", name, arguments));
    }
    private static void assertToolReceipt(Prompt prompt, String id, String name, String reference, long count) {
        long matches = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                .map(message -> (ToolResponseMessage) message).flatMap(message -> message.getResponses().stream())
                .filter(response -> id.equals(response.id()) && name.equals(response.name()) && response.responseData().contains(reference)).count();
        assertEquals(count, matches); assertFalse(prompt.getInstructions().toString().contains(RAW));
    }
    private static int occurrences(String value, String part) {
        int count = 0, offset = 0;
        while ((offset = value.indexOf(part, offset)) >= 0) { count++; offset += part.length(); }
        return count;
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static ArtifactDraft artifact(String id) { return new ArtifactDraft(id, "Evidence", "evidence/v1", "scope-frozen", "periods-frozen",
            "{\"collectionQuality\":\"UNKNOWN\"}", "{}", EXPIRY, PAYLOAD); }
    private static void assertSafe(InspectingSaver... savers) {
        for (var saver : savers) {
            assertFalse(saver.writes.isEmpty());
            for (String value : saver.writes) assertFalse(value.contains(RAW));
        }
    }

    static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final RunToken token;
        final StepPermit step;
        final PlanSpec.Step planStep;
        final Map<String, PlanSpec.ExecutorRef> executors;
        final JdbcExplorationLedger.ModelConfiguration configuration;
        final ModelInvocationRegistry models;
        final AtomicBoolean readable = new AtomicBoolean(true);
        final ArtifactAuthorizer authorizer = (caller, metadata) -> readable.get();
        final AtomicInteger firstCalls = new AtomicInteger(), secondCalls = new AtomicInteger(), submits = new AtomicInteger();
        final boolean async;

        Fixture(boolean async) {
            this(async, NativeExplorationAdapter.generationOptions(new ScriptedExplorationChatModel().getDefaultOptions()));
        }
        Fixture(boolean async, ModelInvocationRegistry.GenerationOptions generation) {
            this.async = async;
            var source = new DriverManagerDataSource("jdbc:h2:mem:durable_explore_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql"),
                    new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql")).execute(source);
            jdbc = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK); steps = new JdbcCampaignStepStore(jdbc, transactions, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(jdbc, transactions, CLOCK);
            var refs = new LinkedHashMap<String, PlanSpec.ExecutorRef>();
            for (String name : async ? List.of("submit_async") : List.of("read_first", "read_second"))
                refs.put(name, new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, name, "1"));
            executors = Map.copyOf(refs);
            planStep = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
                    new PlanSpec.ExplorationPolicy("campaign-explore", "1", List.copyOf(refs.values()), "scope-frozen", "periods-frozen",
                            List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of())), "bounded-fixture"),
                    List.of(), Map.of(), Map.of(), "analysis-evidence/v1");
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "explore-plan", 1, "explore-run", "explore-inputs",
                    List.of(new PlanSpec.Goal("goal", "Inspect authoritative evidence", true, "Evidence-backed answer")), List.of(planStep));
            var assessment = new PlanningAssessment("explore-plan", 1, "fixture/v1", List.of(), List.of(), List.of());
            var frozen = FrozenCampaignRun.freeze(plan, new FrozenInputSet("explore-inputs", "explore-run", Map.of(), Map.of()), assessment);
            token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "explore-session")));
            steps.initialize(token, List.of(new StepSpec(planStep.stepId(), encode(planStep), List.of(), Set.of(), Set.of())));
            step = steps.beginStep(token, planStep.stepId());
            List<ModelInvocationRegistry.ToolDefinition> tools = refs.keySet().stream().map(name -> new ModelInvocationRegistry.ToolDefinition(
                    name, "Read approved evidence", tree("read_second".equals(name) ? DEPENDENCY_SCHEMA : EMPTY_SCHEMA))).toList();
            configuration = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIG, null, tools, Map.of(), EXPIRY, generation);
            models = new ModelInvocationRegistry(List.of(new Contract("scripted-model", "1", CONFIG, invocation -> true)));
        }
        JdbcExplorationLedger ledger(RunToken writer, StepPermit permit) {
            assertEquals(writer.definition(), permit.runToken().definition());
            return new JdbcExplorationLedger(jdbc, transactions, CLOCK, new JdbcCampaignRunStore(jdbc, transactions, CLOCK),
                    new JdbcCampaignStepStore(jdbc, transactions, CLOCK), new JdbcCampaignExplorationCallStore(jdbc, transactions, CLOCK),
                    permit, models, configuration, executors, authorizer);
        }
        NativeExplorationAdapter adapter(JdbcExplorationLedger ledger, org.springframework.ai.chat.model.ChatModel model, InspectingSaver saver) {
            List<NativeExplorationAdapter.RegisteredTool> registered = new ArrayList<>();
            for (var definition : configuration.tools()) {
                String name = definition.name();
                ToolCallback callback = new ToolCallback() {
                    @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                        return org.springframework.ai.tool.definition.ToolDefinition.builder().name(name).description(definition.description())
                                .inputSchema(encode(definition.inputSchema())).build();
                    }
                    @Override public String call(String input) { throw new AssertionError("Real dispatch context required"); }
                    @Override public String call(String input, ToolContext context) {
                        var scope = (NativeExplorationAdapter.DispatchScope) context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
                        var call = scope.callPermit();
                        String childId = "submit_async".equals(name) ? "child-async" : "read_first".equals(name) ? "child-first" : "child-second";
                        boolean pending = "submit_async".equals(name);
                        RunToken current = call.step().runToken();
                        if ("read_second".equals(name)) {
                            assertEquals("artifact-first", tree(input).path("artifactId").asText());
                            assertEquals(PAYLOAD, runs.readArtifact(OWNER, "artifact-first", authorizer).payloadJson());
                        }
                        runs.prepareChild(current, new ChildSpec(childId, call.actionId(), pending ? ChildMode.ASYNC : ChildMode.SYNC,
                                "request-" + childId, new WireRequest(pending ? "POST" : "GET", "/fixture/" + name, "{}")));
                        var dispatch = runs.beginDispatch(current, childId, call);
                        try {
                            scope.dispatch(() -> { (pending ? submits : "read_first".equals(name) ? firstCalls : secondCalls).incrementAndGet(); return null; });
                            if (pending) { runs.recordWaiting(dispatch, "job-stable"); return "job-stable"; }
                            String id = "read_first".equals(name) ? "artifact-first" : "artifact-second";
                            runs.publishReady(dispatch, artifact(id)); return id;
                        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
                        finally { runs.callbackExited(dispatch); }
                    }
                };
                registered.add(new NativeExplorationAdapter.RegisteredTool(callback, value -> "submit_async".equals(name)
                        ? NativeExplorationAdapter.Observation.pending(value) : NativeExplorationAdapter.Observation.ready(value)));
            }
            return new NativeExplorationAdapter(ledger.identity(), ledger, model, registered, saver, Runnable::run, LIMITS, ledger);
        }
        int responses() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_model_response", Integer.class); }
        int modelChildren() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE child_mode='MODEL'", Integer.class); }
        int callCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call", Integer.class); }
        int activeChildren() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class); }
        int activeCalls() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE callback_active=TRUE", Integer.class); }
        void exited() {
            assertEquals(0, activeChildren()); assertEquals(0, activeCalls());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }
    static final class InspectingSaver extends MemorySaver {
        final Fixture fixture; final boolean failAfterSecondModel; final AtomicBoolean failed = new AtomicBoolean();
        final List<String> writes = new CopyOnWriteArrayList<>();
        InspectingSaver(Fixture fixture, boolean failAfterSecondModel) { this.fixture = fixture; this.failAfterSecondModel = failAfterSecondModel; }
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        private void capture(Checkpoint checkpoint) throws Exception {
            writes.add(AgentStateSerializerFactory.create().objectMapper().writeValueAsString(checkpoint.getState()));
            if (failAfterSecondModel && fixture.responses() == 2 && fixture.secondCalls.get() == 0 && failed.compareAndSet(false, true))
                throw new IllegalStateException("NATIVE_CHECKPOINT_FAILURE_AFTER_SECOND_MODEL_COMMIT");
        }
    }
}
