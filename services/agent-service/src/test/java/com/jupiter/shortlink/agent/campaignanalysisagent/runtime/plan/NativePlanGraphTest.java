package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.NativePlanGraph.StepStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidationException;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** Native StateGraph + real MemorySaver; the narrow fake Driver represents durable domain facts. */
class NativePlanGraphTest {
    @Test
    void waitingStepDoesNotBlockIndependentWorkAndRepeatedScansNeverRepeatCompletedSteps() throws Exception {
        Fixture fixture = fixture(List.of(step("a", List.of()), step("b", List.of("a")), step("c", List.of())), 1, "");
        Ledger ledger = new Ledger(fixture.plan());
        ledger.behavior = (step, current) -> current.states.put(step.stepId(),
                step.stepId().equals("a") && current.states.get("a") == PENDING ? WAITING : SUCCEEDED);
        InspectingSaver saver = new InspectingSaver();
        NativePlanGraph graph = compile(fixture, identity("tenant-1", 1), saver, ledger);

        var first = graph.advance();
        assertThat(first.scanCompleted()).isTrue(); // Native END is not business success.
        assertThat(first.visitedSteps()).isEqualTo(3);
        assertThat(first.advancedSteps()).isEqualTo(2);
        assertThat(ledger.calls).containsExactly("a", "c");
        assertThat(ledger.states).containsEntry("a", WAITING).containsEntry("b", PENDING).containsEntry("c", SUCCEEDED);
        var stillWaiting = graph.advance();
        assertThat(stillWaiting.visitedSteps()).isEqualTo(3);
        assertThat(stillWaiting.advancedSteps()).isZero();
        assertThat(ledger.calls).containsExactly("a", "c");

        ledger.states.put("a", READY); // Coordinator reconciles the job before the next graph scan.
        NativePlanGraph restored = compile(fixture, identity("tenant-1", 1), saver, ledger);
        assertThat(restored.threadId()).isEqualTo(graph.threadId());
        assertThat(restored.advance().advancedSteps()).isEqualTo(2);
        assertThat(ledger.calls).containsExactly("a", "c", "a", "b");
        assertThat(restored.advance().advancedSteps()).isZero();
        assertThat(ledger.calls).containsExactly("a", "c", "a", "b");
        assertThat(saver.forThread(graph.threadId())).isNotEmpty();
    }

    @Test
    void tenantRevisionRunnerTopologyAndFrozenContentUseIndependentNativeThreads() throws Exception {
        Fixture first = fixture(List.of(step("a", List.of())), 1, "");
        Fixture revision = fixture(first.plan().steps(), 2, "");
        InspectingSaver saver = new InspectingSaver();
        List<NativePlanGraph> graphs = new ArrayList<>();
        graphs.add(compile(first, identity("tenant-1", 1), saver, new Ledger(first.plan())));
        graphs.add(compile(first, identity("tenant-2", 1), saver, new Ledger(first.plan())));
        graphs.add(compile(revision, identity("tenant-1", 2), saver, new Ledger(revision.plan())));
        graphs.add(compile(first, new NativePlanGraph.RunIdentity("tenant-1", "alice", 7, "session-1", "run-1", "plan-1", 1,
                "runner-v2", "scan-v1"), saver, new Ledger(first.plan())));
        graphs.add(compile(first, new NativePlanGraph.RunIdentity("tenant-1", "alice", 7, "session-1", "run-1", "plan-1", 1,
                "runner-v1", "scan-v2"), saver, new Ledger(first.plan())));
        Fixture changedContent = fixture(List.of(new PlanSpec.Step("a", List.of("goal"), PlanSpec.ExecutionMode.FIXED,
                TOOL, null, List.of(), Map.of(), Map.of("hint", "changed frozen content"), "evidence/v1")), 1, "");
        graphs.add(compile(changedContent, identity("tenant-1", 1), saver, new Ledger(changedContent.plan())));

        assertThat(graphs.stream().map(NativePlanGraph::threadId)).doesNotHaveDuplicates();
        for (NativePlanGraph graph : graphs) {
            assertThat(UUID.fromString(graph.threadId()).toString()).isEqualTo(graph.threadId());
            assertThat(graph.advance().advancedSteps()).isEqualTo(1);
            assertThat(saver.forThread(graph.threadId())).isNotEmpty()
                    .allSatisfy(saved -> assertThat(saved.state()).containsEntry("threadId", graph.threadId()));
        }
    }

