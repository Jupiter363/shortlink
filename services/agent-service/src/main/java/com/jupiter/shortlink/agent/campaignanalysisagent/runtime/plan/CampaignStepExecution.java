package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.util.Objects;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Invocation-scoped, serial child boundary for trusted adapters. No gateway or credentials in Graph state. */
public final class CampaignStepExecution implements AutoCloseable {
    @FunctionalInterface public interface ChildCall { ChildResult call(IoBoundary boundary) throws Exception; }

    public record ChildResult(String jobId, ArtifactDraft artifact) {
        public ChildResult {
            if ((jobId == null) == (artifact == null) || (jobId != null && jobId.isBlank()))
                throw new IllegalArgumentException("A child receipt requires exactly one job or artifact");
        }
        public static ChildResult waiting(String jobId) { return new ChildResult(jobId, null); }
        public static ChildResult ready(ArtifactDraft artifact) { return new ChildResult(null, artifact); }
    }

    /** Adapters must call beforeIo immediately before EACH real wire operation, including paging. */
    public final class IoBoundary {
        private final DispatchPermit dispatch;
        private final ChildSpec child;
        private IoBoundary(DispatchPermit dispatch, ChildSpec child) { this.dispatch = dispatch; this.child = child; }
        public WireRequest request() { requireCurrent(); return child.wire(); }
        public void beforeIo() {
            requireCurrent();
            if (!runs.mayDispatch(dispatch)) throw new SecurityException("CHILD_EXECUTION_FENCED");
        }
    }

    private final PlanSpec.Step step;
    private final List<PlanSpec.Goal> goalSpecs;
    private final BoundInputs inputs;
    private final StepPermit permit;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final BooleanSupplier authorized;
    private boolean closed;

    CampaignStepExecution(PlanSpec.Step step, List<PlanSpec.Goal> goalSpecs, BoundInputs inputs, StepPermit permit, CampaignRunStore runs,
                          CampaignStepStore steps, BooleanSupplier authorized) {
        this.step = step;
        this.goalSpecs = List.copyOf(goalSpecs);
        this.inputs = inputs;
        this.permit = permit;
        this.runs = runs;
        this.steps = steps;
        this.authorized = authorized;
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
        requireCurrent();
        Objects.requireNonNull(call);
        var executor = step.executor();
        runs.prepareAction(permit.runToken(), new ActionSpec(spec.actionId(), step.stepId(), executor.kind().name(),
                executor.name(), executor.version(), FrozenCampaignRun.encode(step)));
        ChildRecord existing = runs.prepareChild(permit.runToken(), spec);
        if (existing.state() != ChildState.PREPARED) return existing;
        requireCurrent();
        DispatchPermit dispatch = runs.beginDispatch(permit.runToken(), spec.childId());
        boolean receiptSaved = false;
        try {
            IoBoundary boundary = new IoBoundary(dispatch, spec);
            boundary.beforeIo();
            ChildResult result = Objects.requireNonNull(call.call(boundary), "Child receipt is required");
            if (result.jobId() != null && (!authorized.getAsBoolean() || !steps.mayExecute(permit)
                    || !runs.mayDispatch(dispatch))) {
                runs.recordLateJob(dispatch, result.jobId());
                receiptSaved = true;
                throw new SecurityException("STEP_EXECUTION_FENCED");
            }
            boundary.beforeIo(); // Same fence applies to local publication after the remote call.
            if (result.jobId() != null) runs.recordWaiting(dispatch, result.jobId());
            else runs.publishReady(dispatch, result.artifact());
            receiptSaved = true;
        } finally {
            try {
                if (!receiptSaved && runs.mayDispatch(dispatch)) runs.markUnresolved(dispatch);
            } finally {
                runs.callbackExited(dispatch); // Only the actual callback exit releases the ledger slot.
            }
        }
        return runs.child(permit.runToken(), spec.childId()).orElseThrow();
    }

    @Override public void close() { closed = true; }
}
