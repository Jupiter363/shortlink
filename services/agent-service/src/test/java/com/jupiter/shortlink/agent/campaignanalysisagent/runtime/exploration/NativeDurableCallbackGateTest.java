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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real callback and model ledgers; the decorator deliberately does not implement durable exploration. */
@Timeout(30)
class NativeDurableCallbackGateTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> true;
    private static final String PROMPT = "Inspect the approved evidence", SECRET = "PRIVATE_LATE_CALLBACK_FAILURE";
    private static final PlanSpec.ExecutorRef TOOL = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "inspect_metrics", "1");
    private static final Response RESPONSE = new Response("Read the saved observation.", List.of(new ToolCall("read-1", "inspect_metrics", "{}")));
    private static final String REQUEST = "{\"schemaVersion\":\"campaign-model-request/v1\",\"messages\":[{\"role\":\"user\",\"text\":\""
            + PROMPT + "\"}],\"tools\":[{\"name\":\"inspect_metrics\",\"description\":\"Read approved evidence\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
    private static final PlanSpec.Step STEP = planStep("explore"), OTHER = planStep("other");

    @Test
    void actualNativeTimeoutRetainsCallOwnershipAfterParentExitAndRejectsFurtherIoUntilActualFinally() throws Exception {
        var f = new Fixture();
        var ledger = new CallbackLedger(f);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var firstIo = new AtomicInteger(); var laterIo = new AtomicInteger();
        var lateDispatch = new AtomicReference<Exception>(); var latePublish = new AtomicReference<Exception>();
        var firstDispatch = new AtomicReference<DispatchPermit>();
        var model = new ScriptedExplorationChatModel().thenReturn(ScriptedExplorationChatModel.response(
                AssistantMessage.builder().content(RESPONSE.text())
                        .toolCalls(List.of(new AssistantMessage.ToolCall("read-1", "function", "inspect_metrics", "{}"))).build(), "tool_calls"));
        var saver = new InspectingSaver(); var executor = Executors.newSingleThreadExecutor();
        ToolCallback callback = new ToolCallback() {
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return toolDefinition(); }
            @Override public String call(String input) { throw new AssertionError("Trusted tool context is required"); }
            @Override public String call(String input, ToolContext context) {
                var scope = (NativeExplorationAdapter.DispatchScope) context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
                try {
                    CallPermit permit = ledger.permit.get();
                    f.prepareBusinessChild("first", ChildMode.SYNC, permit.actionId());
                    assertThrows(IllegalStateException.class, () -> f.runs.beginDispatch(f.token, "first"));
                    f.prepareBusinessChild("foreign", ChildMode.SYNC, "foreign-action");
                    assertThrows(IllegalStateException.class, () -> f.beginOwned("foreign", permit));
                    DispatchPermit child = f.beginOwned("first", permit); firstDispatch.set(child);
                    try {
                        scope.dispatch(firstIo::incrementAndGet);
                        f.runs.publishReady(child, artifact());
                    } finally { f.runs.callbackExited(child); }
                    assertEquals(ChildState.READY, f.runs.child(f.token, "first").orElseThrow().state());
                    f.assertModelAdmissionDenied();
                    entered.countDown();
                    awaitIgnoringInterrupt(release);
                    try { scope.dispatch(laterIo::incrementAndGet); } catch (Exception denied) { lateDispatch.set(denied); }
                    try { f.runs.publishReady(child, artifact()); } catch (Exception denied) { latePublish.set(denied); }
                    throw new IllegalStateException(SECRET);
                } catch (Exception rejected) { throw new IllegalStateException(rejected); }
                finally { exited.countDown(); }
            }
        };
        var limits = new NativeExplorationAdapter.Limits(0, 4096, 1024, 16384, 8, Duration.ofSeconds(1));
        var adapter = new NativeExplorationAdapter(f.key, ledger, model,
                List.of(new NativeExplorationAdapter.RegisteredTool(callback, raw -> NativeExplorationAdapter.Observation.ready("must-not-publish"))),
                saver, executor, limits, new DurableModelCallBoundary(f.runs, f.step, f.modelAction, f.modelChild, f.approval, ALLOW));
        try {
            Map<String, Object> result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> adapter.invoke(PROMPT));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals("BLOCKED", result.get("status")); assertEquals(1, firstIo.get());
            assertEquals(1, f.activeCalls()); assertEquals(1, ledger.view().activeCallbacks());
            assertFalse(f.calls.mayExecute(ledger.permit.get()), "A timed-out call is revoked, but its real callback still exists");
            assertThrows(IllegalStateException.class, () -> f.runs.publishReady(firstDispatch.get(), artifact()),
                    "Run and parent step are still current; even an idempotent READY publication needs the live CALL");
            f.steps.callbackExited(f.step);
            assertEquals(0, f.activeSteps()); assertEquals(0, f.activeChildren()); assertEquals(1, f.activeCalls());
            assertThrows(IllegalStateException.class, () -> f.runs.advance(f.token));
            assertThrows(IllegalStateException.class, () -> f.steps.acquireRun(f.token));
            assertThrows(IllegalStateException.class, () -> f.steps.beginStep(f.token, OTHER.stepId()));
            assertEquals("BLOCKED", adapter.invoke(PROMPT).get("status")); assertEquals(1, model.callCount());
            RunToken nextRevision = f.runs.revise(f.token, 2, f.definition(2).definitionJson());
            f.steps.initialize(nextRevision, List.of(spec(STEP), spec(OTHER)));
            assertThrows(IllegalStateException.class, () -> f.steps.beginStep(nextRevision, STEP.stepId()));
            assertEquals(1, f.activeCalls(), "A new revision cannot erase the old live callback");
            release.countDown(); assertTrue(exited.await(5, TimeUnit.SECONDS));
            executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, f.activeCalls()); assertEquals(0, ledger.view().activeCallbacks());
            assertEquals(0, laterIo.get()); assertNotNull(lateDispatch.get()); assertNotNull(latePublish.get());
            assertFalse(f.runs.mayDispatch(firstDispatch.get()));
            assertEquals(1, model.callCount()); model.assertExhausted();
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE artifact_id='must-not-publish'", Integer.class));
            assertSafe(saver);
            RunToken admitted = f.steps.acquireRun(nextRevision);
            StepPermit newStep = f.steps.beginStep(admitted, OTHER.stepId()); f.steps.callbackExited(newStep);
        } finally {
            release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            f.steps.callbackExited(f.step);
        }
    }

    @Test
    void processProofAloneClearsDeadCallAndPreservesReadyArtifactAndKnownJobWithoutDispatchingAgain() throws Exception {
        var f = new Fixture();
        f.publishModel();
        CallPermit call = f.beginCall();
        f.prepareBusinessChild("first", ChildMode.SYNC, call.actionId());
        var readyDispatch = f.beginOwned("first", call);
        try { f.runs.publishReady(readyDispatch, artifact()); } finally { f.runs.callbackExited(readyDispatch); }
        f.prepareBusinessChild("pending", ChildMode.ASYNC, call.actionId());
        var pendingDispatch = f.beginOwned("pending", call);
        try { f.runs.recordWaiting(pendingDispatch, "job-stable"); } finally { f.runs.callbackExited(pendingDispatch); }
        f.steps.callbackExited(f.step);
        ChildRecord ready = f.runs.child(f.token, "first").orElseThrow(), pending = f.runs.child(f.token, "pending").orElseThrow();
        Artifact source = f.runs.readArtifact(OWNER, "artifact-stable", ALLOW);
        var originalCall = f.calls.call(f.token, call.callId()).orElseThrow();
        var recovery = new JdbcCampaignRecoveryStore(f.jdbc, f.transactions, CLOCK, f.local, f.proof);
        for (var state : List.of(ProcessLiveness.State.ALIVE, ProcessLiveness.State.UNKNOWN)) {
            f.states.put(f.old, state);
            var blocked = recovery.recover(f.token);
            assertEquals(CampaignRecoveryStore.Outcome.BLOCKED, blocked.outcome()); assertEquals(0, blocked.recoveredCallbacks());
            assertEquals(originalCall, f.calls.call(f.token, call.callId()).orElseThrow());
            assertEquals(ready, f.runs.child(f.token, "first").orElseThrow()); assertEquals(pending, f.runs.child(f.token, "pending").orElseThrow());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_callback_recovery", Integer.class));
        }
        f.states.put(f.old, ProcessLiveness.State.DEAD);
        var takeover = recovery.recover(f.token);
        assertEquals(CampaignRecoveryStore.Outcome.ACQUIRED, takeover.outcome()); assertEquals(1, takeover.recoveredCallbacks());
        assertEquals(0, f.activeCalls()); assertFalse(f.calls.mayExecute(call));
        CallRecord recoveredCall = f.calls.call(takeover.token(), call.callId()).orElseThrow();
        assertEquals(originalCall.spec(), recoveredCall.spec()); assertEquals(originalCall.attemptId(), recoveredCall.attemptId());
        assertEquals(CallState.UNRESOLVED, recoveredCall.state()); assertEquals("CALL_RESULT_UNKNOWN", recoveredCall.reason());
        assertTrue(recoveredCall.revoked()); assertFalse(recoveredCall.callbackActive());
        assertEquals(ready, f.runs.child(takeover.token(), "first").orElseThrow());
        assertEquals(pending, f.runs.child(takeover.token(), "pending").orElseThrow());
        assertEquals(source, f.runs.readArtifact(OWNER, "artifact-stable", ALLOW));
        assertThrows(IllegalStateException.class, () -> f.beginOwned("pending", call));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_callback_recovery WHERE callback_kind='CALL' AND proof_code=?",
                Integer.class, ProcessLiveness.PROCESS_EXITED));
        assertEquals(0, f.activeChildren()); assertEquals(0, f.activeSteps());
    }

    private static PlanSpec.Step planStep(String id) {
        return new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
                new PlanSpec.ExplorationPolicy("campaign-explore", "1", List.of(TOOL), "scope-frozen", "periods-frozen",
                        List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of())), "single-call"),
                List.of(), Map.of(), Map.of(), "analysis-evidence/v1");
    }
    private static String encode(Object value) {
        try { return new ObjectMapper().writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static StepSpec spec(PlanSpec.Step step) { return new StepSpec(step.stepId(), encode(step), List.of(), Set.of(), Set.of()); }
    private static ArtifactDraft artifact() { return new ArtifactDraft("artifact-stable", "Evidence", "evidence/v1", "scope-frozen", "periods-frozen", "{}", "{}", EXPIRY, "{\"pv\":13}"); }
    private static org.springframework.ai.tool.definition.ToolDefinition toolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder().name("inspect_metrics").description("Read approved evidence")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean pending = true;
        while (pending) try { latch.await(); pending = false; } catch (InterruptedException ignored) { /* The already-started operation remains alive. */ }
    }
    private static void assertSafe(InspectingSaver saver) {
        assertFalse(saver.writes.isEmpty());
        for (String value : saver.writes) assertFalse(value.contains(SECRET));
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final RunToken token;
        final StepPermit step;
        final ModelActionSpec modelAction;
        final ChildSpec modelChild;
        final Approval approval;
        final ProcessIdentity old = process(101), local = process(202);
        final Map<ProcessIdentity, ProcessLiveness.State> states = new ConcurrentHashMap<>();
        final ProcessLiveness proof = identity -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var state = states.getOrDefault(identity, ProcessLiveness.State.UNKNOWN);
            return new ProcessLiveness.Observation(state, switch (state) {
                case ALIVE -> ProcessLiveness.PROCESS_ALIVE;
                case DEAD -> ProcessLiveness.PROCESS_EXITED;
                case UNKNOWN -> ProcessLiveness.PROCESS_ACCESS_DENIED;
            });
        };
        final NativeExplorationAdapter.ExecutionKey key = new NativeExplorationAdapter.ExecutionKey(OWNER.tenantId(), OWNER.subject(),
                OWNER.authVersion(), "call-session", "call-run", "call-plan", 1, STEP.stepId(), "1", "call-gate-test-v1", "single-turn-v1");
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:callback_gate_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql")).execute(source);
            jdbc = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK); steps = new JdbcCampaignStepStore(jdbc, transactions, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(jdbc, transactions, CLOCK);
            states.put(old, ProcessLiveness.State.ALIVE); states.put(local, ProcessLiveness.State.ALIVE);
            token = new JdbcCampaignRecoveryStore(jdbc, transactions, CLOCK, old, proof).recover(runs.createRun(definition(1))).token();
            steps.initialize(token, List.of(spec(STEP), spec(OTHER))); step = steps.beginStep(token, STEP.stepId());
            var identity = ModelInvocationRegistry.identity(token.definition(), STEP.stepId(), 1);
            var invocation = invocation(identity.invocationId(), 1);
            approval = approval(invocation);
            modelAction = modelAction(identity.actionId(), invocation);
            modelChild = new ChildSpec(identity.childId(), modelAction.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
            runs.prepareAction(token, new ActionSpec("foreign-action", OTHER.stepId(), "TOOL", "inspect_metrics", "1", "{}"));
        }
        RunDefinition definition(int revision) {
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "call-plan", revision, "call-run", "call-inputs",
                    List.of(new PlanSpec.Goal("goal", "Inspect the recorded observations", true, "Supported answer")), List.of(STEP, OTHER));
            var assessment = new PlanningAssessment("call-plan", revision, "call-catalog/v1", List.of(), List.of(), List.of());
            return FrozenCampaignRun.freeze(plan, new FrozenInputSet("call-inputs", "call-run", Map.of(), Map.of()), assessment).definition(OWNER, "call-session");
        }
        InvocationSpec invocation(String id, long turn) { return new InvocationSpec(id, turn, "scripted-model", "1", "b".repeat(64),
                "campaign-explore", "1", "call-inputs", REQUEST, Map.of(), EXPIRY); }
        Approval approval(InvocationSpec invocation) { return new ModelInvocationRegistry(List.of(new Contract("scripted-model", "1", "b".repeat(64), value -> true))).approve(invocation); }
        ModelActionSpec modelAction(String id, InvocationSpec invocation) { return new ModelActionSpec(id, STEP.stepId(), invocation.invocationId(),
                invocation.modelRef(), invocation.modelVersion(), invocation.policyRef(), invocation.policyVersion(), encode(STEP)); }
        void publishModel() {
            runs.prepareModelChild(step, modelAction, modelChild, approval, ALLOW);
            var dispatch = runs.beginModelDispatch(step, modelChild.childId(), approval, ALLOW);
            try { runs.publishModelResponse(step, dispatch, approval, RESPONSE, ALLOW); } finally { runs.callbackExited(dispatch); }
        }
        CallPermit beginCall() {
            var identity = CampaignExplorationCallStore.identity(token.definition(), STEP.stepId(), modelChild.childId(), "read-1");
            var call = new CallSpec(identity.callId(), identity.actionId(), STEP.stepId(), modelChild.childId(),
                    CampaignRunStore.sha256(ModelInvocationRegistry.encodeResponse(RESPONSE)), "read-1", TOOL, "{}");
            calls.prepare(step, call, approval, ALLOW); return calls.beginCall(step, identity.callId(), approval, ALLOW);
        }
        void prepareBusinessChild(String id, ChildMode mode, String actionId) {
            runs.prepareChild(token, new ChildSpec(id, actionId, mode, "request-" + id, new WireRequest(mode == ChildMode.ASYNC ? "POST" : "GET", "/fixture", "{}")));
        }
        DispatchPermit beginOwned(String id, CallPermit call) { return runs.beginDispatch(token, id, call); }
        void assertModelAdmissionDenied() {
            var identity = ModelInvocationRegistry.identity(token.definition(), STEP.stepId(), 2);
            var invocation = invocation(identity.invocationId(), 2); var nextApproval = approval(invocation);
            var child = new ChildSpec(identity.childId(), identity.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
            assertThrows(IllegalStateException.class, () -> runs.prepareModelChild(step,
                    modelAction(identity.actionId(), invocation), child, nextApproval, ALLOW));
            assertTrue(runs.child(token, child.childId()).isEmpty(), "Active CALL blocks admission before a new MODEL row exists");
        }
        int activeCalls() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE callback_active=TRUE", Integer.class); }
        int activeChildren() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class); }
        int activeSteps() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class); }
    }
    private static ProcessIdentity process(int pid) { return new ProcessIdentity(UUID.randomUUID().toString(), "callback-test-domain", pid, NOW.minusSeconds(60).toEpochMilli() + pid); }

    private static final class CallbackLedger implements ExplorationLedger {
        final Fixture f; final ExplorationTestLedger delegate; final AtomicReference<CallPermit> permit = new AtomicReference<>();
        CallbackLedger(Fixture f) { this.f = f; delegate = new ExplorationTestLedger(f.key); }
        @Override public NativeExplorationAdapter.ExecutionKey identity() { return delegate.identity(); }
        @Override public View view() { return delegate.view(); }
        @Override public void freezeInput(String value) { delegate.freezeInput(value); }
        @Override public void registerCall(CallInput call) { delegate.registerCall(call); }
        @Override public Optional<ResumeFacts> readyToResume() { return delegate.readyToResume(); }
        @Override public boolean approveResume(String id) { return delegate.approveResume(id); }
        @Override public void acknowledgeResume(String id) { delegate.acknowledgeResume(id); }
        @Override public void releaseResume(String id) { delegate.releaseResume(id); }
        @Override public boolean mayCallModel() { return delegate.mayCallModel(); }
        @Override public boolean rejectBatch(int maximum) { return delegate.rejectBatch(maximum); }
        @Override public void fail(String reason) { delegate.fail(reason); }
        @Override public void candidate() { delegate.candidate(); }
        @Override public long beginCallback(String id, String name) {
            assertEquals("read-1", id); assertEquals("inspect_metrics", name);
            permit.set(f.beginCall()); return delegate.beginCallback(id, name);
        }
        @Override public boolean mayDispatch(long attempt) { return delegate.mayDispatch(attempt) && f.calls.mayExecute(permit.get()); }
        @Override public void recordObservation(long attempt, NativeExplorationAdapter.Observation observation) {
            if (f.calls.mayExecute(permit.get())) { f.calls.recordReturned(permit.get()); delegate.recordObservation(attempt, observation); }
        }
        @Override public void unresolved(long attempt) { f.calls.revoke(permit.get(), "EXECUTION_UNRESOLVED"); delegate.unresolved(attempt); }
        @Override public void callbackExited(long attempt) { try { f.calls.callbackExited(permit.get()); } finally { delegate.callbackExited(attempt); } }
    }
    private static final class InspectingSaver extends MemorySaver {
        final List<String> writes = new CopyOnWriteArrayList<>();
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> values, Checkpoint checkpoint) throws Exception { capture(checkpoint); }
        private void capture(Checkpoint checkpoint) throws Exception { writes.add(AgentStateSerializerFactory.create().objectMapper().writeValueAsString(checkpoint.getState())); }
    }
}
