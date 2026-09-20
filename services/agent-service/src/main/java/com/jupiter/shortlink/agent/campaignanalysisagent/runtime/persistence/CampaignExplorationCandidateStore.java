package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.CompletionCriterionRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A verified local candidate remains distinct from outer Step success or a new Plan. */
public interface CampaignExplorationCandidateStore {
    enum Verdict { COMPLETE, NEEDS_INPUT, REPLAN_REQUESTED, NO_PROGRESS_REPORTED, REJECTED }

    record Assessment(String assessmentId, String modelChildId, String responseHash, String candidateHash,
                      String registryId, Verdict verdict, Map<String, ArtifactRef> outputs,
                      List<CompletionCriterionRegistry.Result> criteria, List<String> reasonCodes) {
        public Assessment {
            outputs = Map.copyOf(outputs); criteria = List.copyOf(criteria); reasonCodes = List.copyOf(reasonCodes);
        }
    }

    String configurationId(RunDefinition definition, String stepId);
    Assessment assess(StepPermit step, String modelChildId);
    /** Reads through current source, policy and Artifact authorization; never a cached authorization result. */
    Optional<Assessment> assessment(RunToken token, String stepId);

    /** Revalidates the stored COMPLETE receipt and publishes only its outputs in the same Step transaction. */
    CampaignStepStore.StepRecord settleComplete(StepPermit permit);
}
