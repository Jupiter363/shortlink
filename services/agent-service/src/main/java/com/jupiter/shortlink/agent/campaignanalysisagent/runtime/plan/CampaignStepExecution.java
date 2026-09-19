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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Invocation-scoped, serial child boundary for trusted adapters. No gateway or credentials in Graph state. */
public final class CampaignStepExecution implements AutoCloseable {
    @FunctionalInterface public interface ChildCall { ChildResult call(IoBoundary boundary) throws Exception; }
    @FunctionalInterface public interface LocalCall { Map<String, ArtifactDraft> compute(LocalBoundary boundary) throws Exception; }

    /** A known calculation/contract rejection must not be re-armed as an unknown result. */
    public static final class LocalResultInvalid extends IllegalArgumentException {
        private LocalResultInvalid(IllegalArgumentException cause) { super("LOCAL_RESULT_INVALID", cause); }
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

    /** Registered pure calculations can read only their exact frozen local Artifact inputs here. */
    public final class LocalBoundary {
        private final DispatchPermit dispatch;
        private final Approval approval;
        private final ArtifactAuthorizer authorizer;
        private LocalBoundary(DispatchPermit dispatch, Approval approval, ArtifactAuthorizer authorizer) {
            this.dispatch = dispatch; this.approval = approval; this.authorizer = authorizer;
        }
        public void requireCurrent() {
            CampaignStepExecution.this.requireCurrent();
            if (!runs.mayDispatch(dispatch)) throw new SecurityException("LOCAL_EXECUTION_FENCED");
        }
        public Artifact readInput(String name) {
            requireCurrent();
            ArtifactMetadata expected = approval.invocation().inputs().get(name);
            if (expected == null) throw new IllegalArgumentException("LOCAL_INPUT_NOT_DECLARED");
            Artifact input = runs.readArtifact(permit.runToken().definition().caller(), expected.ref().artifactId(), authorizer);
            if (!expected.equals(input.metadata())) throw new IllegalArgumentException("LOCAL_INPUT_CHANGED");
            requireCurrent();
            return input;
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
        if (!runs.submissionDue(permit.runToken(), spec.childId())) return existing;
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
            if (result.capacityKind() != null) runs.deferUnadmitted(dispatch, result.capacityKind());
            else if (result.jobId() != null) runs.recordWaiting(dispatch, result.jobId());
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

    /** Exact registered invocation; completed output sets are reused without running the calculation again. */
    public Map<String, ArtifactRef> local(ChildSpec spec, Approval approval, ArtifactAuthorizer authorizer,
                                          LocalCall calculation) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("LOCAL_COMPUTE_REQUIRES_COMMITTED_PREPARATION");
        requireCurrent();
        Objects.requireNonNull(calculation);
        Objects.requireNonNull(authorizer);
        var executor = step.executor();
        runs.prepareAction(permit.runToken(), new ActionSpec(spec.actionId(), step.stepId(), executor.kind().name(),
                executor.name(), executor.version(), FrozenCampaignRun.encode(step)));
        ChildRecord existing = runs.prepareLocalChild(permit.runToken(), spec, approval, authorizer);
        if (existing.state() == ChildState.READY) {
            var outputs = runs.localOutputs(permit.runToken(), spec.childId(), authorizer);
            requireCurrent();
            return outputs;
        }
        if (existing.reason() == UnresolvedReason.LOCAL_RESULT_INVALID)
            throw new LocalResultInvalid(new IllegalArgumentException("LOCAL_RESULT_INVALID"));
        DispatchPermit dispatch = existing.state() == ChildState.PREPARED
                ? runs.beginDispatch(permit.runToken(), spec.childId())
                : runs.beginLocalReplay(permit.runToken(), spec.childId(), approval, authorizer);
        boolean saved = false;
        try {
            LocalBoundary boundary = new LocalBoundary(dispatch, approval, authorizer);
            boundary.requireCurrent();
            Map<String, ArtifactDraft> drafts = Map.copyOf(calculation.compute(boundary));
            boundary.requireCurrent();
            runs.publishLocalReady(permit, dispatch, approval, drafts, authorizer);
            saved = true;
        } catch (IllegalArgumentException rejected) {
            if (runs.mayDispatch(dispatch)) {
                runs.markLocalInvalid(dispatch);
                saved = true;
            }
            throw new LocalResultInvalid(rejected);
        } finally {
            try { if (!saved && runs.mayDispatch(dispatch)) runs.markUnresolved(dispatch); }
            finally { runs.callbackExited(dispatch); }
        }
        requireCurrent();
        return runs.localOutputs(permit.runToken(), spec.childId(), authorizer);
    }

    @Override public void close() { closed = true; }
}
