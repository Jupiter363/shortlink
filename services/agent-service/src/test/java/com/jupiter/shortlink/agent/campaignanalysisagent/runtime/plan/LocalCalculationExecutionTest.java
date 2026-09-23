package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class LocalCalculationExecutionTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Instant EXPIRY = NOW.plusSeconds(3600);
    private static final String IMPLEMENTATION = "a".repeat(64);
    private static final String INPUT_BODY = "{\"baseline\":13,\"target\":3}";
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> true;
    private static final Set<String> OUTPUTS = Set.of("selectedEntities", "selectionEvidence");
    private static final PlanSpec.Step STEP = new PlanSpec.Step("derive", List.of("goal"),
            PlanSpec.ExecutionMode.FIXED, new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "decline-selection", "1"),
            null, List.of(), Map.of(), Map.of(), "local-pair/v1");

    @Test
    void checksLocalPublicationBindingWithoutReadingPayloadsOrAuthorizingTheirContents() throws Exception {
        Scenario scenario = scenario("binding-only");
        Fixture fixture = scenario.fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        StepPermit permit = steps.beginStep(scenario.token(), STEP.stepId());
        Map<String, ArtifactRef> published;
        try (CampaignStepExecution context = context(scenario.token(), permit, runs, steps)) {
            published = context.local(scenario.child(), scenario.approval(), ALLOW, boundary -> drafts(scenario.approval()));
        } finally {
            steps.callbackExited(permit);
        }
        ArtifactRef ref = published.get("selectedEntities");
        assertTrue(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        assertFalse(runs.isLocalOutputBound(scenario.token(), "missing-child", ref));
        assertFalse(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(),
                new ArtifactRef(ref.artifactId(), ref.type(), ref.schemaVersion(), "0".repeat(64),
                        ref.scopeRef(), ref.periodsRef(), ref.expiresAt())));

        // Identity inspection must not trigger recursive source/output payload reads. Full reads
        // retain their original corruption rejection and cannot use this predicate as authorization.
        fixture.jdbc().update("UPDATE campaign_artifact_payload SET payload_json='corrupted' WHERE artifact_id IN (?,?)",
                scenario.input().ref().artifactId(), ref.artifactId());
        assertTrue(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        assertThrows(IllegalArgumentException.class,
                () -> runs.localOutputs(scenario.token(), scenario.child().childId(), ALLOW));

        fixture.jdbc().update("UPDATE campaign_child_ledger SET child_state='PREPARED' WHERE run_id=? AND child_id=?",
                scenario.token().definition().runId(), scenario.child().childId());
        assertFalse(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        fixture.jdbc().update("UPDATE campaign_child_ledger SET child_state='READY' WHERE run_id=? AND child_id=?",
                scenario.token().definition().runId(), scenario.child().childId());
        fixture.clock().current = EXPIRY;
        assertFalse(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        fixture.clock().current = NOW;

        fixture.jdbc().update("UPDATE campaign_artifact SET subject_name='other-owner' WHERE artifact_id=?", ref.artifactId());
        assertFalse(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        fixture.jdbc().update("UPDATE campaign_artifact SET subject_name=? WHERE artifact_id=?", OWNER.subject(), ref.artifactId());
        fixture.jdbc().update("UPDATE campaign_local_output SET artifact_id=? WHERE child_id=? AND output_name='selectedEntities'",
                scenario.input().ref().artifactId(), scenario.child().childId());
        assertFalse(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        fixture.jdbc().update("UPDATE campaign_local_output SET artifact_id=? WHERE child_id=? AND output_name='selectedEntities'",
                ref.artifactId(), scenario.child().childId());
        fixture.jdbc().update("DELETE FROM campaign_local_output WHERE child_id=? AND output_name='selectionEvidence'",
                scenario.child().childId());
        assertFalse(runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        RunToken next = runs.advance(scenario.token());
        assertThrows(IllegalStateException.class,
                () -> runs.isLocalOutputBound(scenario.token(), scenario.child().childId(), ref));
        assertFalse(runs.isLocalOutputBound(next, scenario.child().childId(), ref));
    }

    @Test
    void rollsBackBothOutputsThenReplaysFrozenInputsAndReusesReadyOutputsAcrossParentRecovery() throws Exception {
        Scenario scenario = scenario("recovery");
        Fixture fixture = scenario.fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        AtomicInteger computations = new AtomicInteger();
        Map<String, ArtifactDraft> drafts = drafts(scenario.approval());
        assertDoesNotThrow(() -> scenario.approval().validateOutputs(drafts));
        // Both the context and store use this immutable map: choose its second INSERT, not a
        // presumed hash iteration order. The failure is a DB constraint after valid first output writes.
        String secondId = new ArrayList<>(Map.copyOf(drafts).values()).get(1).artifactId();
        fixture.jdbc().execute("ALTER TABLE campaign_artifact_payload ADD CONSTRAINT reject_second_local_payload "
                + "CHECK (artifact_id <> '" + secondId + "')");
        StepPermit first = steps.beginStep(scenario.token(), STEP.stepId());
        try (CampaignStepExecution context = context(scenario.token(), first, runs, steps)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> context.local(scenario.child(), scenario.approval(), ALLOW,
                    boundary -> {
                        computations.incrementAndGet();
                        assertEquals(INPUT_BODY, boundary.readInput("source").payloadJson());
                        return drafts;
                    }));
            assertEquals("LEDGER_IDENTITY_CONFLICT", failure.getMessage(), "The database constraint, not schema validation, failed");
        } finally {
            steps.callbackExited(first);
        }
        assertNoPublishedOutputs(scenario);
        ChildRecord unknown = runs.child(scenario.token(), scenario.child().childId()).orElseThrow();
        assertEquals(ChildState.UNRESOLVED, unknown.state());
        assertEquals(UnresolvedReason.LOCAL_RESULT_UNKNOWN, unknown.reason());
        assertFalse(unknown.callbackActive());
        assertEquals(StepStatus.BLOCKED, steps.step(scenario.token(), STEP.stepId()).orElseThrow().status());
        assertEquals("STEP_RESULT_UNKNOWN", steps.step(scenario.token(), STEP.stepId()).orElseThrow().reason());
        assertCallbacksExited(fixture);
        ShortLinkBusinessGateway gateway = mock(ShortLinkBusinessGateway.class);
        var reconciliation = new StatisticsSubmissionReconciler(runs, gateway)
                .recover(scenario.token(), scenario.child().childId(), PRINCIPAL);
        assertEquals(StatisticsSubmissionReconciler.Outcome.UNRESOLVED, reconciliation.outcome());
        assertEquals("LOCAL_RESULT_UNKNOWN", reconciliation.code());
        verifyNoInteractions(gateway);

        fixture.jdbc().execute("ALTER TABLE campaign_artifact_payload DROP CONSTRAINT reject_second_local_payload");
        CampaignRunStore reopenedRuns = fixture.runs();
        CampaignStepStore reopenedSteps = fixture.steps();
        RunToken replayToken = reopenedSteps.acquireRun(scenario.token());
        assertEquals(StepStatus.READY, reopenedSteps.refreshLocalReplay(replayToken, STEP.stepId(),
                Map.of(scenario.child().childId(), scenario.approval()), ALLOW).status());
        StepPermit replay = reopenedSteps.beginStep(replayToken, STEP.stepId());
        Map<String, ArtifactRef> published;
        try (CampaignStepExecution context = context(replayToken, replay, reopenedRuns, reopenedSteps)) {
            published = context.local(scenario.child(), scenario.approval(), ALLOW, boundary -> {
                computations.incrementAndGet();
                assertEquals(scenario.input(), boundary.readInput("source").metadata());
                return drafts;
            });
            assertEquals(OUTPUTS, published.keySet());
            assertEquals(ChildState.READY, reopenedRuns.child(replayToken, scenario.child().childId()).orElseThrow().state());
            InvocationSpec changed = changed(scenario.approval().invocation(), "decline-selection", IMPLEMENTATION,
                    "{\"metric\":\"UV\"}");
            Approval changedApproval = registry().approve(changed);
            ChildSpec rebound = new ChildSpec(scenario.child().childId(), scenario.child().actionId(), ChildMode.LOCAL,
                    scenario.child().requestId(), null, changed);
            assertThrows(IllegalStateException.class, () -> context.local(rebound, changedApproval, ALLOW,
                    boundary -> { fail("A stable child cannot be rebound to different parameters"); return drafts; }));
            // Deliberately stop before parent settlement: the output set, not a graph checkpoint,
            // must suffice for recovery without recomputing the local calculation.
        } finally {
            reopenedSteps.callbackExited(replay);
        }
        assertEquals(2, computations.get());
        assertCallbacksExited(fixture);
        CampaignRunStore finalRuns = fixture.runs();
        CampaignStepStore finalSteps = fixture.steps();
        RunToken finalToken = finalSteps.acquireRun(replayToken);
        assertEquals(StepStatus.READY, finalSteps.refreshWaiting(finalToken, STEP.stepId()).status());
        StepPermit finalPermit = finalSteps.beginStep(finalToken, STEP.stepId());
        try (CampaignStepExecution context = context(finalToken, finalPermit, finalRuns, finalSteps)) {
            Map<String, ArtifactRef> reused = context.local(scenario.child(), scenario.approval(), ALLOW,
                    boundary -> { fail("READY output sets must never recompute"); return drafts; });
            assertEquals(published, reused);
            finalSteps.settle(finalPermit, StepStatus.SUCCEEDED, Map.of(
                    "selectedEntities", reused.get("selectedEntities").artifactId(),
                    "selectionEvidence", reused.get("selectionEvidence").artifactId()), null, ALLOW);
        } finally {
            finalSteps.callbackExited(finalPermit);
        }
        assertEquals(StepStatus.SUCCEEDED, finalSteps.step(finalToken, STEP.stepId()).orElseThrow().status());
        assertEquals(OUTPUTS, finalSteps.step(finalToken, STEP.stepId()).orElseThrow().outputs().keySet());
        assertEquals(2, computations.get());
        assertEquals(2, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_local_output", Integer.class));
        published.forEach((name, ref) -> {
            assertEquals(EXPIRY, ref.expiresAt());
            assertEquals(CampaignRunStore.sha256(drafts.get(name).payloadJson()), ref.payloadHash());
            Artifact output = finalRuns.readArtifact(OWNER, ref.artifactId(), ALLOW);
            assertEquals(ref, output.metadata().ref());
            assertEquals(scenario.child().childId(), output.metadata().childId());
            assertEquals(drafts.get(name).payloadJson(), output.payloadJson());
        });
        ChildRecord ready = finalRuns.child(finalToken, scenario.child().childId()).orElseThrow();
        assertNull(ready.spec().wire());
        assertNull(ready.jobId());
        assertEquals(scenario.approval().invocation().hash(), ready.spec().localInvocation().hash());
        assertCallbacksExited(fixture);
    }

    @Test
    void fencesActiveOrCancelledAttemptsAndDoesNotPublishRevokedExpiredOrInvalidLocalResults() throws Exception {
        Scenario cancelled = scenario("cancelled");
        CampaignRunStore runs = cancelled.fixture().runs();
        CampaignStepStore steps = cancelled.fixture().steps();
        StepPermit active = steps.beginStep(cancelled.token(), STEP.stepId());
        try (CampaignStepExecution context = context(cancelled.token(), active, runs, steps)) {
            assertThrows(SecurityException.class, () -> context.local(cancelled.child(), cancelled.approval(), ALLOW,
                    boundary -> {
                        assertEquals(INPUT_BODY, boundary.readInput("source").payloadJson());
                        ChildRecord child = runs.child(cancelled.token(), cancelled.child().childId()).orElseThrow();
                        assertTrue(child.callbackActive());
                        assertThrows(IllegalStateException.class, () -> runs.advance(cancelled.token()));
                        assertThrows(IllegalStateException.class, () -> runs.beginLocalReplay(cancelled.token(),
                                cancelled.child().childId(), cancelled.approval(), ALLOW));
                        assertEquals(StepStatus.RUNNING, steps.refreshLocalReplay(cancelled.token(), STEP.stepId(),
                                Map.of(cancelled.child().childId(), cancelled.approval()), ALLOW).status());
                        DispatchPermit live = new DispatchPermit(cancelled.token(), child.spec().childId(),
                                child.attemptId(), child.attemptVersion(), child.purpose());
                        StepPermit staleStep = new StepPermit(cancelled.token(), STEP.stepId(), "old-attempt",
                                active.attemptVersion());
                        assertThrows(IllegalStateException.class, () -> runs.publishLocalReady(staleStep, live,
                                cancelled.approval(), drafts(cancelled.approval()), ALLOW));
                        assertNoPublishedOutputs(cancelled);
                        runs.cancel(cancelled.token());
                        assertThrows(IllegalStateException.class, () -> runs.publishLocalReady(active, live,
                                cancelled.approval(), drafts(cancelled.approval()), ALLOW));
                        boundary.requireCurrent();
                        return drafts(cancelled.approval());
                    }));
        } finally {
            steps.callbackExited(active);
        }
        assertEquals(RunStatus.CANCELLED, runs.loadRun(OWNER, cancelled.token().definition().runId()).orElseThrow().status());
        assertNoPublishedOutputs(cancelled);
        assertCallbacksExited(cancelled.fixture());

        for (boolean expire : List.of(false, true)) {
            Scenario restricted = scenario(expire ? "expired" : "revoked");
            CampaignRunStore restrictedRuns = restricted.fixture().runs();
            CampaignStepStore restrictedSteps = restricted.fixture().steps();
            AtomicBoolean readable = new AtomicBoolean(true);
            ArtifactAuthorizer authorizer = (caller, metadata) -> readable.get();
            StepPermit permit = restrictedSteps.beginStep(restricted.token(), STEP.stepId());
            try (CampaignStepExecution context = context(restricted.token(), permit, restrictedRuns, restrictedSteps)) {
                assertThrows(SecurityException.class, () -> context.local(restricted.child(), restricted.approval(), authorizer,
                        boundary -> {
                            assertEquals(INPUT_BODY, boundary.readInput("source").payloadJson());
                            if (expire) restricted.fixture().clock().current = restricted.input().ref().expiresAt();
                            else readable.set(false);
                            return drafts(restricted.approval());
                        }));
            } finally {
                restrictedSteps.callbackExited(permit);
            }
            assertNoPublishedOutputs(restricted);
            assertCallbacksExited(restricted.fixture());
            assertThrows(SecurityException.class, () -> restrictedSteps.refreshLocalReplay(restricted.token(), STEP.stepId(),
                    Map.of(restricted.child().childId(), restricted.approval()), authorizer));
        }

        Scenario invalid = scenario("invalid");
        InvocationSpec original = invalid.approval().invocation();
        assertThrows(IllegalArgumentException.class, () -> registry().approve(changed(original, "not-registered",
                IMPLEMENTATION, original.parametersJson())));
        assertThrows(IllegalArgumentException.class, () -> registry().approve(changed(original, original.contractName(),
                "b".repeat(64), original.parametersJson())));
        CampaignRunStore invalidRuns = invalid.fixture().runs();
        CampaignStepStore invalidSteps = invalid.fixture().steps();
        assertTrue(invalidRuns.children(invalid.token()).isEmpty());
        Map<String, ArtifactDraft> invalidDrafts = Map.of(
                "selectedEntities", drafts(invalid.approval()).get("selectedEntities"),
                "selectionEvidence", draft(invalid.approval(), "selectionEvidence", "{\"delta\":\"unknown\"}"));
        assertThrows(IllegalArgumentException.class, () -> invalid.approval().validateOutputs(invalidDrafts));
        StepPermit invalidPermit = invalidSteps.beginStep(invalid.token(), STEP.stepId());
        try (CampaignStepExecution context = context(invalid.token(), invalidPermit, invalidRuns, invalidSteps)) {
            assertThrows(CampaignStepExecution.LocalResultInvalid.class,
                    () -> context.local(invalid.child(), invalid.approval(), ALLOW, boundary -> invalidDrafts));
        } finally {
            invalidSteps.callbackExited(invalidPermit);
        }
        assertEquals(UnresolvedReason.LOCAL_RESULT_INVALID,
                invalidRuns.child(invalid.token(), invalid.child().childId()).orElseThrow().reason());
        assertEquals(StepStatus.BLOCKED, invalidSteps.refreshLocalReplay(invalid.token(), STEP.stepId(),
                Map.of(invalid.child().childId(), invalid.approval()), ALLOW).status());
        assertNoPublishedOutputs(invalid);
        assertCallbacksExited(invalid.fixture());

        Scenario interrupted = scenario("dead-owner");
        Fixture recoveryFixture = interrupted.fixture();
        ProcessIdentity oldProcess = new ProcessIdentity(UUID.randomUUID().toString(), "local-test-domain", 11001,
                NOW.minusSeconds(120).toEpochMilli());
        ProcessIdentity newProcess = new ProcessIdentity(UUID.randomUUID().toString(), "local-test-domain", 11002,
                NOW.minusSeconds(30).toEpochMilli());
        ProcessLiveness alive = identity -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE);
        var registered = new JdbcCampaignRecoveryStore(recoveryFixture.jdbc(), recoveryFixture.transactions(),
                recoveryFixture.clock(), oldProcess, alive).recover(interrupted.token());
        assertEquals(CampaignRecoveryStore.Outcome.ACQUIRED, registered.outcome());
        CampaignRunStore interruptedRuns = recoveryFixture.runs();
        CampaignStepStore interruptedSteps = recoveryFixture.steps();
        StepPermit lostStep = interruptedSteps.beginStep(registered.token(), STEP.stepId());
        interruptedRuns.prepareAction(registered.token(), new ActionSpec(interrupted.child().actionId(), STEP.stepId(),
                STEP.executor().kind().name(), STEP.executor().name(), STEP.executor().version(), FrozenCampaignRun.encode(STEP)));
        interruptedRuns.prepareLocalChild(registered.token(), interrupted.child(), interrupted.approval(), ALLOW);
        DispatchPermit lostChild = interruptedRuns.beginDispatch(registered.token(), interrupted.child().childId());
        // No callbackExited signal: the real JDBC takeover class must classify these unfinished
        // callbacks using a supplied death proof, not a test UPDATE of their state or reason.
        ProcessLiveness proof = identity -> identity.equals(oldProcess)
                ? new ProcessLiveness.Observation(ProcessLiveness.State.DEAD, ProcessLiveness.PROCESS_EXITED)
                : new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE);
        var acquired = new JdbcCampaignRecoveryStore(recoveryFixture.jdbc(), recoveryFixture.transactions(),
                recoveryFixture.clock(), newProcess, proof).recover(registered.token());
        assertEquals(CampaignRecoveryStore.Outcome.ACQUIRED, acquired.outcome());
        assertEquals(2, acquired.recoveredCallbacks());
        ChildRecord recovered = interruptedRuns.child(acquired.token(), interrupted.child().childId()).orElseThrow();
        assertNull(recovered.spec().wire());
        assertEquals(ChildState.UNRESOLVED, recovered.state());
        assertEquals(UnresolvedReason.LOCAL_RESULT_UNKNOWN, recovered.reason());
        assertFalse(recovered.callbackActive());
        assertThrows(IllegalStateException.class, () -> interruptedRuns.publishLocalReady(lostStep, lostChild,
                interrupted.approval(), drafts(interrupted.approval()), ALLOW));
        assertEquals(StepStatus.READY, interruptedSteps.refreshLocalReplay(acquired.token(), STEP.stepId(),
                Map.of(interrupted.child().childId(), interrupted.approval()), ALLOW).status());
        assertNoPublishedOutputs(interrupted);
        assertCallbacksExited(recoveryFixture);
    }

    private static Scenario scenario(String name) {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        RunToken source = runs.createRun(new RunDefinition(OWNER, "session-source", "source-" + name, "source-plan", 1, "{}"));
        runs.prepareAction(source, new ActionSpec("source-action", "collect", "TOOL", "metrics", "1", "{}"));
        runs.prepareChild(source, new ChildSpec("source-child", "source-action", ChildMode.SYNC,
                "source-request-" + name, new WireRequest("GET", "/statistics/query", "{}")));
        DispatchPermit dispatch = runs.beginDispatch(source, "source-child");
        try {
            runs.publishReady(dispatch, new ArtifactDraft("input-" + name, "LINK_METRICS", "link-metrics/v1",
                    "scope-frozen", "periods-frozen", "{\"status\":\"UNKNOWN\"}", "{}", EXPIRY, INPUT_BODY));
        } finally {
            runs.callbackExited(dispatch);
        }
        ArtifactMetadata input = runs.inspectArtifact(OWNER, "input-" + name, ALLOW);
        InvocationSpec invocation = new InvocationSpec("decline-selection", "1", IMPLEMENTATION, "{\"metric\":\"PV\"}",
                Map.of("source", input), Map.of(
                        "selectedEntities", new OutputBinding("selected-" + name, "SELECTED_ENTITIES", "selected/v1",
                                "scope-frozen", "periods-frozen"),
                        "selectionEvidence", new OutputBinding("evidence-" + name, "DECLINE_EVIDENCE", "decline/v1",
                                "scope-frozen", "periods-frozen")), EXPIRY);
        Approval approval = registry().approve(invocation);
        RunToken token = runs.createRun(new RunDefinition(OWNER, "session-local", "run-" + name, "local-plan", 1, "{}"));
        fixture.steps().initialize(token, List.of(new StepSpec(STEP.stepId(), FrozenCampaignRun.encode(STEP), List.of(), OUTPUTS, OUTPUTS)));
        ChildSpec child = new ChildSpec("local-child", "local-action", ChildMode.LOCAL, "local-request-" + name, null, invocation);
        return new Scenario(fixture, token, input, approval, child);
    }

    private static LocalCalculationRegistry registry() {
        return new LocalCalculationRegistry(List.of(new Contract("decline-selection", "1", IMPLEMENTATION,
                Map.of("source", new TypeContract("LINK_METRICS", "link-metrics/v1")),
                Map.of("selectedEntities", new TypeContract("SELECTED_ENTITIES", "selected/v1"),
                        "selectionEvidence", new TypeContract("DECLINE_EVIDENCE", "decline/v1")),
                parameters -> parameters.size() == 1 && Set.of("PV", "UV").contains(parameters.path("metric").asText()),
                outputs -> outputs.get("selectedEntities").path("linkIds").isArray()
                        && outputs.get("selectionEvidence").path("delta").isIntegralNumber())));
    }

    private static InvocationSpec changed(InvocationSpec original, String name, String hash, String parameters) {
        return new InvocationSpec(name, original.contractVersion(), hash, parameters,
                original.inputs(), original.outputs(), original.expiresAt());
    }

    private static Map<String, ArtifactDraft> drafts(Approval approval) {
        return Map.of("selectedEntities", draft(approval, "selectedEntities", "{\"linkIds\":[7]}"),
                "selectionEvidence", draft(approval, "selectionEvidence", "{\"delta\":-10}"));
    }

    private static ArtifactDraft draft(Approval approval, String output, String payload) {
        OutputBinding binding = approval.invocation().outputs().get(output);
        return new ArtifactDraft(binding.artifactId(), binding.type(), binding.schemaVersion(), binding.scopeRef(),
                binding.periodsRef(), "{\"status\":\"UNKNOWN\"}", "{}", approval.invocation().expiresAt(), payload);
    }

    private static CampaignStepExecution context(RunToken token, StepPermit permit, CampaignRunStore runs, CampaignStepStore steps) {
        assertEquals(token, permit.runToken());
        // This fixture tests the local execution boundary directly; it does not claim full Driver/Bindings integration.
        return new CampaignStepExecution(STEP, List.of(), null, permit, runs, steps, () -> true);
    }

    private static void assertNoPublishedOutputs(Scenario scenario) {
        JdbcTemplate jdbc = scenario.fixture().jdbc();
        String runId = scenario.token().definition().runId();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id=?", Integer.class, runId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact_payload WHERE artifact_id IN (?,?)", Integer.class,
                scenario.approval().invocation().outputs().get("selectedEntities").artifactId(),
                scenario.approval().invocation().outputs().get("selectionEvidence").artifactId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_local_output WHERE run_id=?", Integer.class, runId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND child_state='READY'",
                Integer.class, runId));
    }

    private static void assertCallbacksExited(Fixture fixture) {
        assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
    }

    private static Fixture fixture() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:local_calculation_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql")).execute(source);
        return new Fixture(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)), new MutableClock());
    }

    private record Scenario(Fixture fixture, RunToken token, ArtifactMetadata input, Approval approval, ChildSpec child) {}
    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, MutableClock clock) {
        CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, clock); }
        CampaignStepStore steps() { return new JdbcCampaignStepStore(jdbc, transactions, clock); }
    }
    private static final class MutableClock extends Clock {
        private Instant current = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
}
