package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.NativePlanGraph;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import java.util.Objects;

/**
 * Compiles a candidate native scan without constructing a JDBC driver or invoking a graph.
 * The instance is bound to one trusted base run; callers must create a new instance for each
 * replan invocation so frozen inputs and identity cannot bleed across runs.
 */
public final class NativeGraphPrecompiler implements ReplanCoordinator.GraphPrecompiler {
    private final FrozenInputSet inputs;
    private final PlanValidator validator;
    private final NativePlanGraph.RunIdentity baseIdentity;
    private final ProcessExecutionScope processScope;

    public NativeGraphPrecompiler(CampaignRunStore.RunToken baseRun, FrozenCampaignRun frozen,
                                   PlanValidator validator, ProcessExecutionScope processScope) {
        Objects.requireNonNull(baseRun);
        Objects.requireNonNull(frozen);
        this.validator = Objects.requireNonNull(validator);
        this.processScope = processScope;
        if (!baseRun.definition().runId().equals(frozen.plan().runId())
                || !baseRun.definition().planId().equals(frozen.plan().planId())
                || baseRun.definition().revision() != frozen.plan().revision())
            throw new IllegalArgumentException("REPLAN_BASE_DEFINITION_MISMATCH");
        var caller = baseRun.definition().caller();
        this.inputs = frozen.inputs();
        this.baseIdentity = new NativePlanGraph.RunIdentity(caller.tenantId(), caller.subject(),
                caller.authVersion(), baseRun.definition().sessionId(), baseRun.definition().runId(),
                baseRun.definition().planId(), baseRun.definition().revision(),
                frozen.runnerVersion(), frozen.topologyVersion());
    }

    @Override
    public ReplanCoordinator.PreparedGraph precompile(PlanSpec candidate,
                                                       PlanningAssessment assessment) throws Exception {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(assessment);
        NativePlanGraph.RunIdentity identity = new NativePlanGraph.RunIdentity(
                baseIdentity.tenantId(), baseIdentity.subjectId(), baseIdentity.authVersion(),
                baseIdentity.sessionId(), candidate.runId(), candidate.planId(), candidate.revision(),
                baseIdentity.runnerVersion(), baseIdentity.topologyVersion());
        MemorySaver saver = MemorySaver.builder().build();
        NativePlanGraph.compile(candidate, inputs, assessment, validator, identity, saver,
                new InertDriver(), processScope);
        return new ReplanCoordinator.PreparedGraph(candidate.planId(), candidate.revision(),
                ReplanRequest.planHash(candidate));
    }

    /** Compile must never call the domain driver; execution belongs to the committed revision. */
    private static final class InertDriver implements NativePlanGraph.Driver {
        @Override public boolean mayAdvance() { throw new AssertionError("PRECOMPILE_EXECUTED"); }
        @Override public NativePlanGraph.StepStatus status(String stepId) { throw new AssertionError("PRECOMPILE_EXECUTED"); }
        @Override public void advance(PlanSpec.Step step) { throw new AssertionError("PRECOMPILE_EXECUTED"); }
    }
}
