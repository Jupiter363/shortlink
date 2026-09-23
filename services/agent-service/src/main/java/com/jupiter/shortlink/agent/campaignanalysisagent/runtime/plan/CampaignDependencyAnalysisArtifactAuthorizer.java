package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.OutputBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Current read authority for the server-generated collection, decline and dimension dependency plan. */
public final class CampaignDependencyAnalysisArtifactAuthorizer implements ArtifactAuthorizer {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final CampaignRunStore runs;
    private final CampaignScopeStore scopes;
    private final CampaignDependencyAnalysisAuthorizer inputs;
    /** One bounded immutable parse only; current ledger state and authorization never enter this cache. */
    private volatile ParsedDefinition lastDefinition;

    public CampaignDependencyAnalysisArtifactAuthorizer(CampaignRunStore runs, CampaignScopeStore scopes,
            CampaignDependencyAnalysisAuthorizer inputs) {
        this.runs = Objects.requireNonNull(runs);
        this.scopes = Objects.requireNonNull(scopes);
        this.inputs = Objects.requireNonNull(inputs);
    }

    @Override
    public boolean mayRead(Caller caller, ArtifactMetadata artifact) {
        try {
            if (caller == null || artifact == null || artifact.ref() == null
                    || !caller.equals(artifact.owner())) return false;
            var current = runs.loadRun(caller, artifact.runId()).orElse(null);
            if (current == null || current.status() != RunStatus.ACTIVE
                    || !sameRun(current.definition(), artifact)) return false;
            RunDefinition definition = current.definition();
            ParsedDefinition parsed = parsed(definition);
            FrozenCampaignRun frozen = parsed.frozen();
            var scopeDefinition = parsed.scope().definition();
            var collection = scopes.load(current.token(), scopeDefinition.collectionId());
            if (collection == null || !scopeDefinition.equals(collection.definition())
                    || collection.state() == CampaignScopeStore.State.INVALID || collection.failureCode() != null
                    || collection.pageCount() < 1 || collection.enumerationVersion() == null
                    || !collection.enumerationVersion().matches("[a-f0-9]{64}")) return false;

            // This inner predicate establishes immutable ledger identity only, never read authority.
            // Calling this authorizer recursively would loop while a LOCAL output verifies its inputs.
            ArtifactAuthorizer identity = (owner, stored) -> caller.equals(owner) && sameRun(definition, stored);
            if (!artifact.equals(runs.inspectArtifact(caller, artifact.ref().artifactId(), identity))) return false;
            ChildRecord child = runs.child(current.token(), artifact.childId()).orElse(null);
            if (child == null || child.state() != ChildState.READY || child.reason() != null
                    || !artifact.childId().equals(child.spec().childId())
                    || !artifact.actionId().equals(child.spec().actionId())) return false;
            ActionSpec action = runs.inspectAction(current.token(), artifact.actionId()).orElse(null);
            FrozenStep frozenStep = action == null ? null : parsed.steps().get(action.stepId());
            PlanSpec.Step step = frozenStep == null ? null : frozenStep.step();
            if (step == null || step.executionMode() != PlanSpec.ExecutionMode.FIXED || step.executor() == null
                    || !artifact.actionId().equals(action.actionId())
                    || !artifact.executorVersion().equals(action.executorVersion())
                    || !step.executor().kind().name().equals(action.executorKind())
                    || !step.executor().name().equals(action.executorName())
                    || !step.executor().version().equals(action.executorVersion())
                    || !frozenStep.encoded().equals(action.definitionJson())) return false;

            boolean bound;
            if (CampaignDependencyAnalysisPlanFactory.COLLECT.equals(step.stepId())) {
                bound = scope(artifact, child, parsed.scope());
            } else if (CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(artifact.ref().type())) {
                bound = statistics(artifact, child, step, parsed.selection(), parsed.dimension());
            } else {
                bound = local(artifact, child, step)
                        && runs.isLocalOutputBound(current.token(), child.spec().childId(), artifact.ref());
            }
            if (!bound || !inputs.mayReadEvidence(caller, frozen.inputs(), collection.enumerationVersion())) return false;
            var latest = runs.loadRun(caller, artifact.runId()).orElse(null);
            return latest != null && latest.status() == RunStatus.ACTIVE
                    && definition.equals(latest.definition());
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private ParsedDefinition parsed(RunDefinition definition) {
        ParsedDefinition previous = lastDefinition;
        if (previous != null && definition.equals(previous.definition())) return previous;
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        var scopeDefinitions = FrozenScopeCollection.resolve(definition);
        var selections = FrozenDeclineSelection.templates(definition);
        var dimensions = FrozenDimensionChange.resolveTemplates(definition, FrozenDimensionChange.REF_V2);
        if (frozen.plan().steps().size() != 3
                || !scopeDefinitions.keySet().equals(Set.of(CampaignDependencyAnalysisPlanFactory.COLLECT))
                || !selections.keySet().equals(Set.of(CampaignDependencyAnalysisPlanFactory.SELECT))
                || !dimensions.keySet().equals(Set.of(CampaignDependencyAnalysisPlanFactory.DIMENSION))
                || !CampaignDependencyAnalysisPlanFactory.COLLECT.equals(selections.get(
                        CampaignDependencyAnalysisPlanFactory.SELECT).upstreamStepId())
                || !CampaignDependencyAnalysisPlanFactory.SELECT.equals(dimensions.get(
                        CampaignDependencyAnalysisPlanFactory.DIMENSION).upstreamStepId()))
            throw new IllegalArgumentException("DEPENDENCY_ARTIFACT_PLAN_INVALID");
        Map<String, FrozenStep> steps = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps())
            if (steps.putIfAbsent(step.stepId(), new FrozenStep(step, FrozenCampaignRun.encode(step))) != null)
                throw new IllegalArgumentException("DEPENDENCY_ARTIFACT_PLAN_INVALID");
        ParsedDefinition parsed = new ParsedDefinition(definition, frozen,
                scopeDefinitions.get(CampaignDependencyAnalysisPlanFactory.COLLECT),
                selections.get(CampaignDependencyAnalysisPlanFactory.SELECT),
                dimensions.get(CampaignDependencyAnalysisPlanFactory.DIMENSION), Map.copyOf(steps));
        lastDefinition = parsed;
        return parsed;
    }

    static boolean statistics(ArtifactMetadata artifact, ChildRecord child, PlanSpec.Step step,
            FrozenDeclineSelection.Template selection, FrozenDimensionChange.Template dimension) {
        if (!CampaignStatisticsResultStore.SCHEMA_VERSION.equals(artifact.ref().schemaVersion())
                || child.spec().mode() != ChildMode.ASYNC || child.jobId() == null
                || !artifact.ref().artifactId().equals(child.artifactId()) || child.spec().wire() == null
                || !"POST".equals(child.spec().wire().method())
                || !FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path())) return false;
        JsonNode request = tree(child.spec().wire().bodyJson());
        boolean selecting = FrozenDeclineSelection.REF_V2.equals(step.executor());
        if (!selecting && !FrozenDimensionChange.REF_V2.equals(step.executor())) return false;
        String gid = selecting ? selection.gid() : dimension.gid();
        var periods = selecting ? selection.periods() : dimension.periods();
        if (!gid.equals(request.path("gid").asText())
                || !(selecting ? "LINK_METRICS" : "DIMENSION_BREAKDOWN").equals(request.path("queryKind").asText())
                || !child.spec().requestId().equals(request.path("requestId").asText())
                || !artifact.ref().scopeRef().equals(request.path("scope").path("parentScopeRef").asText())
                || periods.stream().noneMatch(period -> period.periodsRef().equals(artifact.ref().periodsRef())
                        && period.startDate().equals(request.path("startDate").asText())
                        && period.endDate().equals(request.path("endDate").asText()))) return false;
        return selecting || (JSON.valueToTree(dimension.dimensions()).equals(request.path("dimensions"))
                && JSON.valueToTree(dimension.filters()).equals(request.path("filters")));
    }

