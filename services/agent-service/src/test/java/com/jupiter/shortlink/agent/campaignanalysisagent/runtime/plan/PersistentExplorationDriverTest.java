package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionCallTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.ExplorationCandidateAssessmentTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.NativeDeclineSelectionSkillTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** Real outer native scan and native ReactAgent, with durable Skill continuation and candidate settlement. */
@Timeout(60)
class PersistentExplorationDriverTest {
    private static final String CONSUME = "consume-selection";
    private static final PlanSpec.ExecutorRef READ = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "read_selected", "1");
    private static final PlanSpec.Step DOWNSTREAM = new PlanSpec.Step(CONSUME, List.of(), PlanSpec.ExecutionMode.FIXED, READ, null,
            List.of(STEP), Map.of("selectedEntities", PlanBinding.output(STEP, "selectedEntities"),
                    "selectionEvidence", PlanBinding.output(STEP, "selectionEvidence")), Map.of(), "selection-consumer/v1");
    private static final Capability READER = new Capability(READ, new Signature(Map.of(
            "selectedEntities", new Port(new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE), true),
            "selectionEvidence", new Port(new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE), true)),
            "selection-consumer/v1", Map.of(), Parameters.none()), false);

    @Test
    void nativePlanDriverResumesTheOriginalSkillAndSettlesVerifiedOutputsBeforeAdvancingTheTypedConsumer() throws Exception {
        var f = new DriverFixture();
        assertNull(f.base.step, "Only the real Driver may begin this fixture's outer Step");
        assertTrue(f.base.steps.steps(f.base.token).isEmpty());
        var model = new SkillModel(f.base, ExplorationCandidateAssessmentTest::completeCandidate,
                PersistentExplorationDriverTest::assertCandidateInstructions);
        var initial = f.runtime(f.base.token, model);
        assertEquals(1, initial.graph().advance().advancedSteps());
        assertEquals(StepStatus.WAITING, f.step(f.base.token, STEP).status());
        assertEquals(StepStatus.PENDING, f.step(f.base.token, CONSUME).status());
        assertEquals(1, model.calls.get()); assertEquals(2, f.base.gateway.submits); assertEquals(0, f.consumptions.get());
        assertEquals(0, f.continuations.get());
        StepPermit originalPermit = f.reactPermits.get(0);
        var call = onlyCall(f.base, f.base.token);
        assertFalse(call.callbackActive());
        var originalJobs = f.base.children(f.base.token).stream().filter(c -> c.spec().mode() == ChildMode.ASYNC).toList();
        assertEquals(2, originalJobs.size());
        exited(f.base);
        assertEquals(0, initial.graph().advance().advancedSteps());
        assertEquals(1, model.calls.get()); assertEquals(2, f.base.gateway.submits);

        RunToken writer = f.base.steps.acquireRun(f.base.token);
        var delegate = f.base.adapter();
        var targets = delegate.resultTargets(writer);
        var receiver = new StatisticsJobResultReceiver(f.base.runs, f.base.base.results, f.base.gateway, CLOCK, 1);
        for (var entry : targets.entrySet()) {
            var received = receiver.receive(writer, entry.getKey(), PRINCIPAL, entry.getValue(),
                    () -> delegate.reauthorize(writer, entry.getKey()));
            assertEquals(StatisticsJobResultReceiver.Outcome.READY, received.outcome(), received.code());
        }
        assertEquals(1, model.calls.get()); assertEquals(0, f.base.finalCount(), "Receiving both jobs alone does not finish the Skill");
        var restored = f.runtime(writer, model); // Fresh Driver, inner/outer native savers, and durable ledger.
        restored.driver().refreshWaiting();
        assertEquals(StepStatus.READY, f.step(writer, STEP).status());
        assertEquals(2, restored.graph().advance().advancedSteps());
        assertEquals(StepStatus.SUCCEEDED, f.step(writer, STEP).status());
        assertEquals(StepStatus.SUCCEEDED, f.step(writer, CONSUME).status());
        assertEquals(2, model.calls.get()); assertEquals(2, f.base.gateway.submits); assertEquals(2, f.base.gateway.pageReads);
        assertEquals(0, f.base.gateway.recoveries); assertEquals(1, f.continuations.get()); assertEquals(1, f.consumptions.get());
        assertEquals(2, f.reactPermits.size());
        assertEquals(2, f.base.calls.call(writer, call.spec().callId()).orElseThrow().attemptVersion());
        var completion = f.base.invocations.readCompletion(writer, call.spec().callId(), f.base.auth);
        var assessment = f.candidates.assessment(writer, STEP).orElseThrow();
        assertEquals(CampaignExplorationCandidateStore.Verdict.COMPLETE, assessment.verdict());
        assertEquals(completion.outputs(), assessment.outputs());
        var stepOutputs = f.step(writer, STEP).outputs();
        assertEquals(OUTPUTS, stepOutputs.keySet()); assertEquals(stepOutputs, f.consumed.get());
        completion.outputs().forEach((name, ref) -> {
            assertEquals(ref.artifactId(), stepOutputs.get(name));
            assertEquals(ref, f.base.runs.inspectArtifact(OWNER, ref.artifactId(), f.base.auth).ref());
        });
        assertEquals(2, f.base.finalCount(), "Driver settlement preserves the original LOCAL producer artifacts");
        assertTrue(f.base.children(writer).stream().filter(c -> c.spec().mode() == ChildMode.LOCAL)
                .allMatch(c -> c.attemptVersion() == 1 && c.state() == ChildState.READY));
        for (var original : originalJobs) {
            var ready = f.base.runs.child(writer, original.spec().childId()).orElseThrow();
            assertEquals(original.spec(), ready.spec()); assertEquals(original.jobId(), ready.jobId());
        }
        assertThrows(SecurityException.class, () -> f.candidates.settleComplete(originalPermit));
        assertEquals(stepOutputs, f.step(writer, STEP).outputs());
        exited(f.base);

        RunToken freshWriter = f.base.steps.acquireRun(writer);
        var repeated = f.runtime(freshWriter, model);
        repeated.driver().refreshWaiting();
        assertEquals(0, repeated.graph().advance().advancedSteps());
        assertEquals(2, model.calls.get()); assertEquals(1, f.continuations.get()); assertEquals(1, f.consumptions.get());
        assertEquals(2, f.base.gateway.submits); assertEquals(2, f.base.gateway.pageReads);
        f.base.allowed.set(false);
        try {
            assertEquals(0, repeated.graph().advance().advancedSteps());
            assertThrows(SecurityException.class, () -> f.candidates.assessment(freshWriter, STEP));
            assertEquals(2, model.calls.get()); assertEquals(1, f.consumptions.get());
        } finally { f.base.allowed.set(true); }
        assertEquals(stepOutputs, f.step(freshWriter, STEP).outputs());
        assertEquals(assessment, f.candidates.assessment(freshWriter, STEP).orElseThrow());
        exited(f.base);
    }

    @Test
    void needsInputAndRejectedCandidatesRemainBlockedWithoutPublishingOutputsOrRunningDependents() throws Exception {
        String needsInput = new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION, ExplorationCandidate.Kind.NEEDS_INPUT,
                "The authorized comparison period must be specified.", List.of(), null, List.of("comparison-period"), null, null).encode();
        for (String response : List.of(needsInput, needsInput.substring(0, needsInput.length() - 1) + ",\"skipCriteria\":true}")) {
            var f = new DriverFixture();
            var model = new TerminalModel(response, PersistentExplorationDriverTest::assertCandidateInstructions);
            var first = f.runtime(f.base.token, model);
            assertEquals(1, first.graph().advance().advancedSteps());
            assertEquals(StepStatus.BLOCKED, f.step(f.base.token, STEP).status());
            assertTrue(f.step(f.base.token, STEP).outputs().isEmpty());
            assertEquals(StepStatus.PENDING, f.step(f.base.token, CONSUME).status());
            assertTrue(f.step(f.base.token, CONSUME).outputs().isEmpty());
            assertEquals(1, model.calls.get()); assertEquals(0, f.consumptions.get()); assertEquals(0, f.continuations.get());
            assertEquals(0, f.base.gateway.submits + f.base.gateway.reads() + f.base.gateway.recoveries);
            var assessment = f.candidates.assessment(f.base.token, STEP).orElseThrow();
            assertEquals(response.equals(needsInput) ? CampaignExplorationCandidateStore.Verdict.NEEDS_INPUT
                    : CampaignExplorationCandidateStore.Verdict.REJECTED, assessment.verdict());
            assertEquals(response.equals(needsInput) ? "EXPLORATION_NEEDS_INPUT" : "EXPLORATION_CANDIDATE_REJECTED",
                    f.step(f.base.token, STEP).reason());
            assertTrue(assessment.outputs().isEmpty());
            assertThrows(IllegalStateException.class, () -> f.candidates.settleComplete(f.reactPermits.get(0)));
            exited(f.base);
            RunToken writer = f.base.steps.acquireRun(f.base.token);
            var reopened = f.runtime(writer, model);
            reopened.driver().refreshWaiting();
            assertEquals(0, reopened.graph().advance().advancedSteps());
            assertEquals(StepStatus.BLOCKED, f.step(writer, STEP).status());
            assertEquals(StepStatus.PENDING, f.step(writer, CONSUME).status());
            assertEquals(assessment, f.candidates.assessment(writer, STEP).orElseThrow());
            assertEquals(1, model.calls.get()); assertEquals(0, f.consumptions.get());
            assertEquals(1, f.base.count("campaign_model_response", RUN));
            assertEquals(0, f.base.count("campaign_exploration_call", RUN));
            assertEquals(1, writer.definition().revision());
            exited(f.base);
        }
    }

    private record Runtime(PersistentPlanDriver driver, NativePlanGraph graph) {}

    @Test
    void admittedDriverUsesOneScopeThroughNativeWaitingAndRejectsALateScan() throws Exception {
        var f = new DriverFixture();
        var model = new SkillModel(f.base, ExplorationCandidateAssessmentTest::completeCandidate);
        var runtime = new AtomicReference<Runtime>();
        var lifecycle = new AtomicReference<ProcessExecutionScope>();
        try (var admitted = new AdmittedCampaignAdvance(new ProcessCapacityExecutor.Limits(2, 2, 2, 4),
                Runnable::run, (reference, scope) -> {
                    lifecycle.set(scope);
                    var loaded = f.runtime(f.base.token, model, scope);
                    runtime.set(loaded);
                    return () -> assertEquals(1, loaded.graph().advance().advancedSteps());
                })) {
            admitted.submit(new ProcessCapacityExecutor.WorkRef(f.base.token.definition().runId(), "initial"))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(StepStatus.WAITING, f.step(f.base.token, STEP).status());
            assertEquals(1, model.calls.get());
            assertEquals(2, f.base.gateway.submits);
            assertEquals(0, admitted.snapshot().activeAdvances());
            assertEquals(0, admitted.snapshot().models());
            assertEquals(0, admitted.snapshot().largePayloads());
            assertTrue(lifecycle.get().isClosed());
            assertEquals(0, lifecycle.get().activeCount());
            assertFalse(runtime.get().driver().mayAdvance());
            assertThrows(IllegalStateException.class, () -> runtime.get().graph().advance());
            assertEquals(1, model.calls.get());
            assertEquals(2, f.base.gateway.submits);
            exited(f.base);
        }
    }

    private static void assertCandidateInstructions(Prompt prompt) {
        String system = prompt.getInstructions().stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .map(SystemMessage::getText).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(system.contains(ExplorationCandidate.SCHEMA_VERSION), "The real native model must receive the terminal response protocol");
        assertTrue(system.contains("outputBindings"));
    }

    private static final class DriverFixture {
        final CallFixture base;
        final CampaignExplorationCandidateStore candidates;
        final AtomicInteger continuations = new AtomicInteger(), consumptions = new AtomicInteger();
        final AtomicReference<Map<String, String>> consumed = new AtomicReference<>();
        final List<StepPermit> reactPermits = new ArrayList<>();

        DriverFixture() throws Exception {
            base = new CallFixture(false, List.of(new PlanSpec.CriterionUse("supported-evidence", Map.of())), DOWNSTREAM, READER);
            migrate(base);
            candidates = ExplorationCandidateAssessmentTest.candidates(base, criteria(base, false));
        }

        StepRecord step(RunToken token, String id) { return base.steps.step(token, id).orElseThrow(); }

        Runtime runtime(RunToken token, ChatModel model) throws Exception {
            return runtime(token, model, null);
        }

        Runtime runtime(RunToken token, ChatModel model, ProcessExecutionScope scope) throws Exception {
            var readPolicy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                    assertEquals(CONSUME, step.stepId());
                    assertEquals(DeclineSelectionPublisher.SELECTED_TYPE, inputs.artifact("selectedEntities").metadata().ref().type());
                    assertEquals(DeclineSelectionPublisher.EVIDENCE_TYPE, inputs.artifact("selectionEvidence").metadata().ref().type());
                }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertTrue(outputs.isEmpty());
                }
            };
            var consumer = new PersistentPlanDriver.FixedExecutor(READ, readPolicy, context -> {
                context.requireCurrent();
                var selected = context.inputs().artifact("selectedEntities");
                var evidence = context.inputs().artifact("selectionEvidence");
                assertEquals(StepStatus.SUCCEEDED, step(token, STEP).status());
                var index = new JdbcCampaignDeclineSelectionStore(base.base.jdbc, base.base.transactions, CLOCK, base.runs);
                var pair = index.inspectPair(OWNER, selected.metadata().ref().artifactId(), evidence.metadata().ref().artifactId(), base.auth);
                assertEquals(selected.metadata(), pair.selectedEntities()); assertEquals(evidence.metadata(), pair.selectionEvidence());
                var page = index.readSelectedPage(OWNER, selected.metadata().ref().artifactId(), null, 500, base.auth);
                assertEquals(1, page.rows().size()); assertEquals(1, page.rows().get(0).linkId());
                assertEquals(BigInteger.valueOf(-7), page.rows().get(0).delta());
                consumed.set(Map.of("selectedEntities", selected.metadata().ref().artifactId(),
                        "selectionEvidence", evidence.metadata().ref().artifactId()));
                consumptions.incrementAndGet();
                return PersistentPlanDriver.Result.succeeded(Map.of());
            });
            var reactPolicy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                    assertEquals(STEP, step.stepId()); assertEquals(base.scopeRef, inputs.value("scope")); assertEquals(PAIR, inputs.value("periods"));
                }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertEquals(OUTPUTS, outputs.keySet());
                }
            };
            var execute = new PersistentExplorationExecutor((planned, inputs, permit, authorized) -> {
                assertTrue(authorized.getAsBoolean()); assertEquals(STEP, planned.stepId());
                assertEquals(permit.runToken(), token); reactPermits.add(permit);
                var ledger = nativeLedger(base, permit, candidates);
                var adapter = scope == null ? nativeAdapter(base, ledger, model)
                        : new NativeExplorationAdapter(ledger.identity(), ledger, model,
                                List.of(new DeclineSelectionExplorationSkill(base.adapter()).registration()),
                                new MemorySaver(), Runnable::run, new NativeExplorationAdapter.Limits(
                                        0, 4096, 32768, 32768, 8, java.time.Duration.ofSeconds(10)), ledger, scope);
                return new PersistentExplorationExecutor.Session(ledger, adapter, PROMPT, (current, callId, version) -> {
                    assertTrue(authorized.getAsBoolean()); continuations.incrementAndGet();
                    var completion = base.adapter().continueInvocation(current, callId, version);
                    assertFalse(base.calls.call(current.runToken(), callId).orElseThrow().callbackActive());
                    if (model instanceof SkillModel skillModel) {
                        skillModel.completion = completion;
                        skillModel.call = base.calls.call(current.runToken(), callId).orElseThrow();
                    }
                });
            }, scope);
            var driver = new PersistentPlanDriver(token, base.runs, base.steps, base.catalog, base.contracts, List.of(consumer),
                    (caller, inputs) -> base.allowed.get() && OWNER.equals(caller), base.auth,
                    (caller, type, value) -> base.allowed.get() && OWNER.equals(caller)
                            && ("ScopeRef".equals(type.name()) ? base.scopeRef.equals(value) : "PeriodsRef".equals(type.name()) && PAIR.equals(value)),
                    List.of(new PersistentPlanDriver.ReactExecutor("decline-explore", "1", reactPolicy, execute)), candidates, scope);
            return new Runtime(driver, driver.compile(new MemorySaver()));
        }
    }
}
