package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore.Collection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenScopeCollection.Bound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignScopeCollector;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Opt-in NativePlanGraph adapter. Each pass uses the existing durable authority collector. */
public final class ScopeCollectionFixedExecutor {
    public static final PlanSpec.ExecutorRef REF = FrozenScopeCollection.REF;
    public static final String OUTPUT_NAME = "scopeArtifact";
    public static final TypeRef OUTPUT_TYPE = new TypeRef("ScopeArtifact", 1, Cardinality.ONE);
    private static final String PERIODS = "scope-enumeration";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MANIFEST_FIELDS = Set.of("schemaVersion", "collectionId", "gid", "scopeRef",
            "memberHash", "enumerationVersion", "memberCount", "pageCount", "shardCount");

    @FunctionalInterface
    public interface ScopeAuthorizer { boolean mayUse(AgentPrincipal current, String gid); }

    private final RunToken token;
    private final AgentPrincipal current;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignScopeStore scopes;
    private final CampaignScopeCollector collector;
    private final ScopeAuthorizer authorizer;
    private final ArtifactAuthorizer artifactAuthorizer;
    private final Map<String, Bound> bindings;

    public ScopeCollectionFixedExecutor(RunToken token, AgentPrincipal current, CampaignRunStore runs,
            CampaignStepStore steps, CampaignScopeStore scopes, CampaignScopeCollector collector,
            ScopeAuthorizer authorizer, ArtifactAuthorizer artifactAuthorizer) {
        this.token = Objects.requireNonNull(token);
        var owner = token.definition().caller();
        if (current == null || current.system() || !owner.tenantId().equals(current.tenantId())
                || !owner.subject().equals(current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("SCOPE_PRINCIPAL_MISMATCH");
        this.current = current; this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps);
        this.scopes = Objects.requireNonNull(scopes); this.collector = Objects.requireNonNull(collector);
        this.authorizer = Objects.requireNonNull(authorizer); this.artifactAuthorizer = Objects.requireNonNull(artifactAuthorizer);
        this.bindings = FrozenScopeCollection.resolve(token.definition());
    }

    public static Capability capability() {
        return new Capability(REF, new Signature(FrozenScopeCollection.INPUTS, FrozenScopeCollection.OUTPUT_CONTRACT,
                Map.of(OUTPUT_NAME, new Port(OUTPUT_TYPE, true)), Parameters.none()), false);
    }

    public static ArtifactContractRegistry.Contract artifactContract() {
        return new ArtifactContractRegistry.Contract(OUTPUT_TYPE, "ScopeArtifact", FrozenScopeCollection.OUTPUT_CONTRACT,
                ScopeCollectionFixedExecutor::validManifest, ScopeCollectionFixedExecutor::validMetadata);
    }

    public PersistentPlanDriver.FixedExecutor registration() {
        return new PersistentPlanDriver.FixedExecutor(REF, new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { validateBound(bound(step), inputs); }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                Bound bound = bound(step); validateBound(bound, inputs);
                if (!outputs.keySet().equals(Set.of(OUTPUT_NAME)) || outputs.get(OUTPUT_NAME) == null) mismatch();
                Artifact actual = published(bound);
                BoundArtifact output = outputs.get(OUTPUT_NAME);
                if (!actual.metadata().equals(output.metadata()) || !object(actual.payloadJson()).equals(output.payload())) mismatch();
            }
        }, this::execute);
    }

    public boolean authorized() {
        try {
            requireCurrent();
            for (Bound bound : bindings.values()) if (!authorizer.mayUse(current, bound.definition().gid())) return false;
            return true;
        } catch (SecurityException denied) { return false; }
    }

    /** Only a normal bounded collection pass is rearmed here; an unknown first read is not retried. */
    public void prepareRecovery() {
        requireCurrent();
        for (Bound bound : bindings.values()) {
            var step = steps.step(token, bound.step().stepId());
            if (step.isEmpty() || step.get().status() != CampaignStepStore.StepStatus.BLOCKED
                    || !"SCOPE_COLLECTION_PROGRESS".equals(step.get().reason())) continue;
            requireAuthorized(bound);
            steps.refreshScopeCollection(token, bound.step().stepId(), bound.definition(), artifactAuthorizer);
        }
    }

    private PersistentPlanDriver.Result execute(CampaignStepExecution context) {
        Bound bound = bound(context.step());
        validateBound(bound, context.inputs());
        context.requireCurrent();
        Collection existing = scopes.prepare(token, bound.definition());
        if (existing.pageCount() > 0) scopes.verifyContinuation(token, bound.definition(), artifactAuthorizer);
        var result = collector.collect(token, bound.definition(), current, () -> {
            context.requireCurrent();
            requireCurrent();
            return authorizer.mayUse(current, bound.definition().gid());
        });
        context.requireCurrent();
        requireAuthorized(bound);
        return switch (result.outcome()) {
            case PROGRESS -> PersistentPlanDriver.Result.blocked("SCOPE_COLLECTION_PROGRESS");
            case READY -> PersistentPlanDriver.Result.succeeded(Map.of(OUTPUT_NAME, published(bound).metadata().ref().artifactId()));
            case BLOCKED -> PersistentPlanDriver.Result.blocked(result.code());
            case STOPPED -> throw new SecurityException("SCOPE_AUTHORITY_REVOKED");
        };
    }

    private Artifact published(Bound bound) {
        requireAuthorized(bound);
        Collection collection = scopes.load(token, bound.definition().collectionId());
        if (!bound.definition().equals(collection.definition()) || collection.state() != CampaignScopeStore.State.PUBLISHED
                || collection.artifactId() == null || collection.nextCursor() != null) mismatch();
        var summary = scopes.inspectPublished(token.definition().caller(), collection.artifactId(), artifactAuthorizer);
        Artifact actual = runs.readArtifact(token.definition().caller(), collection.artifactId(), artifactAuthorizer);
        ArtifactMetadata metadata = actual.metadata();
        JsonNode manifest = object(actual.payloadJson());
        if (!validManifest(manifest) || !validMetadata(metadata, object(metadata.qualityJson()))) mismatch();
        var ref = metadata.ref();
        if (!current.equals(summary.owner()) || !bound.definition().gid().equals(summary.gid())
                || !collection.enumerationVersion().equals(summary.enumerationVersion())
                || collection.memberCount() != summary.memberCount() || collection.pageCount() != summary.pageCount()
                || !collection.artifactId().equals(ref.artifactId()) || !"ScopeArtifact".equals(ref.type())
                || !FrozenScopeCollection.OUTPUT_CONTRACT.equals(ref.schemaVersion())
                || !summary.scopeRef().equals(ref.scopeRef()) || !PERIODS.equals(ref.periodsRef())
                || !bound.definition().expiresAt().equals(ref.expiresAt())
                || !token.definition().caller().equals(metadata.owner()) || !token.definition().runId().equals(metadata.runId())
                || !token.definition().planId().equals(metadata.planId()) || token.definition().revision() != metadata.revision()
                || !bound.definition().action().actionId().equals(metadata.actionId()) || !REF.version().equals(metadata.executorVersion())) mismatch();
        Map<String, Object> expected = Map.of("schemaVersion", FrozenScopeCollection.OUTPUT_CONTRACT,
                "collectionId", bound.definition().collectionId(), "gid", summary.gid(), "scopeRef", summary.scopeRef(),
                "memberHash", summary.memberHash(), "enumerationVersion", summary.enumerationVersion(),
                "memberCount", summary.memberCount(), "pageCount", summary.pageCount(), "shardCount", summary.shardCount());
        if (!object(FrozenCampaignRun.encode(expected)).equals(manifest)) mismatch();
        JsonNode provenance = object(metadata.provenanceJson());
        if (!bound.definition().collectionId().equals(provenance.path("collectionId").asText())
                || !summary.enumerationVersion().equals(provenance.path("enumerationVersion").asText())) mismatch();
        ChildRecord child = runs.child(token, metadata.childId()).orElseThrow();
        if (child.state() != ChildState.READY || child.spec().mode() != ChildMode.SYNC
                || !collection.artifactId().equals(child.artifactId())
                || !bound.definition().action().actionId().equals(child.spec().actionId())) mismatch();
        requireAuthorized(bound);
        return actual;
    }

    private Bound bound(PlanSpec.Step step) {
        Bound bound = step == null ? null : bindings.get(step.stepId());
        if (bound == null || !bound.step().equals(step)) throw new BindingException(BindingException.Code.INVALID_BINDING);
        return bound;
    }

    private void validateBound(Bound bound, BoundInputs inputs) {
        if (!Map.of("definition", bound.descriptor()).equals(inputs.values()) || !inputs.artifacts().isEmpty())
            throw new BindingException(BindingException.Code.INVALID_BINDING);
        requireAuthorized(bound);
    }

    private void requireAuthorized(Bound bound) {
        requireCurrent();
        if (!authorizer.mayUse(current, bound.definition().gid())) throw new SecurityException("SCOPE_AUTHORITY_REVOKED");
    }

    private void requireCurrent() {
        if (runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(run -> run.status() == RunStatus.ACTIVE && token.equals(run.token())).isEmpty())
            throw new SecurityException("SCOPE_RUN_FENCED");
    }

    private static boolean validManifest(JsonNode value) {
        if (value == null || !value.isObject()) return false;
        Set<String> fields = new HashSet<>(); value.fieldNames().forEachRemaining(fields::add);
        if (!MANIFEST_FIELDS.equals(fields) || !FrozenScopeCollection.OUTPUT_CONTRACT.equals(value.path("schemaVersion").asText())
                || !text(value.path("collectionId")) || !value.path("gid").isTextual()
                || !value.path("gid").asText().matches("[A-Za-z0-9_-]{1,64}")
                || !text(value.path("scopeRef")) || !hash(value.path("memberHash")) || !hash(value.path("enumerationVersion"))
                || !nonnegative(value.path("memberCount")) || !nonnegative(value.path("pageCount"))
                || !value.path("pageCount").canConvertToInt() || !nonnegative(value.path("shardCount"))
                || !value.path("shardCount").canConvertToInt()) return false;
        long count = value.path("memberCount").longValue();
        long shards = count / 500 + (count % 500 == 0 ? 0 : 1);
        return value.path("shardCount").longValue() == shards && value.path("pageCount").longValue() == Math.max(1, shards);
    }

    private static boolean validMetadata(ArtifactMetadata metadata, JsonNode quality) {
        if (metadata == null || metadata.ref() == null || metadata.ref().expiresAt() == null || quality == null || !quality.isObject()
                || !quality.path("resultComplete").isBoolean() || !quality.path("resultComplete").booleanValue()
                || !"COMPLETE".equals(quality.path("membership").asText()) || !quality.path("historicalSnapshot").isBoolean()
                || quality.path("historicalSnapshot").booleanValue()) return false;
        try {
            JsonNode provenance = object(metadata.provenanceJson());
            return "CURRENT_AUTHORITY".equals(provenance.path("source").asText())
                    && text(provenance.path("collectionId")) && hash(provenance.path("enumerationVersion"))
                    && provenance.path("historicalSnapshot").isBoolean() && !provenance.path("historicalSnapshot").booleanValue();
        } catch (BindingException invalid) { return false; }
    }

    private static JsonNode object(String value) {
        if (value == null) throw new BindingException(BindingException.Code.OUTPUT_CONTRACT_MISMATCH);
        try { JsonNode node = JSON.readTree(value); if (node != null && node.isObject()) return node; }
        catch (JsonProcessingException invalid) { /* Closed output contract error. */ }
        throw new BindingException(BindingException.Code.OUTPUT_CONTRACT_MISMATCH);
    }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.textValue().isBlank(); }
    private static boolean hash(JsonNode value) { return value.isTextual() && value.textValue().matches("[0-9a-f]{64}"); }
    private static boolean nonnegative(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }
    private static void mismatch() { throw new BindingException(BindingException.Code.OUTPUT_CONTRACT_MISMATCH); }
}