    static boolean scope(ArtifactMetadata artifact, ChildRecord child, FrozenScopeCollection.Bound bound) {
        var ref = artifact.ref();
        var collection = bound.definition();
        if (child.spec().mode() != ChildMode.SYNC || !ref.artifactId().equals(child.artifactId())
                || !collection.action().actionId().equals(artifact.actionId())
                || !collection.expiresAt().equals(ref.expiresAt()) || !"scope-enumeration".equals(ref.periodsRef())
                || child.spec().wire() == null || !"POST".equals(child.spec().wire().method())
                || !AgentAuthorityClient.GROUP_MEMBERS_PATH.equals(child.spec().wire().path())) return false;
        GroupMembersPage.Request request = GroupMembersPage.Request.fromMap(object(child.spec().wire().bodyJson()));
        if (!collection.gid().equals(request.gid())
                || !collection.collectionId().equals(tree(artifact.provenanceJson()).path("collectionId").asText()))
            return false;
        if ("ScopeArtifact".equals(ref.type()))
            return FrozenScopeCollection.OUTPUT_CONTRACT.equals(ref.schemaVersion())
                    && ("scope-artifact-" + CampaignRunStore.sha256(collection.collectionId())).equals(ref.artifactId());
        // The collector also reauthorizes accepted nonterminal pages before resuming its cursor.
        // Full page ordinal/hash and final collection membership are verified by CampaignScopeStore.
        return "ScopePageRef".equals(ref.type()) && "campaign-scope-page-ref/v1".equals(ref.schemaVersion())
                && ("scope-collection-" + CampaignRunStore.sha256(collection.collectionId())).equals(ref.scopeRef());
    }

