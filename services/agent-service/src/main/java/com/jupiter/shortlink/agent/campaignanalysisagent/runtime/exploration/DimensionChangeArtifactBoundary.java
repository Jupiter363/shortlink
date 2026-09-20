package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangePublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangeResultReader;
import java.util.Objects;

/** Only the actual selected subset's source group can extend an exact exploration boundary. */
public final class DimensionChangeArtifactBoundary implements ExplorationArtifactBoundary {
    private final DimensionChangeResultReader reader;
    public DimensionChangeArtifactBoundary(DimensionChangeResultReader reader) { this.reader = Objects.requireNonNull(reader); }
    @Override public String configurationId() { return "dimension-selected-boundary/v1:" + DimensionChangeResultReader.VERSION; }
    @Override public boolean permits(RunToken token, PlanSpec.Step step, ArtifactMetadata artifact, ArtifactAuthorizer authorizer) {
        if (!DimensionChangePublisher.TYPE.equals(artifact.ref().type()))
            return ExplorationArtifactBoundary.exact().permits(token, step, artifact, authorizer);
        var verified = reader.read(token, artifact, authorizer, 0);
        return step.explorationPolicy().scopeRef().equals(verified.sourceScopeRef())
                && step.explorationPolicy().periodsRef().equals(artifact.ref().periodsRef());
    }
}
