package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDeclineSelection.Bound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDeclineSelection.BoundQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned fixed Skill inside the existing native Plan runner. Scope enumeration is a prerequisite. */
public final class DeclineSelectionSkill {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeRef SELECTED = new TypeRef(DeclineSelectionPublisher.SELECTED_TYPE, 1, Cardinality.ONE);
    private static final TypeRef EVIDENCE = new TypeRef(DeclineSelectionPublisher.EVIDENCE_TYPE, 1, Cardinality.ONE);
    private static final TypeRef SCOPE = new TypeRef("ScopeArtifact", 1, Cardinality.ONE);
    private final RunToken token;
    private final AgentPrincipal current;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignScopeStore scopes;
    private final CampaignDeclineSelectionStore selections;
    private final ShortLinkBusinessGateway gateway;
    private final StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer;
    private final ArtifactAuthorizer artifactAuthorizer;
    private final DeclineSelectionPublisher publisher;
    private final Map<String, Bound> bindings;
    private final RunPinnedSkills methods;

    public DeclineSelectionSkill(RunToken token, AgentPrincipal current, Path approvedSkillsRoot,
            CampaignRunStore runs, CampaignStepStore steps, CampaignScopeStore scopes,
            CampaignStatisticsResultStore results, CampaignDeclineSelectionStore selections,
            ShortLinkBusinessGateway gateway, StatisticsJobFixedExecutor.QueryAuthorizer queryAuthorizer,
            ArtifactAuthorizer artifactAuthorizer) {
        this.token = Objects.requireNonNull(token); this.current = Objects.requireNonNull(current);
        var owner = token.definition().caller();
        if (current.system() || !owner.tenantId().equals(current.tenantId()) || !owner.subject().equals(current.username())
                || owner.authVersion() != current.authVersion()) throw new SecurityException("DECLINE_PRINCIPAL_MISMATCH");
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.scopes = Objects.requireNonNull(scopes); this.selections = Objects.requireNonNull(selections);
        this.gateway = Objects.requireNonNull(gateway); this.queryAuthorizer = Objects.requireNonNull(queryAuthorizer);
        this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer);
        this.bindings = FrozenDeclineSelection.resolve(token.definition());
        if (bindings.isEmpty()) throw new IllegalArgumentException("DECLINE_SKILL_NOT_IN_PLAN");
        List<RunPinnedSkills.SkillPin> pins = bindings.values().stream().map(Bound::skillPin).distinct().toList();
        if (pins.size() != 1) throw new IllegalArgumentException("DECLINE_METHOD_PIN_CONFLICT");
        this.methods = new RunPinnedSkills(token.definition().runId(), approvedSkillsRoot, pins, Set.of(), List.of(),
                (runId, toolName) -> false); // Fixed execution uses business boundaries, never method-activated callbacks.
        this.publisher = new DeclineSelectionPublisher(runs,
                new CampaignObservedLinkComparison(new CampaignParentCoverage(runs, scopes, results)), selections, artifactAuthorizer);
        bindings.values().forEach(this::definition);
    }

    public static Capability capability() {
        return new Capability(FrozenDeclineSelection.REF, new Signature(FrozenDeclineSelection.INPUTS,
                FrozenDeclineSelection.OUTPUT_CONTRACT,
                Map.of("selectedEntities", new Port(SELECTED, true), "selectionEvidence", new Port(EVIDENCE, true)),
                new Parameters(Set.of("metric"), Map.of("metric", value -> value instanceof String metric
                        && Set.of("PV", "UV", "UIP").contains(metric)))), false);
    }

    public static List<ArtifactContractRegistry.Contract> artifactContracts() {
        return List.of(new ArtifactContractRegistry.Contract(SCOPE, "ScopeArtifact", "campaign-scope/v1",
                        value -> value.isObject() && "campaign-scope/v1".equals(value.path("schemaVersion").asText())
                                && value.path("scopeRef").isTextual() && value.path("memberCount").isIntegralNumber(),
                        (metadata, quality) -> quality.isObject()),
                contract(SELECTED, DeclineSelectionPublisher.SELECTED_SCHEMA),
                contract(EVIDENCE, DeclineSelectionPublisher.EVIDENCE_SCHEMA));
    }

    private static ArtifactContractRegistry.Contract contract(TypeRef type, String schema) {
        return new ArtifactContractRegistry.Contract(type, type.name(), schema,
                value -> value.isObject() && schema.equals(value.path("schemaVersion").asText())
                        && value.path("selectionComplete").isBoolean() && value.path("headPayloadHash").isTextual(),
                (metadata, quality) -> "OBSERVED_ONLY".equals(quality.path("interpretation").asText()));
    }

    public PersistentPlanDriver.FixedExecutor registration() {
        return new PersistentPlanDriver.FixedExecutor(FrozenDeclineSelection.REF, new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { validateBound(step, inputs); }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                validateBound(step, inputs);
                Receipt receipt = selections.loadReceipt(token, bound(step).collectionId(), artifactAuthorizer).orElseThrow();
                if (!receipt.sealed() || !outputs.keySet().equals(Set.of("selectedEntities", "selectionEvidence"))
                        || !receipt.selectedArtifactId().equals(outputs.get("selectedEntities").metadata().ref().artifactId())
                        || !receipt.evidenceArtifactId().equals(outputs.get("selectionEvidence").metadata().ref().artifactId()))
                    throw new IllegalArgumentException("DECLINE_OUTPUT_MISMATCH");
            }
        }, this::execute);
    }

    /** Registry approved local recovery only; no gateway calls and no unknown remote resubmission. */
    public void prepareRecovery() {
        requireCurrent();
        for (Bound bound : bindings.values()) {
            var step = steps.step(token, bound.step().stepId());
            if (step.isEmpty() || step.get().status() != CampaignStepStore.StepStatus.BLOCKED
                    || !"STEP_RESULT_UNKNOWN".equals(step.get().reason())) continue;
            var definition = definition(bound);
            Receipt receipt = selections.loadReceipt(token, bound.collectionId(), artifactAuthorizer).orElse(null);
            DeclineSelectionPublisher.Prepared prepared;
            if (receipt != null && receipt.committedPages() == Math.max(1, definition.shardCount())) {
                prepared = publisher.prepareFinal(token, bound.step().stepId(), receipt.chainArtifactId());
            } else {
                int shard = receipt == null ? 0 : receipt.committedPages();
                List<BoundQuery> queries = queries(bound, definition, shard);
                if (!allReady(queries)) continue;
                prepared = publisher.prepareShard(token, bound.step().stepId(), definition, bound.periods(),
                        (period, ignored) -> slot(queries.get(period)), shard, receipt == null ? null : receipt.chainArtifactId());
            }
            var child = runs.child(token, prepared.child().childId());
            if (child.isPresent() && child.get().reason() == UnresolvedReason.LOCAL_RESULT_UNKNOWN)
                steps.refreshLocalReplay(token, bound.step().stepId(), Map.of(prepared.child().childId(), prepared.approval()), artifactAuthorizer);
        }
    }

    /** Only the current shard's two targets are materialized, never every member/request in the plan. */
    public Map<String, StatisticsJobResultReceiver.Target> resultTargets() {
        requireCurrent();
        Map<String, StatisticsJobResultReceiver.Target> targets = new LinkedHashMap<>();
        for (Bound bound : bindings.values()) {
            var definition = definition(bound);
            Receipt receipt = selections.loadReceipt(token, bound.collectionId(), artifactAuthorizer).orElse(null);
            int next = receipt == null ? 0 : receipt.committedPages();
            if (next >= definition.shardCount()) continue;
            for (BoundQuery query : queries(bound, definition, next)) targets.put(query.child().childId(), query.target());
        }
        return Map.copyOf(targets);
    }

    public boolean authorized() {
        try { requireCurrent(); bindings.values().forEach(this::definition); return true; }
        catch (SecurityException | IllegalArgumentException | IllegalStateException unavailable) { return false; }
    }

    private PersistentPlanDriver.Result execute(CampaignStepExecution context) throws Exception {
        Bound bound = bound(context.step());
        var definition = definition(bound);
        Receipt receipt = selections.loadReceipt(token, bound.collectionId(), artifactAuthorizer).orElse(null);
        if (receipt != null && receipt.sealed()) return succeeded(receipt);
        int next = receipt == null ? 0 : receipt.committedPages();
        for (int shard = next; shard < Math.max(1, definition.shardCount()); shard++) {
            context.requireCurrent(); requireCurrent();
            List<BoundQuery> queries = queries(bound, definition, shard);
            boolean pending = false, waiting = false, capacity = false;
            for (BoundQuery query : queries) {
                ChildRecord child;
                try {
                    child = context.child(query.child(), boundary -> StatisticsJobFixedExecutor.submit(boundary,
                            token.definition(), current, gateway, query.request(), () -> authorized(bound, query)));
                } catch (StatisticsJobFixedExecutor.SubmissionUnresolved unknown) {
                    pending = true; continue;
                }
                if (child.state() == ChildState.READY) {
                    if (!query.target().artifactId().equals(child.artifactId())) throw new IllegalArgumentException("DECLINE_STATISTICS_BINDING_MISMATCH");
                } else {
                    pending = true;
                    waiting |= child.state() == ChildState.WAITING;
                    capacity |= child.state() == ChildState.PREPARED && child.reason() == UnresolvedReason.QUERY_CAPACITY_EXHAUSTED;
                }
            }
            if (pending) return waiting ? PersistentPlanDriver.Result.waiting()
                    : PersistentPlanDriver.Result.blocked(capacity ? "REMOTE_CAPACITY" : "STEP_RESULT_UNKNOWN");
            receipt = publisher.publishShard(context, token, definition, bound.periods(),
                    (period, ignored) -> slot(queries.get(period)), shard, receipt == null ? null : receipt.chainArtifactId());
        }
        return succeeded(publisher.finish(context, token, Objects.requireNonNull(receipt).chainArtifactId()));
    }

    private List<BoundQuery> queries(Bound bound, DeclineSelectionPage.Definition definition, int shardIndex) {
        if (definition.memberCount() == 0) return List.of();
        FrozenQueryScope shard = scopes.shard(token.definition().caller(), bound.scopeArtifactId(), shardIndex, artifactAuthorizer);
        return List.of(FrozenDeclineSelection.query(token.definition(), bound, shard, 0),
                FrozenDeclineSelection.query(token.definition(), bound, shard, 1));
    }

    private boolean allReady(List<BoundQuery> queries) {
        return queries.stream().allMatch(query -> runs.child(token, query.child().childId())
                .filter(child -> child.state() == ChildState.READY && query.target().artifactId().equals(child.artifactId())).isPresent());
    }

    private CampaignParentCoverage.Slot slot(BoundQuery query) {
        return new CampaignParentCoverage.Slot(token, query.child().childId(), query.target().artifactId());
    }

    private boolean authorized(Bound bound, BoundQuery query) {
        requireCurrent();
        definition(bound);
        return queryAuthorizer.mayUse(current, bound.scopeRef(), query.periodsRef(), query.request());
    }

    private DeclineSelectionPage.Definition definition(Bound bound) {
        Artifact scope = runs.readArtifact(token.definition().caller(), bound.scopeArtifactId(), artifactAuthorizer);
        JsonNode value;
        try { value = JSON.readTree(scope.payloadJson()); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("DECLINE_SCOPE_INVALID", invalid); }
        if (!"ScopeArtifact".equals(scope.metadata().ref().type()) || !"campaign-scope/v1".equals(scope.metadata().ref().schemaVersion())
                || !bound.scopeRef().equals(scope.metadata().ref().scopeRef()) || !bound.scopeRef().equals(value.path("scopeRef").asText())
                || !bound.gid().equals(value.path("gid").asText()) || !value.path("memberCount").isIntegralNumber()
                || !value.path("memberCount").canConvertToLong() || !value.path("shardCount").isIntegralNumber()
                || !value.path("shardCount").canConvertToInt()) throw new IllegalArgumentException("DECLINE_SCOPE_INVALID");
        return new DeclineSelectionPage.Definition(bound.collectionId(), bound.scopeArtifactId(), bound.scopeRef(),
                bound.periodsRef(), bound.metric(), value.path("shardCount").intValue(), value.path("memberCount").longValue());
    }

    private void validateBound(PlanSpec.Step step, BoundInputs inputs) {
        Bound bound = bound(step); requireCurrent();
        if (!bound.scopeRef().equals(inputs.value("scope")) || !bound.periodsRef().equals(inputs.value("periods"))
                || !bound.descriptor().equals(inputs.value("definition")) || inputs.artifact("scopeArtifact") == null
                || !bound.scopeArtifactId().equals(inputs.artifact("scopeArtifact").metadata().ref().artifactId()))
            throw new IllegalArgumentException("DECLINE_INPUT_MISMATCH");
        definition(bound);
    }

    private Bound bound(PlanSpec.Step step) {
        Bound bound = bindings.get(step.stepId());
        if (bound == null || !bound.step().equals(step)) throw new IllegalArgumentException("DECLINE_STEP_CHANGED");
        return bound;
    }

    private void requireCurrent() {
        methods.verifyPins();
        if (runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(run -> run.status() == RunStatus.ACTIVE && run.token().equals(token)).isEmpty())
            throw new SecurityException("DECLINE_RUN_FENCED");
    }

    private static PersistentPlanDriver.Result succeeded(Receipt receipt) {
        return PersistentPlanDriver.Result.succeeded(Map.of("selectedEntities", receipt.selectedArtifactId(),
                "selectionEvidence", receipt.evidenceArtifactId()));
    }
}
