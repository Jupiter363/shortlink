package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** One native model turn with a real durable boundary; no provider, service or durable tool-loop claim. */
@Timeout(30)
class NativeDurableModelBoundaryTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> true;
    private static final String PROMPT = "Inspect the approved evidence", PUBLIC_TEXT = "Read the saved observation.";
    private static final String PRIVATE = "PROVIDER_REASONING_MUST_NOT_BE_SAVED", SECRET = "SENSITIVE_PROVIDER_FAILURE";
    private static final String CONFIG = "b".repeat(64), INPUT_BODY = "{\"pv\":13,\"collectionQuality\":\"UNKNOWN\"}";
    private static final NativeExplorationAdapter.Limits LIMITS =
            new NativeExplorationAdapter.Limits(0, 4096, 1024, 16384, 8, Duration.ofSeconds(5));
    private static final String REQUEST = "{\"schemaVersion\":\"campaign-model-request/v1\",\"messages\":[{\"role\":\"user\",\"text\":\""
            + PROMPT + "\"}],\"tools\":[{\"name\":\"inspect_metrics\",\"description\":\"Read approved evidence\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
    private static final PlanSpec.Step STEP = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
            new PlanSpec.ExplorationPolicy("campaign-explore", "1", List.of(new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "inspect_metrics", "1")),
                    "scope-frozen", "periods-frozen", List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of())), "single-turn"),
            List.of(), Map.of("source", PlanBinding.artifact("source-artifact")), Map.of(), "analysis-evidence/v1");

    @Test
    void durableResponseSurvivesNativeCheckpointFailureAndReplaysWithoutAnotherModelCall() throws Exception {
        var f = new Fixture();
        var model = new ScriptedExplorationChatModel().thenReturn(publicResponseWithPrivateMetadata());
        var failedSaver = new InspectingSaver(f, true);
        var firstResponse = new AtomicReference<Response>();
        try {
            var boundary = new DurableModelCallBoundary(f.runs, f.step, f.action, f.spec, f.approval, ALLOW);
            f.transactions.execute(status -> {
                var rejected = assertThrows(IllegalStateException.class, () -> boundary.call(
                        ModelInvocationRegistry.decodeRequest(REQUEST), () -> fail("Provider cannot run inside an uncommitted transaction")));
                assertEquals("MODEL_CALL_REQUIRES_COMMITTED_PREPARATION", rejected.getMessage());
                return null;
            });
            assertEquals(0, f.modelChildren());
            var first = f.adapter(f.runs, new ExplorationTestLedger(f.key), model, failedSaver, firstResponse);
            assertThrows(Exception.class, () -> first.invoke(PROMPT));
            assertTrue(failedSaver.failed.get(), "Failure occurs in a native saver write after the response commits");
            assertEquals(1, f.responses()); assertEquals(1, model.callCount()); assertEquals(0, f.tools.get());
            ChildRecord saved = f.child(f.runs);
            assertEquals(ChildState.READY, saved.state()); assertFalse(saved.callbackActive());
            assertEquals(1, saved.attemptVersion()); assertNull(saved.artifactId()); assertNull(saved.jobId());
            assertTrue(f.steps.step(f.token, STEP.stepId()).orElseThrow().callbackActive(), "Reuse the same still-valid parent callback");

            var reopened = new JdbcCampaignRunStore(f.jdbc, f.transactions, CLOCK);
            var replaySaver = new InspectingSaver(f, false);
            var replayed = new AtomicReference<Response>();
            var ledger = new ExplorationTestLedger(f.key);
            var replay = f.adapter(reopened, ledger, model, replaySaver, replayed);
            assertEquals("WAITING", replay.invoke(PROMPT).get("status"));
            assertEquals(firstResponse.get(), replayed.get());
            assertEquals(PUBLIC_TEXT, replayed.get().text());
            assertEquals(List.of(new ToolCall("read-1", "inspect_metrics", "{}")), replayed.get().toolCalls());
            assertEquals(replayed.get(), reopened.readModelResponse(f.token, f.spec.childId(), f.approval, ALLOW));
            assertEquals(1, model.callCount()); model.assertExhausted();
            assertEquals(1, f.tools.get()); assertEquals(1, f.responses()); assertEquals(1, f.modelChildren());
            assertEquals(saved.spec(), f.child(reopened).spec()); assertEquals(saved.attemptId(), f.child(reopened).attemptId());
            assertEquals(INPUT_BODY, reopened.readArtifact(OWNER, "source-artifact", ALLOW).payloadJson());
            assertTrue(replaySaver.writes.stream().anyMatch(value -> value.contains(PUBLIC_TEXT) && value.contains("read-1")));
            assertSafeCheckpoints(failedSaver, replaySaver);
            assertFalse(f.jdbc.queryForObject("SELECT response_json FROM campaign_model_response", String.class).contains(PRIVATE));
            replay.invoke(PROMPT); // A waiting native ledger does not enter a second turn.
            assertEquals(1, model.callCount()); assertEquals(1, f.tools.get());
        } finally { f.steps.callbackExited(f.step); }
        f.exited();
    }

    @Test
    void cancelledLateModelResponseAndUnknownTurnCannotPublishDispatchToolsOrRetryProvider() throws Exception {
        var f = new Fixture();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var model = new ScriptedExplorationChatModel(prompt -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Model latch not released"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
            return publicResponseWithPrivateMetadata();
        });
        var saver = new InspectingSaver(f, false);
        var executor = Executors.newSingleThreadExecutor();
        Future<?> inFlight = null;
        try {
            var adapter = f.adapter(f.runs, new ExplorationTestLedger(f.key), model, saver, new AtomicReference<>());
            inFlight = executor.submit(() -> { try { adapter.invoke(PROMPT); } catch (Exception expected) { /* Fixed boundary rejection is allowed. */ } });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            ChildRecord dispatched = f.child(f.runs);
            assertEquals(ChildState.DISPATCHING, dispatched.state()); assertTrue(dispatched.callbackActive());
            f.runs.cancel(f.token);
            RunToken cancelled = f.runs.loadRun(OWNER, "model-run").orElseThrow().token();
            assertTrue(f.runs.child(cancelled, f.spec.childId()).orElseThrow().callbackActive());
            assertThrows(IllegalStateException.class, () -> f.steps.acquireRun(cancelled));
            assertEquals(0, f.responses()); assertEquals(0, f.tools.get());
            release.countDown(); inFlight.get(10, TimeUnit.SECONDS);
            ChildRecord late = f.runs.child(cancelled, f.spec.childId()).orElseThrow();
            assertNotEquals(ChildState.READY, late.state()); assertFalse(late.callbackActive());
            assertEquals(0, f.responses()); assertEquals(0, f.tools.get()); assertEquals(1, model.callCount());
            assertSafeCheckpoints(saver);
        } finally {
            release.countDown();
            try { if (inFlight != null) inFlight.get(10, TimeUnit.SECONDS); }
            finally {
                executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                f.steps.callbackExited(f.step);
            }
        }
        f.exited();

        var unknown = new Fixture();
        var failedModel = new ScriptedExplorationChatModel(prompt -> { throw new IllegalStateException(SECRET); });
        var firstSaver = new InspectingSaver(unknown, false);
        var retrySaver = new InspectingSaver(unknown, false);
        try {
            invokeRejected(unknown.adapter(unknown.runs, new ExplorationTestLedger(unknown.key), failedModel, firstSaver, new AtomicReference<>()));
            ChildRecord original = unknown.child(unknown.runs);
            assertEquals(ChildState.UNRESOLVED, original.state()); assertEquals(UnresolvedReason.MODEL_RESULT_UNKNOWN, original.reason());
            assertFalse(original.callbackActive()); assertEquals(1, failedModel.callCount());
            var reopened = new JdbcCampaignRunStore(unknown.jdbc, unknown.transactions, CLOCK);
            invokeRejected(unknown.adapter(reopened, new ExplorationTestLedger(unknown.key), failedModel, retrySaver, new AtomicReference<>()));
            assertEquals(original, unknown.child(reopened));
            assertEquals(1, failedModel.callCount()); assertEquals(1, unknown.modelChildren());
            assertEquals(0, unknown.responses()); assertEquals(0, unknown.tools.get());
            assertSafeCheckpoints(firstSaver, retrySaver);
        } finally { unknown.steps.callbackExited(unknown.step); }
        unknown.exited();
    }

    private static void invokeRejected(NativeExplorationAdapter adapter) throws Exception {
        try { adapter.invoke(PROMPT); } catch (Exception expected) { /* Ledger assertions below prove the rejection, not an exception type. */ }
    }
    private static org.springframework.ai.chat.model.ChatResponse publicResponseWithPrivateMetadata() {
        return ScriptedExplorationChatModel.response(AssistantMessage.builder().content(PUBLIC_TEXT)
                .properties(Map.of("provider_reasoning", PRIVATE, "provider_id", "PRIVATE_PROVIDER_ID"))
                .toolCalls(List.of(new AssistantMessage.ToolCall("read-1", "function", "inspect_metrics", "{}"))).build(), "tool_calls");
    }
    private static void assertSafeCheckpoints(InspectingSaver... savers) {
        for (var saver : savers) {
            assertFalse(saver.writes.isEmpty(), "The real native saver must have observed checkpoints");
            for (String value : saver.writes) {
                assertFalse(value.contains(PRIVATE)); assertFalse(value.contains("PRIVATE_PROVIDER_ID"));
                assertFalse(value.contains(SECRET)); assertFalse(value.contains("provider_reasoning"));
            }
        }
    }
    private static String stepJson() {
        try { return new ObjectMapper().writeValueAsString(STEP); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final RunToken token;
        final StepPermit step;
        final ChildSpec spec;
        final ModelActionSpec action;
        final Approval approval;
        final AtomicInteger tools = new AtomicInteger();
        final NativeExplorationAdapter.ExecutionKey key = new NativeExplorationAdapter.ExecutionKey(
                OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), "model-session", "model-run", "model-plan", 1,
                STEP.stepId(), "1", "durable-boundary-test-v1", "single-turn-v1");

        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:native_model_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql")).execute(source);
            jdbc = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK); steps = new JdbcCampaignStepStore(jdbc, transactions, CLOCK);
            var sourceRun = runs.createRun(new RunDefinition(OWNER, "source-session", "source-run", "source-plan", 1, "{}"));
            runs.prepareAction(sourceRun, new ActionSpec("source-action", "source-step", "TOOL", "metrics", "1", "{}"));
            runs.prepareChild(sourceRun, new ChildSpec("source-child", "source-action", ChildMode.SYNC, "source-request", new WireRequest("GET", "/fixture", "{}")));
            var sourceDispatch = runs.beginDispatch(sourceRun, "source-child");
            try { runs.publishReady(sourceDispatch, new ArtifactDraft("source-artifact", "Evidence", "evidence/v1", "scope-frozen", "periods-frozen",
                    "{\"collectionQuality\":\"UNKNOWN\"}", "{}", EXPIRY, INPUT_BODY)); }
            finally { runs.callbackExited(sourceDispatch); }
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "model-plan", 1, "model-run", "model-inputs",
                    List.of(new PlanSpec.Goal("goal", "Inspect frozen evidence", true, "Deliver supported analysis")), List.of(STEP));
            var assessment = new PlanningAssessment("model-plan", 1, "model-catalog/v1",
                    List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())),
                    List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(STEP.stepId(), "analysis")))), List.of());
            var frozen = FrozenCampaignRun.freeze(plan, new FrozenInputSet("model-inputs", "model-run", Map.of(), Map.of()), assessment);
            token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "model-session")));
            steps.initialize(token, List.of(new StepSpec(STEP.stepId(), stepJson(), List.of(), Set.of("analysis"), Set.of("analysis"))));
            step = steps.beginStep(token, STEP.stepId());
            Identity identity = ModelInvocationRegistry.identity(token.definition(), STEP.stepId(), 1);
            var invocation = new InvocationSpec(identity.invocationId(), 1, "scripted-model", "1", CONFIG, "campaign-explore", "1", "model-inputs",
                    REQUEST, Map.of("source", runs.inspectArtifact(OWNER, "source-artifact", ALLOW)), EXPIRY);
            approval = new ModelInvocationRegistry(List.of(new Contract("scripted-model", "1", CONFIG, value -> true))).approve(invocation);
            action = new ModelActionSpec(identity.actionId(), STEP.stepId(), invocation.invocationId(), invocation.modelRef(), invocation.modelVersion(),
                    invocation.policyRef(), invocation.policyVersion(), stepJson());
            spec = new ChildSpec(identity.childId(), action.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
        }
        NativeExplorationAdapter adapter(CampaignRunStore store, ExplorationTestLedger ledger, ScriptedExplorationChatModel model,
                InspectingSaver saver, AtomicReference<Response> observed) {
            var durable = new DurableModelCallBoundary(store, step, action, spec, approval, ALLOW);
            ModelCallBoundary boundary = (request, live) -> {
                Response response = durable.call(request, live); observed.set(response); return response;
            };
            ToolCallback callback = new ToolCallback() {
                @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return org.springframework.ai.tool.definition.ToolDefinition.builder().name("inspect_metrics").description("Read approved evidence")
                            .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
                }
                @Override public String call(String input) { tools.incrementAndGet(); return "durable-observation"; }
            };
            return new NativeExplorationAdapter(key, ledger, model,
                    List.of(new NativeExplorationAdapter.RegisteredTool(callback, raw -> NativeExplorationAdapter.Observation.pending("job-observation"))),
                    saver, Runnable::run, LIMITS, boundary);
        }
        ChildRecord child(CampaignRunStore store) { return store.child(token, spec.childId()).orElseThrow(); }
        int responses() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_model_response", Integer.class); }
        int modelChildren() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE child_mode='MODEL'", Integer.class); }
        void exited() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static final class InspectingSaver extends MemorySaver {
        final Fixture fixture;
        final boolean failAfterResponse;
        final AtomicBoolean failed = new AtomicBoolean();
        final List<String> writes = new CopyOnWriteArrayList<>();
        InspectingSaver(Fixture fixture, boolean failAfterResponse) { this.fixture = fixture; this.failAfterResponse = failAfterResponse; }
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        private void capture(Checkpoint checkpoint) throws Exception {
            writes.add(AgentStateSerializerFactory.create().objectMapper().writeValueAsString(checkpoint.getState()));
            if (failAfterResponse && fixture.responses() == 1 && failed.compareAndSet(false, true))
                throw new IllegalStateException("CHECKPOINT_WRITE_FAILED_AFTER_MODEL_COMMIT");
        }
    }
}
