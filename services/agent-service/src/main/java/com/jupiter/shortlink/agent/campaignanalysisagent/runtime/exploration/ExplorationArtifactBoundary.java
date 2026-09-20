package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;

/** Registered server evidence-boundary checker; never supplied by a model or candidate. */
public interface ExplorationArtifactBoundary {
    String configurationId();
    boolean permits(RunToken token, PlanSpec.Step step, ArtifactMetadata artifact, ArtifactAuthorizer authorizer);

    static ExplorationArtifactBoundary exact() { return Exact.INSTANCE; }

    enum Exact implements ExplorationArtifactBoundary {
        INSTANCE;
        @Override public String configurationId() { return "exact-scope-period/v1"; }
        @Override public boolean permits(RunToken token, PlanSpec.Step step, ArtifactMetadata artifact, ArtifactAuthorizer authorizer) {
            return step.explorationPolicy().scopeRef().equals(artifact.ref().scopeRef())
                    && step.explorationPolicy().periodsRef().equals(artifact.ref().periodsRef());
        }
    }
}
