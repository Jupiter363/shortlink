package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Opt-in JDBC Driver for the native serial Plan scan. No Spring registration, scheduler or custom
 * ReAct loop. This stage supports registered FIXED adapters; REACT plans fail before any dispatch
 * until the native exploration adapter has its persistent, per-I/O boundaries wired in P3.
 */
public final class PersistentPlanDriver implements NativePlanGraph.Driver {
    @FunctionalInterface public interface Executor { Result execute(CampaignStepExecution context) throws Exception; }
    @FunctionalInterface public interface RunAuthorizer { boolean mayExecute(Caller caller, FrozenInputSet frozenInputs); }

    /** Policies and callbacks are trusted, versioned application code, never supplied by a Plan. */
    public record FixedExecutor(PlanSpec.ExecutorRef ref, StepBindings.StepPolicy policy, Executor executor) {
        public FixedExecutor { Objects.requireNonNull(ref); Objects.requireNonNull(policy); Objects.requireNonNull(executor); }
    }

    public record Result(CampaignStepStore.StepStatus status, Map<String, String> outputs, String reason) {
        public Result {
            outputs = Map.copyOf(outputs);
            if (status != CampaignStepStore.StepStatus.SUCCEEDED && status != CampaignStepStore.StepStatus.WAITING
                    && status != CampaignStepStore.StepStatus.BLOCKED && status != CampaignStepStore.StepStatus.FAILED)
                throw new IllegalArgumentException("An executor must return a settled status");
            if (status != CampaignStepStore.StepStatus.SUCCEEDED && !outputs.isEmpty())
                throw new IllegalArgumentException("Only successful steps may release named outputs");
        }
        public static Result succeeded(Map<String, String> outputs) { return new Result(CampaignStepStore.StepStatus.SUCCEEDED, outputs, null); }
        public static Result waiting() { return new Result(CampaignStepStore.StepStatus.WAITING, Map.of(), null); }
        public static Result blocked(String reason) { return new Result(CampaignStepStore.StepStatus.BLOCKED, Map.of(), reason); }
    }

    private final RunToken token;
    private final FrozenCampaignRun frozen;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final RunAuthorizer runAuthorizer;
    private final ArtifactAuthorizer artifactAuthorizer;
    private final StepBindings.CurrentInputAuthorizer inputAuthorizer;
    private final ArtifactContractRegistry contracts;
    private final Map<PlanSpec.ExecutorRef, FixedExecutor> executors;
    private final Map<String, CapabilityCatalog.Signature> signatures;
    private final PlanValidator validator;