    @Test
    void cancellationAfterOneDispatchStopsAllLaterDriverAdvances() throws Exception {
        Fixture fixture = fixture(List.of(step("a", List.of()), step("b", List.of("a")), step("c", List.of())), 1, "");
        Ledger ledger = new Ledger(fixture.plan());
        ledger.behavior = (step, current) -> {
            current.states.put(step.stepId(), SUCCEEDED);
            current.allowed = false;
        };
        var graph = compile(fixture, identity("tenant-1", 1), new MemorySaver(), ledger);

        var result = graph.advance();

        assertThat(result.scanCompleted()).isFalse();
        assertThat(result.visitedSteps()).isEqualTo(1);
        assertThat(result.advancedSteps()).isEqualTo(1);
        assertThat(ledger.calls).containsExactly("a");
        assertThat(ledger.statusReads).containsExactly("a");
        assertThat(graph.advance().advancedSteps()).isZero();
        assertThat(ledger.calls).containsExactly("a");
    }

    @Test
    void legalFortyStepDependencyChainUsesItsOwnComputedNativeRecursionLimit() throws Exception {
        List<PlanSpec.Step> steps = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            steps.add(step("s" + index, index == 0 ? List.of() : List.of("s" + (index - 1))));
        }
        Fixture fixture = fixture(steps, 1, "");
        Ledger ledger = new Ledger(fixture.plan());
        var graph = compile(fixture, identity("tenant-1", 1), new MemorySaver(), ledger);

        var result = graph.advance();

