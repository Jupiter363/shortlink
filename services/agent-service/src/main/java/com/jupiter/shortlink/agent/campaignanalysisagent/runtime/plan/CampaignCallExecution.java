package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Invocation-scoped child execution for a genuine live CALL. The registered adapter still owns
 * typed input/source authorization. No fake fixed Step/Action, callback re-entry or runner exists here.
 */
public final class CampaignCallExecution implements CapabilityExecution {
    private final CallPermit permit;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignExplorationCallStore calls;
    private final BooleanSupplier authorized;
    private final CallSpec specification;
    private final CampaignChildExecution children;
    private volatile boolean closed;

    public CampaignCallExecution(CallPermit permit, CampaignRunStore runs, CampaignStepStore steps,
                                 CampaignExplorationCallStore calls, BooleanSupplier authorized) {
        this.permit = Objects.requireNonNull(permit); this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps); this.calls = Objects.requireNonNull(calls);
        this.authorized = Objects.requireNonNull(authorized);
        if (permit.step() == null || permit.step().runToken() == null) throw new SecurityException("CALL_EXECUTION_FENCED");
        this.specification = calls.call(permit.step().runToken(), permit.callId())
                .orElseThrow(() -> new SecurityException("CALL_EXECUTION_FENCED")).spec();
        var identity = CampaignExplorationCallStore.identity(permit.step().runToken().definition(), permit.step().stepId(),
                specification.modelChildId(), specification.toolCallId());
        if (!identity.callId().equals(permit.callId()) || !identity.actionId().equals(permit.actionId())
                || !specification.callId().equals(permit.callId()) || !specification.actionId().equals(permit.actionId())
                || !specification.stepId().equals(permit.step().stepId())) throw new SecurityException("CALL_EXECUTION_FENCED");
        requireCurrent();
        this.children = new CampaignChildExecution(permit.step(), runs, this::requireCurrent, this::prepareAction, permit);
    }

    public void requireCurrent() {
        if (closed || !authorized.getAsBoolean() || !steps.mayExecute(permit.step()) || !calls.mayExecute(permit))
            throw new SecurityException("CALL_EXECUTION_FENCED");
        CallSpec current = calls.call(permit.step().runToken(), permit.callId())
                .orElseThrow(() -> new SecurityException("CALL_EXECUTION_FENCED")).spec();
        if (!specification.equals(current)) throw new SecurityException("CALL_EXECUTION_CHANGED");
    }

    public ChildRecord child(ChildSpec spec, CampaignStepExecution.ChildCall call) throws Exception {
        return children.child(spec, call);
    }

    public Map<String, ArtifactRef> local(ChildSpec spec, Approval approval, ArtifactAuthorizer authorizer,
                                          CampaignStepExecution.LocalCall calculation) throws Exception {
        return children.local(spec, approval, authorizer, calculation);
    }

    private void prepareAction(ChildSpec child) {
        requireCurrent();
        if (!specification.actionId().equals(child.actionId())) throw new IllegalArgumentException("CALL_CHILD_ACTION_MISMATCH");
        var executor = specification.executor();
        // CALL's FK already owns this Action; an idempotent prepare verifies its immutable definition.
        runs.prepareAction(permit.step().runToken(), new ActionSpec(specification.actionId(), specification.stepId(),
                executor.kind().name(), executor.name(), executor.version(), CampaignExplorationCallStore.encode(specification)));
    }

    /** Closing this holder revokes its use; only the actual outer delegate finally exits the CALL. */
    @Override public void close() { closed = true; }
}
