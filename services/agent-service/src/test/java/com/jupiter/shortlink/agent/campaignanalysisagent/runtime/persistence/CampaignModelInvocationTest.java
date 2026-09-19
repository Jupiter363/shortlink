package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Model ledger only: no native REACT execution, model provider, network or business tool is started. */
@Timeout(30)
class CampaignModelInvocationTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final String CONFIG = "a".repeat(64), INPUT_BODY = "{\"pv\":13}";
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> true;
    private static final String REQUEST = "{\"schemaVersion\":\"campaign-model-request/v1\",\"messages\":[{\"role\":\"user\",\"text\":\"Compare the frozen observations\"}],"
            + "\"tools\":[{\"name\":\"inspect_metrics\",\"description\":\"Read the approved evidence\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"metric\":{\"type\":\"string\"}},\"additionalProperties\":false}}]}";
    private static final Response RESPONSE = new Response("Inspect the recorded observations.",
            List.of(new ToolCall("call-1", "inspect_metrics", "{\"metric\":\"PV\"}")));
    private static final PlanSpec.Step STEP = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
            new PlanSpec.ExplorationPolicy("campaign-explore", "1", List.of(new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "inspect_metrics", "1")),
                    "scope-frozen", "periods-frozen", List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of())), "bounded-turns"),
            List.of(), Map.of("source", PlanBinding.artifact("source-artifact")), Map.of(), "analysis-evidence/v1");

    @Test
    void modelResponseAndReadyCommitAtomicallyAndOriginalResponseReopensWithoutBecomingAnArtifact() throws Exception {
        var f = new Fixture();
        StepPermit step = f.steps.beginStep(f.token, STEP.stepId());
        DispatchPermit dispatch = null;
        try {
            assertThrows(IllegalStateException.class, () -> f.runs.prepareChild(f.token, f.child));
            ChildSpec renamed = new ChildSpec("renamed-model-child", f.child.actionId(), ChildMode.MODEL,
                    f.child.requestId(), null, null, f.invocation);
            assertThrows(IllegalStateException.class, () -> f.runs.prepareModelChild(step, f.action, renamed, f.approval, ALLOW));
            f.runs.prepareModelChild(step, f.action, f.child, f.approval, ALLOW);
            InvocationSpec changed = new InvocationSpec(f.invocation.invocationId(), f.invocation.turnIndex(),
                    f.invocation.modelRef(), f.invocation.modelVersion(), CONFIG, f.invocation.policyRef(),
                    f.invocation.policyVersion(), f.invocation.inputSetRef(), REQUEST.replace("Compare", "Recheck"),
                    f.invocation.inputs(), EXPIRY);
            Approval changedApproval = new ModelInvocationRegistry(List.of(new Contract("test-model", "1", CONFIG,
                    value -> true))).approve(changed);
            ChildSpec changedChild = new ChildSpec(f.child.childId(), f.child.actionId(), ChildMode.MODEL,
                    f.child.requestId(), null, null, changed);
            assertThrows(IllegalStateException.class, () -> f.runs.prepareModelChild(step, f.action, changedChild, changedApproval, ALLOW));
            assertEquals(ChildState.PREPARED, f.runs.child(f.token, f.child.childId()).orElseThrow().state());
            assertThrows(IllegalStateException.class, () -> f.runs.beginDispatch(f.token, f.child.childId()));
            dispatch = f.runs.beginModelDispatch(step, f.child.childId(), f.approval, ALLOW);
            f.vendorCalls.incrementAndGet(); // One stand-in model result, with no provider call.
            DispatchPermit active = dispatch;
            assertTrue(f.runs.mayDispatch(active));
            assertThrows(IllegalStateException.class, () -> f.steps.acquireRun(f.token));
            assertThrows(IllegalStateException.class, () -> f.runs.beginReconciliation(f.token, f.child.childId()));
            assertThrows(IllegalStateException.class, () -> f.runs.publishReady(active, fakeArtifact()));
            assertEquals(0, f.outputs());

            f.jdbc.execute("ALTER TABLE campaign_model_response ADD CONSTRAINT reject_model_response CHECK (response_json='{}')");
            assertThrows(IllegalStateException.class, () -> f.runs.publishModelResponse(step, active, f.approval, RESPONSE, ALLOW));
            assertEquals(0, f.responses());
            ChildRecord failedInsert = f.runs.child(f.token, f.child.childId()).orElseThrow();
            assertEquals(ChildState.DISPATCHING, failedInsert.state()); assertTrue(failedInsert.callbackActive());
            assertTrue(f.runs.mayDispatch(active));
            f.jdbc.execute("ALTER TABLE campaign_model_response DROP CONSTRAINT reject_model_response");
            f.runs.publishModelResponse(step, active, f.approval, RESPONSE, ALLOW);
            ChildRecord ready = f.runs.child(f.token, f.child.childId()).orElseThrow();
            assertEquals(ChildState.READY, ready.state()); assertTrue(ready.callbackActive());
            assertEquals(1, ready.attemptVersion()); assertNull(ready.jobId()); assertNull(ready.artifactId());
            assertNull(ready.spec().wire()); assertNull(ready.spec().localInvocation());
            assertEquals(f.invocation, ready.spec().modelInvocation());
            assertEquals(1, f.responses()); assertEquals(0, f.outputs());
            assertEquals(RESPONSE, f.runs.readModelResponse(f.token, f.child.childId(), f.approval, ALLOW));
            assertEquals("MODEL", f.jdbc.queryForObject("SELECT action_kind FROM campaign_action_ledger WHERE run_id=? AND action_id=?",
                    String.class, f.token.definition().runId(), f.action.actionId()));
            assertNull(f.jdbc.queryForObject("SELECT executor_kind FROM campaign_action_ledger WHERE run_id=? AND action_id=?",
                    String.class, f.token.definition().runId(), f.action.actionId()));
        } finally {
            if (dispatch != null) f.runs.callbackExited(dispatch);
            f.steps.callbackExited(step);
        }
        f.exited();
        var reopened = new JdbcCampaignRunStore(f.jdbc, f.transactions, f.clock);
        RunToken reacquired = f.steps.acquireRun(f.token);
        assertEquals(RESPONSE, reopened.readModelResponse(reacquired, f.child.childId(), f.approval, ALLOW));
        assertEquals(1, f.vendorCalls.get()); assertEquals(1, f.responses()); assertEquals(0, f.outputs());
        assertEquals(f.child, reopened.child(reacquired, f.child.childId()).orElseThrow().spec());
        var gateway = mock(ShortLinkBusinessGateway.class);
        assertNotEquals(StatisticsSubmissionReconciler.Outcome.RECOVERED,
                new StatisticsSubmissionReconciler(reopened, gateway).recover(reacquired, f.child.childId(), PRINCIPAL).outcome());
        var receiver = new StatisticsJobResultReceiver(reopened,
                new JdbcCampaignStatisticsResultStore(f.jdbc, f.transactions, f.clock), gateway, f.clock, 1);
        assertEquals(StatisticsJobResultReceiver.Outcome.NOT_APPLICABLE,
                receiver.receive(reacquired, f.child.childId(), PRINCIPAL,
                        new StatisticsJobResultReceiver.Target("must-not-create", "scope-frozen", "periods-frozen"), () -> true).outcome());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_receipt", Integer.class));
        verifyNoInteractions(gateway);
        for (String invalid : List.of(
                "{\"text\":\"ok\",\"toolCalls\":[],\"reasoning\":\"not public\"}",
                "{\"text\":\"ok\",\"toolCalls\":[],\"unknown\":true}",
                "{\"text\":\"ok\",\"text\":\"duplicate\",\"toolCalls\":[]}",
                "{\"text\":\"ok\",\"toolCalls\":[]} {}"))
            assertThrows(IllegalArgumentException.class, () -> ModelInvocationRegistry.decodeResponse(invalid));
    }

    @Test
    void currentAuthorizationOriginalInputsExpiryAndCancellationFencePublicationAndUnknownCannotRedispatch() throws Exception {
        var f = new Fixture();
        AtomicBoolean readable = new AtomicBoolean(true);
        ArtifactAuthorizer auth = (caller, input) -> readable.get();
        StepPermit step = f.steps.beginStep(f.token, STEP.stepId());
        f.runs.prepareModelChild(step, f.action, f.child, f.approval, auth);
        DispatchPermit dispatch = f.runs.beginModelDispatch(step, f.child.childId(), f.approval, auth);
        f.vendorCalls.incrementAndGet();
        try {
            readable.set(false);
            assertThrows(SecurityException.class, () -> f.runs.publishModelResponse(step, dispatch, f.approval, RESPONSE, auth));
            assertEquals(0, f.responses());
            assertThrows(IllegalStateException.class, () -> f.steps.acquireRun(f.token));
            readable.set(true);
            assertEquals(1, f.jdbc.update("UPDATE campaign_artifact_payload SET payload_json='{\"pv\":999}' WHERE artifact_id='source-artifact'"));
            assertThrows(IllegalStateException.class, () -> f.runs.publishModelResponse(step, dispatch, f.approval, RESPONSE, auth));
            assertEquals(0, f.responses());
            f.jdbc.update("UPDATE campaign_artifact_payload SET payload_json=? WHERE artifact_id='source-artifact'", INPUT_BODY);
            f.clock.current = EXPIRY;
            assertThrows(SecurityException.class, () -> f.runs.publishModelResponse(step, dispatch, f.approval, RESPONSE, auth));
            f.clock.current = NOW;
            StepPermit stale = new StepPermit(f.token, STEP.stepId(), "stale-step-attempt", step.attemptVersion());
            assertThrows(IllegalStateException.class, () -> f.runs.publishModelResponse(stale, dispatch, f.approval, RESPONSE, auth));
            f.runs.cancel(f.token);
            assertThrows(IllegalStateException.class, () -> f.runs.publishModelResponse(step, dispatch, f.approval, RESPONSE, auth));
            assertTrue(f.runs.child(f.runs.loadRun(OWNER, "model-run").orElseThrow().token(), f.child.childId()).orElseThrow().callbackActive(),
                    "Cancellation does not pretend the active model callback has exited");
            assertEquals(0, f.responses()); assertEquals(0, f.outputs());
        } finally {
            f.runs.callbackExited(dispatch);
            f.steps.callbackExited(step);
        }
        f.exited(); assertEquals(1, f.vendorCalls.get());

        var unknown = new Fixture();
        StepPermit active = unknown.steps.beginStep(unknown.token, STEP.stepId());
        unknown.runs.prepareModelChild(active, unknown.action, unknown.child, unknown.approval, ALLOW);
        DispatchPermit sent = unknown.runs.beginModelDispatch(active, unknown.child.childId(), unknown.approval, ALLOW);
        unknown.vendorCalls.incrementAndGet();
        try {
            unknown.runs.markUnresolved(sent);
        } finally { unknown.runs.callbackExited(sent); }
        try {
            ChildRecord unresolved = unknown.runs.child(unknown.token, unknown.child.childId()).orElseThrow();
            assertEquals(ChildState.UNRESOLVED, unresolved.state());
            assertEquals(UnresolvedReason.MODEL_RESULT_UNKNOWN, unresolved.reason()); assertFalse(unresolved.callbackActive());
            IllegalStateException denied = assertThrows(IllegalStateException.class,
                    () -> unknown.runs.beginModelDispatch(active, unknown.child.childId(), unknown.approval, ALLOW));
            assertEquals("MODEL_DISPATCH_REQUIRES_PREPARED", denied.getMessage());
            var gateway = mock(ShortLinkBusinessGateway.class);
            new StatisticsSubmissionReconciler(unknown.runs, gateway).recover(unknown.token, unknown.child.childId(), PRINCIPAL);
            verifyNoInteractions(gateway);
            assertEquals(1, unknown.vendorCalls.get()); assertEquals(0, unknown.responses());
        } finally { unknown.steps.callbackExited(active); }
        unknown.exited();
    }

    @Test
    void onlyProvedDeadProcessClearsModelCallbacksAndTakeoverPreservesUnknownWithoutAnotherModelCall() throws Exception {
        var f = new Fixture();
        ProcessIdentity old = process(101), local = process(202);
        Map<ProcessIdentity, ProcessLiveness.State> states = new HashMap<>();
        states.put(old, ProcessLiveness.State.ALIVE); states.put(local, ProcessLiveness.State.ALIVE);
        ProcessLiveness proof = identity -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var state = states.getOrDefault(identity, ProcessLiveness.State.UNKNOWN);
            return new ProcessLiveness.Observation(state, switch (state) {
                case ALIVE -> ProcessLiveness.PROCESS_ALIVE;
                case DEAD -> ProcessLiveness.PROCESS_EXITED;
                case UNKNOWN -> ProcessLiveness.PROCESS_ACCESS_DENIED;
            });
        };
        var owner = new JdbcCampaignRecoveryStore(f.jdbc, f.transactions, f.clock, old, proof).recover(f.token);
        assertEquals(CampaignRecoveryStore.Outcome.ACQUIRED, owner.outcome());
        RunToken owned = owner.token();
        StepPermit step = f.steps.beginStep(owned, STEP.stepId());
        f.runs.prepareModelChild(step, f.action, f.child, f.approval, ALLOW);
        DispatchPermit sent = f.runs.beginModelDispatch(step, f.child.childId(), f.approval, ALLOW);
        f.vendorCalls.incrementAndGet();
        ChildRecord before = f.runs.child(owned, f.child.childId()).orElseThrow();
        StepRecord beforeStep = f.steps.step(owned, STEP.stepId()).orElseThrow();
        var takeover = new JdbcCampaignRecoveryStore(f.jdbc, f.transactions, f.clock, local, proof);
        for (var state : List.of(ProcessLiveness.State.ALIVE, ProcessLiveness.State.UNKNOWN)) {
            states.put(old, state);
            var rejected = takeover.recover(owned);
            assertEquals(CampaignRecoveryStore.Outcome.BLOCKED, rejected.outcome()); assertEquals(0, rejected.recoveredCallbacks());
            assertEquals(before, f.runs.child(owned, f.child.childId()).orElseThrow());
            assertEquals(beforeStep, f.steps.step(owned, STEP.stepId()).orElseThrow());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_callback_recovery", Integer.class));
        }
        states.put(old, ProcessLiveness.State.DEAD);
        var recovered = takeover.recover(owned);
        assertEquals(CampaignRecoveryStore.Outcome.ACQUIRED, recovered.outcome()); assertEquals(2, recovered.recoveredCallbacks());
        ChildRecord child = f.runs.child(recovered.token(), f.child.childId()).orElseThrow();
        assertEquals(before.spec(), child.spec()); assertEquals(before.attemptId(), child.attemptId());
        assertEquals(before.attemptVersion(), child.attemptVersion()); assertEquals(ChildState.UNRESOLVED, child.state());
        assertEquals(UnresolvedReason.MODEL_RESULT_UNKNOWN, child.reason()); assertFalse(child.callbackActive());
        assertNull(child.jobId()); assertNull(child.artifactId());
        assertEquals(StepStatus.BLOCKED, f.steps.step(recovered.token(), STEP.stepId()).orElseThrow().status());
        assertEquals("STEP_RESULT_UNKNOWN", f.steps.step(recovered.token(), STEP.stepId()).orElseThrow().reason());
        assertFalse(f.runs.mayDispatch(sent)); assertFalse(f.steps.mayExecute(step));
        assertEquals(StepStatus.BLOCKED, f.steps.refreshWaiting(recovered.token(), STEP.stepId()).status());
        assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_callback_recovery WHERE proof_code=?", Integer.class, ProcessLiveness.PROCESS_EXITED));
        var gateway = mock(ShortLinkBusinessGateway.class);
        new StatisticsSubmissionReconciler(f.runs, gateway).recover(recovered.token(), f.child.childId(), PRINCIPAL);
        verifyNoInteractions(gateway);
        assertEquals(1, f.vendorCalls.get(), "Process takeover cannot make a second model call");
        assertEquals(0, f.responses()); assertEquals(0, f.outputs()); f.exited();
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final RunToken token;
        final ArtifactMetadata input;
        final InvocationSpec invocation;
        final Approval approval;
        final ModelActionSpec action;
        final ChildSpec child;
        final AtomicInteger vendorCalls = new AtomicInteger();

        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:model_ledger_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql")).execute(source);
            jdbc = new JdbcTemplate(source); transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, transactions, clock); steps = new JdbcCampaignStepStore(jdbc, transactions, clock);
            RunToken sourceRun = runs.createRun(new RunDefinition(OWNER, "source-session", "source-run", "source-plan", 1, "{}"));
            runs.prepareAction(sourceRun, new ActionSpec("source-action", "source-step", "TOOL", "metrics", "1", "{}"));
            runs.prepareChild(sourceRun, new ChildSpec("source-child", "source-action", ChildMode.SYNC, "source-request", new WireRequest("GET", "/fixture", "{}")));
            DispatchPermit sourceDispatch = runs.beginDispatch(sourceRun, "source-child");
            try {
                runs.publishReady(sourceDispatch, new ArtifactDraft("source-artifact", "Evidence", "evidence/v1", "scope-frozen", "periods-frozen",
                        "{\"collectionQuality\":\"UNKNOWN\"}", "{}", EXPIRY, INPUT_BODY));
            } finally { runs.callbackExited(sourceDispatch); }
            input = runs.inspectArtifact(OWNER, "source-artifact", ALLOW);
            token = steps.acquireRun(runs.createRun(frozen().definition(OWNER, "model-session")));
            steps.initialize(token, List.of(new StepSpec(STEP.stepId(), stepJson(), List.of(), Set.of("analysis"), Set.of("analysis"))));
            Identity identity = ModelInvocationRegistry.identity(token.definition(), STEP.stepId(), 1);
            invocation = new InvocationSpec(identity.invocationId(), 1, "test-model", "1", CONFIG, "campaign-explore", "1", "model-inputs",
                    REQUEST, Map.of("source", input), EXPIRY);
            approval = new ModelInvocationRegistry(List.of(new Contract("test-model", "1", CONFIG,
                    value -> value.policyRef().equals("campaign-explore") && value.policyVersion().equals("1")))).approve(invocation);
            action = new ModelActionSpec(identity.actionId(), STEP.stepId(), invocation.invocationId(), invocation.modelRef(), invocation.modelVersion(),
                    invocation.policyRef(), invocation.policyVersion(), stepJson());
            child = new ChildSpec(identity.childId(), action.actionId(), ChildMode.MODEL, identity.requestId(), null, null, invocation);
        }
        int responses() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_model_response", Integer.class); }
        int outputs() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id='model-run'", Integer.class); }
        void exited() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static String stepJson() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(STEP); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }

    private static FrozenCampaignRun frozen() {
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "model-plan", 1, "model-run", "model-inputs",
                List.of(new PlanSpec.Goal("goal", "Inspect frozen evidence", true, "Deliver supported analysis")), List.of(STEP));
        var assessment = new PlanningAssessment("model-plan", 1, "model-catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(STEP.stepId(), "analysis")))), List.of());
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet("model-inputs", "model-run", Map.of(), Map.of()), assessment);
    }
    private static ArtifactDraft fakeArtifact() {
        return new ArtifactDraft("fake-model-artifact", "Evidence", "evidence/v1", "scope-frozen", "periods-frozen", "{}", "{}", EXPIRY, "{}");
    }
    private static ProcessIdentity process(int pid) {
        return new ProcessIdentity(UUID.randomUUID().toString(), "model-test-process-domain", pid, NOW.minusSeconds(60).toEpochMilli() + pid);
    }
    private static final class MutableClock extends Clock {
        Instant current = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
}
