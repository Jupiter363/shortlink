package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.Approval;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Invocation-scoped, serial child boundary for trusted adapters. No gateway or credentials in Graph state. */
public final class CampaignStepExecution implements CapabilityExecution {
    @FunctionalInterface public interface ChildCall { ChildResult call(IoBoundary boundary) throws Exception; }
    @FunctionalInterface public interface LocalCall { Map<String, ArtifactDraft> compute(LocalBoundary boundary) throws Exception; }

    /** A known calculation/contract rejection must not be re-armed as an unknown result. */
    public static final class LocalResultInvalid extends IllegalArgumentException {
        LocalResultInvalid(IllegalArgumentException cause) { super("LOCAL_RESULT_INVALID", cause); }
    }

    public record ChildResult(String jobId, ArtifactDraft artifact, CapacityKind capacityKind) {
        public ChildResult(String jobId, ArtifactDraft artifact) { this(jobId, artifact, null); }
        public ChildResult {
            if ((jobId == null ? 0 : 1) + (artifact == null ? 0 : 1) + (capacityKind == null ? 0 : 1) != 1
                    || (jobId != null && jobId.isBlank()))
                throw new IllegalArgumentException("A child receipt requires exactly one job, artifact or unadmitted proof");
        }
        public static ChildResult waiting(String jobId) { return new ChildResult(jobId, null); }
        public static ChildResult ready(ArtifactDraft artifact) { return new ChildResult(null, artifact); }
        /** Only a trusted adapter's validated remote admitted=false receipt may use this branch. */
        public static ChildResult notAdmitted(CapacityKind kind) { return new ChildResult(null, null, Objects.requireNonNull(kind)); }
    }

    /** Adapters must call beforeIo immediately before EACH real wire operation, including paging. */
    public interface IoBoundary {
        WireRequest request();
        void beforeIo();
    }

    /** Registered pure calculations can read only their exact frozen local Artifact inputs here. */
    public interface LocalBoundary {
        void requireCurrent();
        Artifact readInput(String name);
    }

    private final PlanSpec.Step step;
    private final List<PlanSpec.Goal> goalSpecs;
    private final BoundInputs inputs;
    private final StepPermit permit;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final BooleanSupplier authorized;
    private final CampaignChildExecution children;
    private volatile boolean closed;

    CampaignStepExecution(PlanSpec.Step step, List<PlanSpec.Goal> goalSpecs, BoundInputs inputs, StepPermit permit, CampaignRunStore runs,
                          CampaignStepStore steps, BooleanSupplier authorized) {
        this.step = step;
        this.goalSpecs = List.copyOf(goalSpecs);
        this.inputs = inputs;
        this.permit = permit;
        this.runs = runs;
        this.steps = steps;
        this.authorized = authorized;
        this.children = new CampaignChildExecution(permit, runs, this::requireCurrent, this::prepareAction, null);
    }

    public PlanSpec.Step step() { requireCurrent(); return step; }
    public List<PlanSpec.Goal> goalSpecs() { requireCurrent(); return goalSpecs; }
    public BoundInputs inputs() { requireCurrent(); return inputs; }

    public void requireCurrent() {
        if (closed || !authorized.getAsBoolean() || !steps.mayExecute(permit))
            throw new SecurityException("STEP_EXECUTION_FENCED");
    }

    /**
     * Stable action/child/request IDs are supplied by the versioned adapter. An existing receipt,
     * WAITING job or unknown result is returned without invoking the call or submitting again.
     * Reconciliation is a separate protocol; this method can only dispatch PREPARED work.
     */
    public ChildRecord child(ChildSpec spec, ChildCall call) throws Exception {
        return children.child(spec, call);
    }

    /** Exact registered invocation; completed output sets are reused without running the calculation again. */
    public Map<String, ArtifactRef> local(ChildSpec spec, Approval approval, ArtifactAuthorizer authorizer,
                                          LocalCall calculation) throws Exception {
        return children.local(spec, approval, authorizer, calculation);
    }

    private void prepareAction(ChildSpec spec) {
        requireCurrent();
        var executor = step.executor();
        runs.prepareAction(permit.runToken(), new ActionSpec(spec.actionId(), step.stepId(), executor.kind().name(),
                executor.name(), executor.version(), FrozenCampaignRun.encode(step)));
    }

    @Override public void close() { closed = true; }
}