    /** token must have been acquired through StepStore.acquireRun; construction never silently takes over. */
    public PersistentPlanDriver(RunToken token, CampaignRunStore runs, CampaignStepStore steps,
            CapabilityCatalog catalog, ArtifactContractRegistry contracts, List<FixedExecutor> executors,
            RunAuthorizer runAuthorizer, ArtifactAuthorizer artifactAuthorizer,
            StepBindings.CurrentInputAuthorizer inputAuthorizer) {
        this.token = Objects.requireNonNull(token);
        this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps);
        this.contracts = Objects.requireNonNull(contracts);
        this.runAuthorizer = Objects.requireNonNull(runAuthorizer);
        this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer);
        this.inputAuthorizer = Objects.requireNonNull(inputAuthorizer);
        this.frozen = FrozenCampaignRun.read(token.definition());
        this.executors = executors.stream().collect(Collectors.toUnmodifiableMap(FixedExecutor::ref, value -> value));
        this.validator = new PlanValidator(catalog, id -> Optional.of(contracts.typeOf(
                runs.inspectArtifact(token.definition().caller(), id, artifactAuthorizer))));
        validator.validate(frozen.plan(), frozen.inputs(), frozen.assessment());
        Map<String, CapabilityCatalog.Signature> registered = new HashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (step.executionMode() != PlanSpec.ExecutionMode.FIXED || !this.executors.containsKey(step.executor()))
                throw new IllegalArgumentException("RUNTIME_EXECUTOR_UNAVAILABLE");
            registered.put(step.stepId(), catalog.capability(step.executor()).orElseThrow().signature());
        }
        this.signatures = Map.copyOf(registered);
        if (!authorized()) throw new SecurityException("RUN_ACCESS_DENIED");
        steps.initialize(token, frozen.plan().steps().stream().map(step -> {
            var outputs = signatures.get(step.stepId()).outputs();
            Set<String> required = outputs.entrySet().stream().filter(entry -> entry.getValue().required())
                    .map(Map.Entry::getKey).collect(Collectors.toSet());
            return new StepSpec(step.stepId(), FrozenCampaignRun.encode(step), step.dependsOn(), outputs.keySet(), required);
        }).toList());
    }

    public NativePlanGraph compile(BaseCheckpointSaver saver) throws GraphStateException {
        var definition = token.definition();
        var caller = definition.caller();
        return NativePlanGraph.compile(frozen.plan(), frozen.inputs(), frozen.assessment(), validator,
                new NativePlanGraph.RunIdentity(caller.tenantId(), caller.subject(), caller.authVersion(),
                        definition.sessionId(), definition.runId(), definition.planId(), definition.revision(),
                        frozen.runnerVersion(), frozen.topologyVersion()), saver, this);
    }

    /** Coordinator calls after result ingestion; this does not poll or interpret a remote job status. */
    public void refreshWaiting() {
        if (!mayAdvance()) return;
        for (StepRecord record : steps.steps(token)) {
            steps.refreshWaiting(token, record.spec().stepId());
            steps.refreshCapacityDeferred(token, record.spec().stepId());
        }
    }

    @Override public boolean mayAdvance() { return authorized() && steps.mayAdvance(token); }

    @Override public NativePlanGraph.StepStatus status(String stepId) {
        return NativePlanGraph.StepStatus.valueOf(steps.step(token, stepId).orElseThrow().status().name());
    }

    @Override public void advance(PlanSpec.Step step) throws Exception {
        if (!frozen.plan().steps().contains(step)) throw new IllegalArgumentException("FROZEN_STEP_CHANGED");
        if (!mayAdvance()) return;
        StepPermit permit = steps.beginStep(token, step.stepId());
        FixedExecutor executor = executors.get(step.executor());
        var bindings = new StepBindings(contracts, runs, token.definition().caller(), artifactAuthorizer,
                inputAuthorizer, executor.policy());
        String failure = "INPUT_BINDING_INVALID";
        try (var inputs = bindings.resolve(step, frozen.inputs(), signatures.get(step.stepId()), this::completedOutput);
             var context = new CampaignStepExecution(step, frozen.plan().goals().stream()
                     .filter(goal -> step.goalIds().contains(goal.goalId())).toList(), inputs, permit, runs, steps,
                     () -> authorizedInputs(bindings, signatures.get(step.stepId()), inputs))) {
            context.requireCurrent();
            failure = "STEP_RESULT_UNKNOWN";
            Result result = Objects.requireNonNull(executor.executor().execute(context), "Step result is required");
            context.requireCurrent();
            failure = "OUTPUT_CONTRACT_INVALID";
            if (result.status() == CampaignStepStore.StepStatus.SUCCEEDED)
                bindings.validateOutputs(step, signatures.get(step.stepId()), inputs, result.outputs());
            // Recheck both run authority and Artifact authority at the publication boundary.
            context.requireCurrent();
            steps.settle(permit, result.status(), result.outputs(), result.reason(), artifactAuthorizer);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            // A fenced writer cannot publish even an error; finally still records its actual callback exit.
            if (steps.mayExecute(permit)) steps.settle(permit, CampaignStepStore.StepStatus.BLOCKED, Map.of(),
                    exception instanceof SecurityException ? "EXECUTION_ACCESS_DENIED" : failure, artifactAuthorizer);
        } finally {
            steps.callbackExited(permit);
        }
    }

    private Optional<String> completedOutput(String stepId, String name) {
        return steps.step(token, stepId).filter(record -> record.status() == CampaignStepStore.StepStatus.SUCCEEDED)
                .map(record -> record.outputs().get(name));
    }

    private boolean authorizedInputs(StepBindings bindings, CapabilityCatalog.Signature signature, BoundInputs inputs) {
        if (!authorized()) return false;
        try {
            bindings.reauthorize(signature, inputs);
            return true;
        } catch (SecurityException | BindingException denied) {
            return false;
        }
    }

    private boolean authorized() { return runAuthorizer.mayExecute(token.definition().caller(), frozen.inputs()); }
}
