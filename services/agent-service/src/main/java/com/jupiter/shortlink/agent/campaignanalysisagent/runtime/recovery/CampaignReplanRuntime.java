package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.ReplanReceiptStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.util.Objects;

/** Runtime bound to one trusted owner, token, and persisted frozen base revision. */
public final class CampaignReplanRuntime {
    private final CampaignRunStore.Caller owner;
    private final CampaignRunStore.RunToken baseRun;
    private final FrozenCampaignRun frozen;
    private final PlanValidator planValidator;
    private final ProcessExecutionScope processScope;
    private final CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer;
    private final ReplanReceiptStore receipts;
    private final ReplanCoordinator.RevisionApplier applier;

    CampaignReplanRuntime(CampaignRunStore.Caller owner,
                          CampaignRunStore.RunToken baseRun,
                          FrozenCampaignRun frozen,
                          PlanValidator planValidator,
                          ProcessExecutionScope processScope,
                          CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer,
                          ReplanReceiptStore receipts,
                          ReplanCoordinator.RevisionApplier applier) {
        this.owner = Objects.requireNonNull(owner);
        this.baseRun = Objects.requireNonNull(baseRun);
        this.frozen = Objects.requireNonNull(frozen);
        this.planValidator = Objects.requireNonNull(planValidator);
        this.processScope = processScope;
        this.capabilityAuthorizer = Objects.requireNonNull(capabilityAuthorizer);
        this.receipts = Objects.requireNonNull(receipts);
        this.applier = Objects.requireNonNull(applier);
    }

    public CampaignRunStore.Caller owner() { return owner; }

    public CampaignRunStore.RunToken baseRun() { return baseRun; }

    public FrozenCampaignRun frozen() { return frozen; }

    /**
     * Executes a typed request after rebinding it to this runtime's owner and base token.
     * A native graph precompiler is allocated for every invocation and is never retained.
     */
    public CampaignReplanApplicationService.Response execute(
            CampaignReplanApplicationService.Request request) throws Exception {
        Objects.requireNonNull(request, "REPLAN_REQUEST_REQUIRED");
        if (!owner.equals(request.owner()) || !baseRun.equals(request.runToken())) {
            throw new IllegalArgumentException("REPLAN_RUNTIME_BINDING_MISMATCH");
        }

        NativeGraphPrecompiler precompiler = new NativeGraphPrecompiler(
                baseRun, frozen, planValidator, processScope);
        ReplanCoordinator coordinator = new ReplanCoordinator(receipts, precompiler, applier);
        return new CampaignReplanApplicationService(coordinator, capabilityAuthorizer).execute(request);
    }
}
