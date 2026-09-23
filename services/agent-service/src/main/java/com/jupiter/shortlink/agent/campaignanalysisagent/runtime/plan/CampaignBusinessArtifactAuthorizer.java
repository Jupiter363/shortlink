package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import java.util.Map;
import java.util.Objects;

/** Published child/local-output proof plus current source membership for each independent business branch. */
public final class CampaignBusinessArtifactAuthorizer implements ArtifactAuthorizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CampaignRunStore runs;
    private final CampaignScopeStore scopes;
    private final CampaignBusinessPlanFactory plans;
    private final CampaignDependencyAnalysisPlanFactory dependencyPlans;
    private final CampaignBusinessAuthorizer inputs;
    private final AgentAuthorityClient authority;
    private final CampaignExplorationRuntimeFactory exploration;
    private final ArtifactAuthorizer adopted;

    public CampaignBusinessArtifactAuthorizer(CampaignRunStore runs, CampaignScopeStore scopes,
            CampaignBusinessPlanFactory plans, CampaignDependencyAnalysisPlanFactory dependencyPlans,
            CampaignBusinessAuthorizer inputs, AgentAuthorityClient authority, CampaignExplorationRuntimeFactory exploration) {
        this(runs,scopes,plans,dependencyPlans,inputs,authority,exploration,null);
    }

    public CampaignBusinessArtifactAuthorizer(CampaignRunStore runs, CampaignScopeStore scopes,
            CampaignBusinessPlanFactory plans, CampaignDependencyAnalysisPlanFactory dependencyPlans,
            CampaignBusinessAuthorizer inputs, AgentAuthorityClient authority, CampaignExplorationRuntimeFactory exploration,
            ArtifactAuthorizer adopted) {
        this.runs = Objects.requireNonNull(runs); this.scopes = Objects.requireNonNull(scopes);
        this.plans = Objects.requireNonNull(plans); this.dependencyPlans = Objects.requireNonNull(dependencyPlans);
        this.inputs = Objects.requireNonNull(inputs); this.authority = Objects.requireNonNull(authority);
        this.exploration = Objects.requireNonNull(exploration);
        this.adopted = adopted;
    }

    public boolean mayRead(Caller caller, ArtifactMetadata artifact) {
        try {
            if (caller == null || artifact == null || artifact.ref() == null || !caller.equals(artifact.owner())) return false;
            var current = runs.loadRun(caller, artifact.runId()).orElse(null);
            if (current == null || current.status() != RunStatus.ACTIVE) return false;
            var definition = current.definition();
            plans.validateDefinition(definition);
            var frozen = FrozenCampaignRun.read(definition);
            if (!inputs.mayExecute(caller, frozen.inputs())) return false;
            if (!sameRun(definition, artifact)) return adopted != null && artifact.revision() < definition.revision()
                    && definition.planId().equals(artifact.planId()) && adopted.mayRead(caller,artifact);
            ArtifactAuthorizer identity = (owner, stored) -> caller.equals(owner) && sameRun(definition, stored);
            if (!artifact.equals(runs.inspectArtifact(caller, artifact.ref().artifactId(), identity))) return false;
            var child = runs.child(current.token(), artifact.childId()).orElse(null);
            if (child == null || child.state() != ChildState.READY || child.reason() != null
                    || !artifact.actionId().equals(child.spec().actionId())) return false;
            var action = runs.inspectAction(current.token(), artifact.actionId()).orElse(null);
            var step = action == null ? null : frozen.plan().steps().stream()
                    .filter(value -> value.stepId().equals(action.stepId())).findFirst().orElse(null);
            if (step == null) return false;
            var queryGate = step.executionMode() == PlanSpec.ExecutionMode.REACT
                    || CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(artifact.ref().type())
                    ? new CampaignBusinessQueryAuthorizer(authority, plans, dependencyPlans, inputs, definition) : null;
            boolean bound;
            if (step.executionMode() == PlanSpec.ExecutionMode.REACT) {
                bound = exploration.mayReadStatistics(current.token(), artifact, plans.catalog(), plans.contracts(),
                        inputs.inputAuthorizer(frozen.inputs()), this, queryGate);
            } else {
                if (step.executor() == null || !artifact.executorVersion().equals(action.executorVersion())
                        || !step.executor().kind().name().equals(action.executorKind())
                        || !step.executor().name().equals(action.executorName())
                        || !step.executor().version().equals(action.executorVersion())
                        || !FrozenCampaignRun.encode(step).equals(action.definitionJson())) return false;
                if (StatisticsJobFixedExecutor.REF.equals(step.executor())) {
                    var query = FrozenStatisticsJobQuery.resolve(definition, StatisticsJobFixedExecutor.REF).get(step.stepId());
                    bound = query != null && query.child().equals(child.spec())
                            && query.target().artifactId().equals(artifact.ref().artifactId())
                            && artifact.ref().artifactId().equals(child.artifactId())
                            && query.scopeRef().equals(artifact.ref().scopeRef()) && query.periodsRef().equals(artifact.ref().periodsRef())
                            && CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(artifact.ref().type())
                            && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(artifact.ref().schemaVersion())
                            && queryGate.mayReadEvidence(CampaignBusinessAuthorizer.principal(caller), query.scopeRef(), query.periodsRef(), query.request());
                } else bound = dependency(current.token(), artifact, child, step, queryGate);
            }
            var latest = runs.loadRun(caller, artifact.runId()).orElse(null);
            return bound && latest != null && latest.status() == RunStatus.ACTIVE && definition.equals(latest.definition());
        } catch (RuntimeException denied) { return false; }
    }

    private boolean dependency(RunToken token, ArtifactMetadata artifact, ChildRecord child, PlanSpec.Step step,
                               CampaignBusinessQueryAuthorizer queries) {
        var definition = token.definition();
        var collections = FrozenScopeCollection.resolve(definition);
        var selections = FrozenDeclineSelection.templates(definition);
        var dimensions = FrozenDimensionChange.resolveTemplates(definition, FrozenDimensionChange.REF_V2);
        var dimension = dimensions.get(step.stepId());
        var selection = selections.get(dimension == null ? step.stepId() : dimension.upstreamStepId());
        var scope = collections.get(selection == null ? step.stepId() : selection.upstreamStepId());
        if (scope == null || (!FrozenScopeCollection.REF.equals(step.executor()) && selection == null)) return false;
        var collection = scopes.load(token, scope.definition().collectionId());
        if (collection == null || !scope.definition().equals(collection.definition())
                || collection.state() == CampaignScopeStore.State.INVALID || collection.failureCode() != null
                || collection.pageCount() < 1 || collection.enumerationVersion() == null
                || !collection.enumerationVersion().matches("[a-f0-9]{64}")) return false;
        boolean bound;
        if (FrozenScopeCollection.REF.equals(step.executor())) {
            bound = CampaignDependencyAnalysisArtifactAuthorizer.scope(artifact, child, scope);
        } else if (CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(artifact.ref().type())) {
            bound = CampaignDependencyAnalysisArtifactAuthorizer.statistics(artifact, child, step, selection, dimension)
                    && queries.mayReadEvidence(CampaignBusinessAuthorizer.principal(definition.caller()), artifact.ref().scopeRef(),
                    artifact.ref().periodsRef(), request(child.spec().wire().bodyJson()));
        } else {
            bound = CampaignDependencyAnalysisArtifactAuthorizer.local(artifact, child, step)
                    && runs.isLocalOutputBound(token, child.spec().childId(), artifact.ref());
        }
        return bound && inputs.mayReadEvidence(definition.caller(), FrozenCampaignRun.read(definition).inputs(),
                scope.definition().gid(), collection.enumerationVersion());
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> request(String json) {
        try { return JSON.readValue(json, Map.class); }
        catch (Exception invalid) { throw new IllegalArgumentException("BUSINESS_CHILD_REQUEST_INVALID", invalid); }
    }
    private static boolean sameRun(RunDefinition definition, ArtifactMetadata artifact) {
        return artifact != null && definition.caller().equals(artifact.owner()) && definition.runId().equals(artifact.runId())
                && definition.planId().equals(artifact.planId()) && definition.revision() == artifact.revision();
    }
}
