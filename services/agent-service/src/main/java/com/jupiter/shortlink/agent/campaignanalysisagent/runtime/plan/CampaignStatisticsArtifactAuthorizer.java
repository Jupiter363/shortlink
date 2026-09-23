package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.Objects;

/** Authorizes only a current statistics Run's exact, published child output under live resource rights. */
public final class CampaignStatisticsArtifactAuthorizer implements ArtifactAuthorizer {
    private final CampaignRunStore runs;
    private final CampaignStatisticsRunAuthorizer runAuthorizer;
    private final CampaignStatisticsQueryAuthorizer queryAuthorizer;

    public CampaignStatisticsArtifactAuthorizer(CampaignRunStore runs,
            CampaignStatisticsRunAuthorizer runAuthorizer, CampaignStatisticsQueryAuthorizer queryAuthorizer) {
        this.runs = Objects.requireNonNull(runs);
        this.runAuthorizer = Objects.requireNonNull(runAuthorizer);
        this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer);
    }

    @Override
    public boolean mayRead(Caller caller, ArtifactMetadata artifact) {
        try {
            if (caller == null || artifact == null || artifact.ref() == null || !caller.equals(artifact.owner())
                    || !CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(artifact.ref().type())
                    || !CampaignStatisticsResultStore.SCHEMA_VERSION.equals(artifact.ref().schemaVersion()))
                return false;
            var run = runs.loadRun(caller, artifact.runId()).orElse(null);
            if (run == null || run.status() != RunStatus.ACTIVE
                    || !caller.equals(run.definition().caller())
                    || !run.definition().planId().equals(artifact.planId())
                    || run.definition().revision() != artifact.revision()
                    || !StatisticsJobFixedExecutor.REF.version().equals(artifact.executorVersion()))
                return false;
            var bound = FrozenStatisticsJobQuery.resolve(run.definition(), StatisticsJobFixedExecutor.REF).values()
                    .stream().filter(query -> query.target().artifactId().equals(artifact.ref().artifactId()))
                    .findFirst().orElse(null);
            if (bound == null || !bound.scopeRef().equals(artifact.ref().scopeRef())
                    || !bound.periodsRef().equals(artifact.ref().periodsRef())
                    || !bound.child().childId().equals(artifact.childId())
                    || !bound.child().actionId().equals(artifact.actionId())) return false;
            var child = runs.child(run.token(), bound.child().childId()).orElse(null);
            if (child == null || child.state() != ChildState.READY || !bound.child().equals(child.spec())
                    || !artifact.ref().artifactId().equals(child.artifactId())) return false;
            if (!runAuthorizer.mayExecute(caller, FrozenCampaignRun.read(run.definition()).inputs())) return false;
            if (!queryAuthorizer.mayUse(new AgentPrincipal(caller.tenantId(), caller.subject(), caller.authVersion(), false),
                    bound.scopeRef(), bound.periodsRef(), bound.request())) return false;
            var latest = runs.loadRun(caller, artifact.runId()).orElse(null);
            return latest != null && latest.status() == RunStatus.ACTIVE
                    && run.definition().equals(latest.definition());
        } catch (RuntimeException denied) {
            return false;
        }
    }
}
