package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionCallTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real native Driver and Skill: only the external capacity receipt and job replies are scripted. */
@Timeout(60)
class SkillCapacityContinuationTest {
    private static final String CONSUME = "consume-selection";
    private static final PlanSpec.ExecutorRef READ = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "consume_selection", "1");
    private static final PlanSpec.Step NEXT = new PlanSpec.Step(CONSUME, List.of(), PlanSpec.ExecutionMode.FIXED, READ, null,
            List.of(STEP), Map.of("selectedEntities", PlanBinding.output(STEP, "selectedEntities"),
                    "selectionEvidence", PlanBinding.output(STEP, "selectionEvidence")), Map.of(), "consume-selection/v1");
    private static final Capability READER = new Capability(READ, new Signature(Map.of(
            "selectedEntities", new Port(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), true),
            "selectionEvidence", new Port(new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE), true)),
            "consume-selection/v1", Map.of(), Parameters.none()), false);

    @Test
    void mixedAcceptedAndCapacityDeferredJobsResumeTheSameCallTwiceWithoutResubmittingReadyEvidence() throws Exception {
        var f = new Fixture(Mode.MIXED);
        var first = f.runtime();
        assertEquals(1, first.graph().advance().advancedSteps());
        assertEquals(StepStatus.WAITING, f.step().status());
        CallRecord originalCall = f.call();
        assertEquals(CampaignSkillInvocationStore.State.WAITING, f.invocation().state());
        assertEquals(1, originalCall.attemptVersion()); assertFalse(originalCall.callbackActive());
        ChildRecord accepted = f.child("baseline"), capacity = f.child("target");
        assertEquals(ChildState.WAITING, accepted.state()); assertNotNull(accepted.jobId());
        assertEquals(ChildState.PREPARED, capacity.state()); assertNull(capacity.jobId());
        assertEquals(UnresolvedReason.QUERY_CAPACITY_EXHAUSTED, capacity.reason());
        SubmissionDeferral proof = f.base.runs.submissionDeferral(f.token, capacity.spec().childId()).orElseThrow();
        assertEquals(2, f.base.gateway.submits); assertEquals(1, f.base.gateway.accepted.size());
        assertEquals(1, f.model.calls.get()); assertEquals(0, f.continuations.get());
        first.driver().refreshWaiting(); assertEquals(0, first.graph().advance().advancedSteps());
        assertEquals(proof, f.base.runs.submissionDeferral(f.token, capacity.spec().childId()).orElseThrow());

        // Even a due capacity receipt cannot bypass the still-pending accepted job.
        f.clock.set(proof.retryNotBeforeMillis()); f.reopen();
        var dueButWaiting = f.runtime(); dueButWaiting.driver().refreshWaiting();
        assertEquals(StepStatus.WAITING, f.step().status()); assertEquals(0, dueButWaiting.graph().advance().advancedSteps());
        assertEquals(2, f.base.gateway.submits); assertEquals(1, f.call().attemptVersion());
        f.receive(accepted);
        ChildRecord readyA = f.base.runs.child(f.token, accepted.spec().childId()).orElseThrow();
        Artifact originalArtifact = f.base.runs.readArtifact(OWNER, readyA.artifactId(), f.base.auth);
        assertEquals(accepted.spec(), readyA.spec()); assertEquals(accepted.jobId(), readyA.jobId());
        var resumeB = f.runtime(); resumeB.driver().refreshWaiting();
        assertEquals(StepStatus.READY, f.step().status());
        assertEquals(1, resumeB.graph().advance().advancedSteps());
        assertEquals(StepStatus.WAITING, f.step().status());
        assertEquals(CampaignSkillInvocationStore.State.WAITING, f.invocation().state());
        assertEquals(2, f.call().attemptVersion()); assertEquals(1, f.continuations.get());
        assertEquals(1, f.model.calls.get()); assertEquals(3, f.base.gateway.submits);
        assertEquals(2, f.base.gateway.accepted.size()); assertEquals(1, f.base.gateway.pageReads);
        assertEquals(capacity.spec(), f.child("target").spec());
        assertEquals(ChildState.WAITING, f.child("target").state());
        assertEquals(readyA, f.base.runs.child(f.token, accepted.spec().childId()).orElseThrow());
        assertEquals(originalArtifact, f.base.runs.readArtifact(OWNER, readyA.artifactId(), f.base.auth));
        assertEquals(1, f.attempts(accepted.spec().requestId())); assertEquals(2, f.attempts(capacity.spec().requestId()));
        assertEquals(f.base.gateway.submissionAttempts.get(1), f.base.gateway.submissionAttempts.get(2), "B keeps its entire original frozen request");
        resumeB.driver().refreshWaiting(); assertEquals(0, resumeB.graph().advance().advancedSteps());

        f.reopen(); f.receive(f.child("target"));
        var finish = f.runtime(); finish.driver().refreshWaiting();
        assertEquals(2, finish.graph().advance().advancedSteps());
        f.assertSucceeded(3, 2);
        assertEquals(3, f.call().attemptVersion()); assertEquals(2, f.continuations.get());
        assertEquals(originalCall.spec(), f.call().spec());
        assertEquals(readyA, f.base.runs.child(f.token, accepted.spec().childId()).orElseThrow());
        assertEquals(originalArtifact, f.base.runs.readArtifact(OWNER, readyA.artifactId(), f.base.auth));
        f.reopen(); var again = f.runtime(); again.driver().refreshWaiting();
        assertEquals(0, again.graph().advance().advancedSteps()); f.assertSucceeded(3, 2);
        f.assertExited();
    }

    @Test
    void unknownSubmissionMissingProofAndChangedCapacityProofNeverCreateAnOrdinaryContinuation() throws Exception {
        for (Mode mode : List.of(Mode.UNKNOWN, Mode.MISSING_PROOF, Mode.CHANGED_PROOF)) {
            var f = new Fixture(mode);
            assertEquals(1, f.runtime().graph().advance().advancedSteps());
            ChildRecord accepted = f.child("baseline"), other = f.child("target");
            assertEquals(ChildState.WAITING, accepted.state()); assertNotNull(accepted.jobId());
            assertEquals(2, f.base.gateway.submits); assertEquals(1, f.base.gateway.accepted.size());
            assertEquals(1, f.model.calls.get()); assertEquals(1, f.call().attemptVersion());
            if (mode == Mode.CHANGED_PROOF) {
                assertEquals(CampaignSkillInvocationStore.State.WAITING, f.invocation().state());
                SubmissionDeferral proof = f.base.runs.submissionDeferral(f.token, other.spec().childId()).orElseThrow();
                f.clock.set(proof.retryNotBeforeMillis()); f.reopen(); f.receive(accepted);
                Artifact saved = f.base.runs.readArtifact(OWNER, f.child("baseline").artifactId(), f.base.auth);
                // A syntactically valid replacement differs from the exact proof frozen by the Skill wait.
                assertEquals(1, f.base.base.jdbc.update("UPDATE campaign_submission_deferral SET capacity_kind='RESULT_STORAGE' WHERE run_id=? AND child_id=?",
                        RUN, other.spec().childId()));
                assertEquals(CapacityKind.RESULT_STORAGE, f.base.runs.submissionDeferral(f.token, other.spec().childId()).orElseThrow().kind());
                var changed = f.runtime(); changed.driver().refreshWaiting();
                changed.graph().advance();
                assertNotEquals(StepStatus.SUCCEEDED, f.step().status());
                assertEquals(saved, f.base.runs.readArtifact(OWNER, saved.metadata().ref().artifactId(), f.base.auth));
            } else {
                assertEquals(ChildState.UNRESOLVED, other.state());
                assertEquals(UnresolvedReason.SUBMISSION_UNRESOLVED, other.reason());
                assertTrue(f.base.runs.submissionDeferral(f.token, other.spec().childId()).isEmpty());
                assertNotEquals(CampaignSkillInvocationStore.State.WAITING, f.invocation().state());
                assertNotEquals(CampaignSkillInvocationStore.State.DEFERRED, f.invocation().state());
                f.clock.set(CLOCK.millis() + 60_000); f.reopen();
                var resumed = f.runtime(); resumed.driver().refreshWaiting();
                assertEquals(0, resumed.graph().advance().advancedSteps());
                assertEquals(accepted, f.base.runs.child(f.token, accepted.spec().childId()).orElseThrow());
            }
            assertEquals(1, f.call().attemptVersion()); assertEquals(0, f.continuations.get(), mode.name());
            assertEquals(2, f.base.gateway.submits); assertEquals(0, f.base.gateway.recoveries);
            assertEquals(1, f.model.calls.get()); assertEquals(0, f.base.finalCount());
            assertTrue(f.step().outputs().isEmpty()); assertEquals(StepStatus.PENDING, f.base.steps.step(f.token, CONSUME).orElseThrow().status());
            assertEquals(0, f.consumed.get()); f.assertExited();
        }
    }

    @Test
    void pureCapacityWithoutAnyAcceptedJobStaysDeferredUntilDueThenWaitsForRealOriginalJobs() throws Exception {
        var f = new Fixture(Mode.PURE);
        assertEquals(1, f.runtime().graph().advance().advancedSteps());
        assertEquals(StepStatus.BLOCKED, f.step().status()); assertEquals("REMOTE_CAPACITY", f.step().reason());
        assertEquals(CampaignSkillInvocationStore.State.DEFERRED, f.invocation().state());
        assertEquals(0, f.base.gateway.accepted.size()); assertEquals(2, f.base.gateway.submits);
        var deferred = f.base.children(f.token).stream().filter(child -> child.spec().mode() == ChildMode.ASYNC).toList();
        assertEquals(2, deferred.size());
        assertTrue(deferred.stream().allMatch(child -> child.state() == ChildState.PREPARED && child.jobId() == null));
        long due = deferred.stream().mapToLong(child -> f.base.runs.submissionDeferral(f.token, child.spec().childId()).orElseThrow().retryNotBeforeMillis()).max().orElseThrow();
        f.clock.set(due - 1); f.reopen();
        var early = f.runtime(); early.driver().refreshWaiting(); assertEquals(0, early.graph().advance().advancedSteps());
        assertEquals(2, f.base.gateway.submits); assertEquals(0, f.continuations.get()); assertEquals(1, f.model.calls.get());
        f.clock.set(due);
        var dueRuntime = f.runtime(); dueRuntime.driver().refreshWaiting();
        assertEquals(StepStatus.READY, f.step().status()); assertEquals(1, dueRuntime.graph().advance().advancedSteps());
        assertEquals(StepStatus.WAITING, f.step().status()); assertEquals(CampaignSkillInvocationStore.State.WAITING, f.invocation().state());
        assertEquals(4, f.base.gateway.submits); assertEquals(2, f.base.gateway.accepted.size()); assertEquals(2, f.call().attemptVersion());
        assertEquals(1, f.model.calls.get()); assertEquals(0, f.base.gateway.pageReads);
        deferred.forEach(original -> {
            assertEquals(original.spec(), f.base.runs.child(f.token, original.spec().childId()).orElseThrow().spec());
            assertEquals(2, f.attempts(original.spec().requestId()));
        });
        f.reopen(); f.receive(f.child("baseline")); f.receive(f.child("target"));
        var finish = f.runtime(); finish.driver().refreshWaiting(); assertEquals(2, finish.graph().advance().advancedSteps());
        f.assertSucceeded(4, 2); assertEquals(3, f.call().attemptVersion()); assertEquals(2, f.continuations.get());
        f.assertExited();
    }

    private enum Mode { MIXED, UNKNOWN, MISSING_PROOF, CHANGED_PROOF, PURE }
    private record Runtime(PersistentPlanDriver driver, NativePlanGraph graph) {}
    private static ToolResult capacity() { return new ToolResult(false, Map.of("code", "QUERY_CAPACITY_EXHAUSTED",
            "admitted", false, "capacityKind", "ACTIVE_EXECUTION"), "capacity unavailable"); }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final CallFixture base;
        final CampaignExplorationCandidateStore candidates;
        final NativeDeclineSelectionSkillTest.SkillModel model;
        final AtomicInteger continuations = new AtomicInteger(), consumed = new AtomicInteger();
        RunToken token;
        Fixture(Mode mode) throws Exception {
            base = new CallFixture(false, List.of(new PlanSpec.CriterionUse("supported-evidence", Map.of())), NEXT, READER, clock, true);
            token = base.token;
            ExplorationCandidateAssessmentTest.migrate(base);
            var criteria = new CompletionCriterionRegistry(List.of(DeclineSelectionCompletionCriteria.coverage("decline-explore", "1",
                    "supported-evidence", new JdbcCampaignDeclineSelectionStore(base.base.jdbc, base.base.transactions, clock, base.runs), base.auth)));
            candidates = new JdbcCampaignExplorationCandidateStore(base.base.jdbc, base.base.transactions, clock,
                    base.runs, base.steps, base.models, base.catalog, base.contracts, criteria, base.auth);
            model = new NativeDeclineSelectionSkillTest.SkillModel(base, ExplorationCandidateAssessmentTest::completeCandidate);
            Map<String, Integer> attempts = new HashMap<>();
            base.gateway.submissionScript = request -> {
                String start = (String) request.get("startDate"); int attempt = attempts.merge(start, 1, Integer::sum);
                if ("2026-09-02".equals(start) && mode == Mode.UNKNOWN) throw new IllegalStateException("UNKNOWN_EXTERNAL_ACK");
                if ("2026-09-02".equals(start) && mode == Mode.MISSING_PROOF)
                    return new ToolResult(false, Map.of("code", "QUERY_CAPACITY_EXHAUSTED", "capacityKind", "ACTIVE_EXECUTION"), "missing admitted receipt");
                if (attempt == 1 && (mode == Mode.PURE || "2026-09-02".equals(start))) return capacity();
                return null;
            };
        }
        Runtime runtime() throws Exception {
            RunToken current = token;
            var policy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                    if (STEP.equals(step.stepId())) { assertEquals(base.scopeRef, inputs.value("scope")); assertEquals(PAIR, inputs.value("periods")); }
                    else {
                        assertEquals(DeclineSelectionPublisher.SELECTED_TYPE, inputs.artifact("selectedEntities").metadata().ref().type());
                        assertEquals(DeclineSelectionPublisher.EVIDENCE_TYPE, inputs.artifact("selectionEvidence").metadata().ref().type());
                    }
                }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertEquals(STEP.equals(step.stepId()) ? OUTPUTS : Set.of(), outputs.keySet());
                }
            };
            var reader = new PersistentPlanDriver.FixedExecutor(READ, policy, context -> {
                context.requireCurrent(); assertEquals(StepStatus.SUCCEEDED, base.steps.step(current, STEP).orElseThrow().status());
                var selected = context.inputs().artifact("selectedEntities").metadata();
                var evidence = context.inputs().artifact("selectionEvidence").metadata();
                var pair = new JdbcCampaignDeclineSelectionStore(base.base.jdbc, base.base.transactions, clock, base.runs)
                        .inspectPair(OWNER, selected.ref().artifactId(), evidence.ref().artifactId(), base.auth);
                assertEquals(selected, pair.selectedEntities()); assertEquals(evidence, pair.selectionEvidence());
                consumed.incrementAndGet(); return PersistentPlanDriver.Result.succeeded(Map.of());
            });
            var execute = new PersistentExplorationExecutor((step, inputs, permit, authorized) -> {
                assertTrue(authorized.getAsBoolean());
                var definition = DeclineSelectionExplorationSkill.definition();
                var config = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIGURATION, null,
                        List.of(new ModelInvocationRegistry.ToolDefinition(definition.name(), definition.description(), JSON.readTree(definition.inputSchema()))),
                        Map.of("scope", base.runs.inspectArtifact(OWNER, base.scopeArtifact, base.auth)), Instant.ofEpochMilli(EXPIRY));
                var ledger = new JdbcExplorationLedger(base.base.jdbc, base.base.transactions, clock, base.runs, base.steps, base.calls,
                        permit, base.models, config, Map.of(FrozenDeclineSelection.REF.name(), FrozenDeclineSelection.REF), base.auth,
                        ExplorationBudgetPolicy.defaults(), new DeclineSelectionArtifactProjection(base.runs,
                        new JdbcCampaignDeclineSelectionStore(base.base.jdbc, base.base.transactions, clock, base.runs)), base.invocations, candidates);
                var adapter = NativeDeclineSelectionSkillTest.nativeAdapter(base, ledger, model);
                return new PersistentExplorationExecutor.Session(ledger, adapter, NativeDeclineSelectionSkillTest.PROMPT, (owner, callId, version) -> {
                    continuations.incrementAndGet(); model.completion = base.adapter().continueInvocation(owner, callId, version);
                    model.call = base.calls.call(owner.runToken(), callId).orElseThrow();
                    assertFalse(model.call.callbackActive());
                });
            });
            var driver = new PersistentPlanDriver(current, base.runs, base.steps, base.catalog, base.contracts, List.of(reader),
                    (caller, inputs) -> OWNER.equals(caller) && base.allowed.get(), base.auth,
                    (caller, type, value) -> OWNER.equals(caller) && base.allowed.get()
                            && ("ScopeRef".equals(type.name()) ? base.scopeRef.equals(value) : "PeriodsRef".equals(type.name()) && PAIR.equals(value)),
                    List.of(new PersistentPlanDriver.ReactExecutor("decline-explore", "1", policy, execute)), candidates);
            return new Runtime(driver, driver.compile(new MemorySaver()));
        }
        void reopen() { assertExited(); token = base.steps.acquireRun(token); }
        ChildRecord child(String period) {
            String start = "baseline".equals(period) ? "2026-09-01" : "2026-09-02";
            return base.children(token).stream().filter(child -> child.spec().mode() == ChildMode.ASYNC)
                    .filter(child -> { try { return start.equals(JSON.readTree(child.spec().wire().bodyJson()).path("startDate").asText()); }
                    catch (Exception invalid) { throw new IllegalStateException(invalid); } }).findFirst().orElseThrow();
        }
        void receive(ChildRecord child) {
            var delegate = base.adapter(); var target = delegate.resultTargets(token).get(child.spec().childId());
            var result = new StatisticsJobResultReceiver(base.runs, base.base.results, base.gateway, clock, 1)
                    .receive(token, child.spec().childId(), PRINCIPAL, target, () -> delegate.reauthorize(token, child.spec().childId()));
            assertEquals(StatisticsJobResultReceiver.Outcome.READY, result.outcome(), result.code());
        }
        StepRecord step() { return base.steps.step(token, STEP).orElseThrow(); }
        CallRecord call() { return NativeDeclineSelectionSkillTest.onlyCall(base, token); }
        CampaignSkillInvocationStore.InvocationRecord invocation() { return base.invocations.invocation(token, call().spec().callId()).orElseThrow(); }
        long attempts(String request) { return base.gateway.submissionAttempts.stream().filter(value -> request.equals(value.get("requestId"))).count(); }
        void assertSucceeded(int submits, int pages) {
            assertEquals(StepStatus.SUCCEEDED, step().status()); assertEquals(OUTPUTS, step().outputs().keySet());
            assertEquals(StepStatus.SUCCEEDED, base.steps.step(token, CONSUME).orElseThrow().status());
            assertEquals(CampaignSkillInvocationStore.State.COMPLETED, invocation().state()); assertEquals(2, base.finalCount());
            assertEquals(2, model.calls.get()); assertEquals(submits, base.gateway.submits); assertEquals(pages, base.gateway.pageReads);
            assertEquals(0, base.gateway.recoveries); assertEquals(1, consumed.get());
        }
        void assertExited() { ExplorationCandidateAssessmentTest.exited(base); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong value = new AtomicLong(CLOCK.millis());
        void set(long millis) { assertTrue(millis >= value.get(), "Fixture time only advances"); value.set(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC fixture"); return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(value.get()); }
        @Override public long millis() { return value.get(); }
    }
}
