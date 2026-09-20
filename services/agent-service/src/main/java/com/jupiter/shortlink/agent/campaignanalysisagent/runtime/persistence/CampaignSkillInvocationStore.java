package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.OutputBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Approval;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCallStore.CallPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Server-owned completion contract for one non-exploring Skill callback, not an execution plan. */
public interface CampaignSkillInvocationStore {
    enum State { RUNNING, WAITING, COMPLETED }

    /** Supplied by the registered server Skill, never decoded from model arguments. */
    record CompletionSpec(String finalLocalChildId, String outputContractRef, Map<String, OutputBinding> outputs) {
        public CompletionSpec {
            if (finalLocalChildId == null || !finalLocalChildId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")
                    || outputContractRef == null || outputContractRef.isBlank() || outputs == null || outputs.isEmpty())
                throw new IllegalArgumentException("SKILL_COMPLETION_SPEC_INVALID");
            outputs = Map.copyOf(outputs);
            if (outputs.keySet().stream().anyMatch(name -> !name.matches("[A-Za-z][A-Za-z0-9_.-]{0,95}"))
                    || new HashSet<>(outputs.values().stream().map(OutputBinding::artifactId).toList()).size() != outputs.size())
                throw new IllegalArgumentException("SKILL_COMPLETION_OUTPUTS_INVALID");
        }
    }

    record InvocationRecord(CompletionSpec spec, State state, long rowVersion, String completionId,
                            Map<String, ArtifactRef> outputs) {
        public InvocationRecord { outputs = Map.copyOf(outputs); }
    }

    InvocationRecord prepare(CallPermit permit, CompletionSpec spec, Approval sourceModel, ArtifactAuthorizer authorizer);

    /** All actual pending children must be declared; unknown/live/capacity states cannot become an ordinary wait. */
    InvocationRecord awaitContinuation(CallPermit permit, Set<String> childIds);

    /** Only the exact persisted WAITING invocation may admit a new callback attempt. */
    CallPermit beginContinuation(StepPermit step, String callId, long expectedInvocationVersion,
                                 Approval sourceModel, ArtifactAuthorizer authorizer);

    InvocationRecord completeInvocation(CallPermit permit, ArtifactAuthorizer authorizer);

    /** Revalidates a sealed invocation under the current Run and ACL, without reviving its old callback. */
    InvocationRecord readCompletion(RunToken token, String callId, ArtifactAuthorizer authorizer);

    Optional<InvocationRecord> invocation(RunToken token, String callId);
}