    static boolean local(ArtifactMetadata artifact, ChildRecord child, PlanSpec.Step step) {
        var invocation = child.spec().localInvocation();
        if (child.spec().mode() != ChildMode.LOCAL || invocation == null || child.artifactId() != null
                || !"1".equals(invocation.contractVersion())
                || !artifact.ref().expiresAt().equals(invocation.expiresAt())) return false;
        Map<String, Contract> expected;
        if (FrozenDeclineSelection.REF_V2.equals(step.executor())) {
            expected = switch (invocation.contractName()) {
                case "decline-selection-page" -> Map.of(
                        "comparisonPage", new Contract(DeclineSelectionPage.PAGE_TYPE, DeclineSelectionPage.PAGE_SCHEMA),
                        "selectionChain", new Contract(DeclineSelectionPage.CHAIN_TYPE, DeclineSelectionPage.CHAIN_SCHEMA));
                case "decline-selection-final" -> Map.of(
                        "selectedEntities", new Contract(DeclineSelectionPublisher.SELECTED_TYPE, DeclineSelectionPublisher.SELECTED_SCHEMA),
                        "selectionEvidence", new Contract(DeclineSelectionPublisher.EVIDENCE_TYPE, DeclineSelectionPublisher.EVIDENCE_SCHEMA));
                default -> Map.of();
            };
        } else if (FrozenDimensionChange.REF_V2.equals(step.executor())) {
            expected = switch (invocation.contractName()) {
                case "selected-scope" -> Map.of(CampaignSelectedScope.OUTPUT,
                        new Contract(CampaignSelectedScope.TYPE, CampaignSelectedScope.SCHEMA));
                case "dimension-change-page" -> Map.of("dimensionPage",
                        new Contract(DimensionChangePublisher.PAGE_TYPE, DimensionChangePublisher.PAGE_SCHEMA));
                case "dimension-change-final" -> Map.of("dimensionChanges",
                        new Contract(DimensionChangePublisher.TYPE, DimensionChangePublisher.SCHEMA));
                default -> Map.of();
            };
        } else return false;
        if (expected.isEmpty() || !expected.keySet().equals(invocation.outputs().keySet())) return false;
        for (var output : invocation.outputs().entrySet()) {
            Contract type = expected.get(output.getKey());
            if (!type.type().equals(output.getValue().type())
                    || !type.schema().equals(output.getValue().schemaVersion())) return false;
        }
        return invocation.outputs().containsValue(new OutputBinding(artifact.ref().artifactId(), artifact.ref().type(),
                artifact.ref().schemaVersion(), artifact.ref().scopeRef(), artifact.ref().periodsRef()));
    }

    private static boolean sameRun(RunDefinition run, ArtifactMetadata artifact) {
        return artifact != null && artifact.ref() != null && run.caller().equals(artifact.owner())
                && run.runId().equals(artifact.runId()) && run.planId().equals(artifact.planId())
                && run.revision() == artifact.revision();
    }

    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DEPENDENCY_ARTIFACT_INVALID", invalid); }
    }

    private static Map<?, ?> object(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DEPENDENCY_ARTIFACT_INVALID", invalid); }
    }

    private record Contract(String type, String schema) { }
    private record FrozenStep(PlanSpec.Step step, String encoded) { }
    private record ParsedDefinition(RunDefinition definition, FrozenCampaignRun frozen,
            FrozenScopeCollection.Bound scope, FrozenDeclineSelection.Template selection,
            FrozenDimensionChange.Template dimension, Map<String, FrozenStep> steps) { }
}
