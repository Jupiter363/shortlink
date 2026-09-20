package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.ChildCall;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.ChildResult;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.IoBoundary;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.LocalBoundary;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.LocalCall;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution.LocalResultInvalid;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One child receipt protocol, shared by fixed execution and an actual exploration CALL owner. */
final class CampaignChildExecution {
    private final StepPermit step;
    private final CampaignRunStore runs;
    private final Runnable current;
    private final Consumer<ChildSpec> prepareAction;
    private final CallPermit parent;

    CampaignChildExecution(StepPermit step, CampaignRunStore runs, Runnable current,
                           Consumer<ChildSpec> prepareAction, CallPermit parent) {
        this.step = Objects.requireNonNull(step); this.runs = Objects.requireNonNull(runs);
        this.current = Objects.requireNonNull(current); this.prepareAction = Objects.requireNonNull(prepareAction);
        this.parent = parent;
        if (parent != null && !step.equals(parent.step())) throw new IllegalArgumentException("CHILD_PARENT_STEP_MISMATCH");
    }

    ChildRecord child(ChildSpec spec, ChildCall call) throws Exception {
        if (parent != null && TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CALL_CHILD_REQUIRES_COMMITTED_PREPARATION");
        current.run(); Objects.requireNonNull(spec); Objects.requireNonNull(call);
        prepareAction.accept(spec);
        ChildRecord existing = runs.prepareChild(step.runToken(), spec);
        if (existing.state() != ChildState.PREPARED) return existing;
        if (!runs.submissionDue(step.runToken(), spec.childId())) return existing;
        current.run();
        DispatchPermit dispatch = begin(spec.childId());
        boolean receiptSaved = false;
        try {
            IoBoundary boundary = ioBoundary(dispatch, spec);
            boundary.beforeIo();
            ChildResult result = Objects.requireNonNull(call.call(boundary), "Child receipt is required");
            if (result.jobId() != null && !live(dispatch)) {
                runs.recordLateJob(dispatch, result.jobId()); receiptSaved = true;
                throw new SecurityException(parent == null ? "STEP_EXECUTION_FENCED" : "CALL_EXECUTION_FENCED");
            }
            boundary.beforeIo();
            if (result.capacityKind() != null) runs.deferUnadmitted(dispatch, result.capacityKind());
            else if (result.jobId() != null) runs.recordWaiting(dispatch, result.jobId());
            else runs.publishReady(dispatch, result.artifact());
            receiptSaved = true;
        } finally {
            try { if (!receiptSaved && runs.mayDispatch(dispatch)) runs.markUnresolved(dispatch); }
            finally { runs.callbackExited(dispatch); }
        }
        return runs.child(step.runToken(), spec.childId()).orElseThrow();
    }

    Map<String, ArtifactRef> local(ChildSpec spec, Approval approval, ArtifactAuthorizer authorizer,
                                   LocalCall calculation) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("LOCAL_COMPUTE_REQUIRES_COMMITTED_PREPARATION");
        current.run(); Objects.requireNonNull(spec); Objects.requireNonNull(calculation); Objects.requireNonNull(authorizer);
        prepareAction.accept(spec);
        ChildRecord existing = runs.prepareLocalChild(step.runToken(), spec, approval, authorizer);
        if (existing.state() == ChildState.READY) {
            var outputs = runs.localOutputs(step.runToken(), spec.childId(), authorizer);
            current.run();
            return outputs;
        }
        if (existing.reason() == UnresolvedReason.LOCAL_RESULT_INVALID)
            throw new LocalResultInvalid(new IllegalArgumentException("LOCAL_RESULT_INVALID"));
        DispatchPermit dispatch = existing.state() == ChildState.PREPARED ? begin(spec.childId())
                : parent == null ? runs.beginLocalReplay(step.runToken(), spec.childId(), approval, authorizer)
                : runs.beginLocalReplay(step.runToken(), spec.childId(), approval, authorizer, parent);
        boolean saved = false;
        try {
            LocalBoundary boundary = localBoundary(dispatch, approval, authorizer);
            boundary.requireCurrent();
            Map<String, ArtifactDraft> drafts = Map.copyOf(calculation.compute(boundary));
            boundary.requireCurrent();
            runs.publishLocalReady(step, dispatch, approval, drafts, authorizer);
            saved = true;
        } catch (IllegalArgumentException rejected) {
            if (runs.mayDispatch(dispatch)) { runs.markLocalInvalid(dispatch); saved = true; }
            throw new LocalResultInvalid(rejected);
        } finally {
            try { if (!saved && runs.mayDispatch(dispatch)) runs.markUnresolved(dispatch); }
            finally { runs.callbackExited(dispatch); }
        }
        current.run();
        return runs.localOutputs(step.runToken(), spec.childId(), authorizer);
    }

    private DispatchPermit begin(String childId) {
        return parent == null ? runs.beginDispatch(step.runToken(), childId) : runs.beginDispatch(step.runToken(), childId, parent);
    }

    private IoBoundary ioBoundary(DispatchPermit dispatch, ChildSpec child) {
        return new IoBoundary() {
            @Override public WireRequest request() { current.run(); return child.wire(); }
            @Override public void beforeIo() {
                current.run();
                if (!runs.mayDispatch(dispatch)) throw new SecurityException("CHILD_EXECUTION_FENCED");
            }
        };
    }

    private LocalBoundary localBoundary(DispatchPermit dispatch, Approval approval, ArtifactAuthorizer authorizer) {
        return new LocalBoundary() {
            @Override public void requireCurrent() {
                current.run();
                if (!runs.mayDispatch(dispatch)) throw new SecurityException("LOCAL_EXECUTION_FENCED");
            }
            @Override public Artifact readInput(String name) {
                requireCurrent();
                ArtifactMetadata expected = approval.invocation().inputs().get(name);
                if (expected == null) throw new IllegalArgumentException("LOCAL_INPUT_NOT_DECLARED");
                Artifact input = runs.readArtifact(step.runToken().definition().caller(), expected.ref().artifactId(), authorizer);
                if (!expected.equals(input.metadata())) throw new IllegalArgumentException("LOCAL_INPUT_CHANGED");
                requireCurrent();
                return input;
            }
        };
    }

    private boolean live(DispatchPermit dispatch) {
        try { current.run(); return runs.mayDispatch(dispatch); }
        catch (SecurityException | IllegalStateException denied) { return false; }
    }
}