        assertThat(graph.recursionLimit()).isEqualTo(44);
        assertThat(result.scanCompleted()).isTrue();
        assertThat(result.visitedSteps()).isEqualTo(40);
        assertThat(result.advancedSteps()).isEqualTo(40);
        assertThat(ledger.calls).containsExactlyElementsOf(steps.stream().map(PlanSpec.Step::stepId).toList());
    }

    @Test
    void everyNativeCheckpointContainsOnlySmallIdentityAndScanStateNotFrozenInputsOrStepPayload() throws Exception {
        String payload = "PRIVATE_PLAN_OR_INPUT_PAYLOAD" + "x".repeat(1_200_000);
        var heavy = new PlanSpec.Step("a", List.of("goal"), PlanSpec.ExecutionMode.FIXED, TOOL, null,
                List.of(), Map.of(), Map.of("hint", payload), "evidence/v1");
        Fixture fixture = fixture(List.of(heavy), 1, payload);
        InspectingSaver saver = new InspectingSaver();
        Ledger ledger = new Ledger(fixture.plan());
        var graph = compile(fixture, identity("tenant-1", 1), saver, ledger);

        graph.advance();

        assertThat(saver.forThread(graph.threadId())).isNotEmpty().allSatisfy(saved -> {
            assertThat(saved.bytes()).isLessThan(4096);
            assertThat(saved.state()).containsOnlyKeys("threadId", "planId", "revision", "visitedSteps", "advancedSteps",
                    "lastStepRef", "lastObservedStatus", "stopped", "scanCompleted", "_graph_execution_id_");
            assertThat(saved.state().toString()).doesNotContain("PRIVATE_PLAN_OR_INPUT_PAYLOAD", "inputValues", "parameters");
        });
        assertThat(ledger.calls).containsExactly("a");
    }

    @Test
    void compileRejectsInvalidFrozenPlanOrWrongExecutionIdentityBeforeAnyDriverInteraction() throws Exception {
        Fixture fixture = fixture(List.of(step("a", List.of()), step("a", List.of())), 1, "");
        Ledger ledger = new Ledger(fixture.plan());
        assertThatThrownBy(() -> compile(fixture, identity("tenant-1", 1), new MemorySaver(), ledger))
                .isInstanceOf(PlanValidationException.class);
        Fixture valid = fixture(List.of(step("a", List.of())), 1, "");
        assertThatThrownBy(() -> compile(valid, identity("tenant-1", 2), new MemorySaver(), ledger))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
        assertThat(ledger.calls).isEmpty();
        assertThat(ledger.statusReads).isEmpty();
        assertThat(ledger.gateReads).isZero();
    }

    private static final PlanSpec.ExecutorRef TOOL = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1.0.0");
    private static final CapabilityCatalog.TypeRef EVIDENCE = new CapabilityCatalog.TypeRef("evidence", 1, CapabilityCatalog.Cardinality.ONE);

    private record Fixture(PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment, PlanValidator validator) { }

    private static PlanSpec.Step step(String id, List<String> dependencies) {
        return new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED, TOOL, null, dependencies, Map.of(), Map.of(), "evidence/v1");
    }

    private static Fixture fixture(List<PlanSpec.Step> steps, int revision, String privateInput) {
        var signature = new CapabilityCatalog.Signature(Map.of(), "evidence/v1", Map.of("evidence", new CapabilityCatalog.Port(EVIDENCE, true)),
                new CapabilityCatalog.Parameters(Set.of(), Map.of("hint", value -> value instanceof String)));
        CapabilityCatalog catalog = new CapabilityCatalog() {
            @Override public String version() { return "catalog-v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) {
                return TOOL.equals(executor) ? Optional.of(new Capability(TOOL, signature, false)) : Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return "delivery/v1".equals(ref) && "1.0.0".equals(version)
                        ? Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(EVIDENCE)))
                        : Optional.empty();
            }
        };
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", revision, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Analyze authorized evidence", true, "Deliver the verified result")), steps);
        var inputs = new FrozenInputSet("inputs-1", "run-1", Map.of("privateInput", new CapabilityCatalog.Port(EVIDENCE, true)),
                Map.of("privateInput", privateInput));
        var assessment = new PlanningAssessment("plan-1", revision, catalog.version(), List.of(new PlanningAssessment.Requirement("delivery",
                "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery/v1", "1.0.0", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(
                        steps.get(steps.size() - 1).stepId(), "evidence")))), List.of());
        return new Fixture(plan, inputs, assessment, new PlanValidator(catalog));
    }

    private static NativePlanGraph.RunIdentity identity(String tenant, int revision) {
        return new NativePlanGraph.RunIdentity(tenant, "alice", 7, "session-1", "run-1", "plan-1", revision, "runner-v1", "scan-v1");
    }

    private static NativePlanGraph compile(Fixture fixture, NativePlanGraph.RunIdentity identity, MemorySaver saver, Ledger ledger) throws Exception {
        return NativePlanGraph.compile(fixture.plan(), fixture.inputs(), fixture.assessment(), fixture.validator(), identity, saver, ledger);
    }

    private static final class Ledger implements NativePlanGraph.Driver {
        final Map<String, NativePlanGraph.StepStatus> states = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> statusReads = new ArrayList<>();
        boolean allowed = true;
        int gateReads;
        BiConsumer<PlanSpec.Step, Ledger> behavior = (step, current) -> current.states.put(step.stepId(), SUCCEEDED);
        Ledger(PlanSpec plan) { plan.steps().forEach(step -> states.put(step.stepId(), PENDING)); }
        @Override public boolean mayAdvance() { gateReads++; return allowed; }
        @Override public NativePlanGraph.StepStatus status(String stepId) { statusReads.add(stepId); return states.get(stepId); }
        @Override public void advance(PlanSpec.Step step) { calls.add(step.stepId()); behavior.accept(step, this); }
    }

    private record SavedState(String threadId, Map<String, Object> state, int bytes) { }

    private static final class InspectingSaver extends MemorySaver {
        private final List<SavedState> writes = new ArrayList<>();
        @Override protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
            capture(config, checkpoint);
        }
        @Override protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
            capture(config, checkpoint);
        }
        private synchronized void capture(RunnableConfig config, Checkpoint checkpoint) throws Exception {
            writes.add(new SavedState(config.threadId().orElseThrow(), new LinkedHashMap<>(checkpoint.getState()),
                    AgentStateSerializerFactory.create().dataToBytes(checkpoint.getState()).length));
        }
        synchronized List<SavedState> forThread(String threadId) { return writes.stream().filter(saved -> saved.threadId().equals(threadId)).toList(); }
    }
}
