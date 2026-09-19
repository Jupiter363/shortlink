package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery.Bound;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Opt-in fixed adapter. Submission records a job identity; only durable reception supplies pages. */
public final class StatisticsJobFixedExecutor {
    public static final PlanSpec.ExecutorRef REF =
            new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics_query_job", "1");
    public static final TypeRef OUTPUT_TYPE = new TypeRef(CampaignStatisticsResultStore.ARTIFACT_TYPE, 1, Cardinality.ONE);
    public static final String OUTPUT_NAME = "pages";
    private static final Set<String> JOB_STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @FunctionalInterface
    public interface QueryAuthorizer {
        /** Verify the actual immutable request against both trusted refs using current rights. */
        boolean mayUse(AgentPrincipal current, String scopeRef, String periodsRef, Map<String, Object> frozenRequest);
    }

    private final RunDefinition definition;
    private final AgentPrincipal current;
    private final ShortLinkBusinessGateway gateway;
    private final QueryAuthorizer authorizer;
    private final Map<String, Bound> bindings;
    private final Map<String, StatisticsJobResultReceiver.Target> targets;

    public StatisticsJobFixedExecutor(RunDefinition definition, AgentPrincipal current,
            ShortLinkBusinessGateway gateway, QueryAuthorizer authorizer) {
        this.definition = Objects.requireNonNull(definition);
        var owner = definition.caller();
        if (current == null || current.system() || owner == null
                || !Objects.equals(owner.tenantId(), current.tenantId())
                || !Objects.equals(owner.subject(), current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("STATISTICS_PRINCIPAL_MISMATCH");
        this.current = current;
        this.gateway = Objects.requireNonNull(gateway);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.bindings = FrozenStatisticsJobQuery.resolve(definition, REF);
        Map<String, StatisticsJobResultReceiver.Target> resolved = new LinkedHashMap<>();
        for (Bound bound : bindings.values())
            if (resolved.putIfAbsent(bound.child().childId(), bound.target()) != null)
                throw new IllegalArgumentException("STATISTICS_CHILD_ID_CONFLICT");
        this.targets = Map.copyOf(resolved);
    }

    public PersistentPlanDriver.FixedExecutor registration() {
        return new PersistentPlanDriver.FixedExecutor(REF, new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                validateBoundInputs(bound(step), inputs);
            }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                Bound bound = bound(step);
                validateBoundInputs(bound, inputs);
                validateOutput(bound, outputs);
            }
        }, this::execute);
    }

    public Map<String, StatisticsJobResultReceiver.Target> resultTargets() { return targets; }

    /** The runtime/coordinator must invoke this current gate before every submission or result read. */
    public boolean authorized() {
        for (Bound bound : bindings.values()) if (!authorized(bound)) return false;
        return true;
    }

    public static Capability capability() {
        return new Capability(REF, new Signature(FrozenStatisticsJobQuery.INPUTS,
                CampaignStatisticsResultStore.SCHEMA_VERSION,
                Map.of(OUTPUT_NAME, new Port(OUTPUT_TYPE, true)), Parameters.none()), false);
    }

    public static ArtifactContractRegistry.Contract artifactContract() {
        return new ArtifactContractRegistry.Contract(OUTPUT_TYPE, CampaignStatisticsResultStore.ARTIFACT_TYPE,
                CampaignStatisticsResultStore.SCHEMA_VERSION,
                StatisticsJobFixedExecutor::validManifest, StatisticsJobFixedExecutor::validMetadata);
    }

    private PersistentPlanDriver.Result execute(CampaignStepExecution context) throws Exception {
        Bound bound = bound(context.step());
        validateBoundInputs(bound, context.inputs());
        ChildRecord child;
        try {
            child = context.child(bound.child(), boundary -> {
                requireAuthorized(bound);
                boundary.beforeIo();
                ToolResult response;
                try {
                    response = gateway.submitStatisticsJob(new ToolContext(definition.sessionId(), current.username(),
                            bound.request(), current), bound.request());
                } catch (RuntimeException uncertain) {
                    throw new SubmissionUnresolved();
                }
                if (response == null || !response.success() || !(response.data() instanceof Map<?, ?> data)
                        || !(data.get("jobId") instanceof String jobId) || !jobReference(jobId)
                        || !(data.get("state") instanceof String state) || !JOB_STATES.contains(state))
                    throw new SubmissionUnresolved();
                // Even a SUCCEEDED acknowledgement is only a job identity, never local evidence.
                // The child boundary preserves this identity if authority was revoked during I/O.
                return CampaignStepExecution.ChildResult.waiting(jobId);
            });
        } catch (SubmissionUnresolved unknown) {
            return PersistentPlanDriver.Result.blocked("STEP_RESULT_UNKNOWN");
        } catch (SecurityException denied) {
            return PersistentPlanDriver.Result.blocked("STATISTICS_QUERY_ACCESS_DENIED");
        }
        if (!authorized(bound)) return PersistentPlanDriver.Result.blocked("STATISTICS_QUERY_ACCESS_DENIED");
        return switch (child.state()) {
            case READY -> {
                if (!bound.target().artifactId().equals(child.artifactId())) outputMismatch();
                yield PersistentPlanDriver.Result.succeeded(Map.of(OUTPUT_NAME, child.artifactId()));
            }
            case WAITING -> {
                if (!jobReference(child.jobId())) throw new IllegalStateException("STATISTICS_JOB_ID_INVALID");
                yield PersistentPlanDriver.Result.waiting();
            }
            // The child ledger retains READ_RESULT_UNKNOWN / SUBMISSION_UNRESOLVED /
            // JOB_RESULT_UNKNOWN. This step reason lets the existing recovery driver refresh it.
            case PREPARED, DISPATCHING, UNRESOLVED -> PersistentPlanDriver.Result.blocked("STEP_RESULT_UNKNOWN");
        };
    }

    private Bound bound(PlanSpec.Step step) {
        Bound bound = step == null ? null : bindings.get(step.stepId());
        if (bound == null || !bound.step().equals(step)) throw new BindingException(BindingException.Code.INVALID_BINDING);
        return bound;
    }

    private void validateBoundInputs(Bound bound, BoundInputs inputs) {
        Map<String, Object> expected = Map.of("scope", bound.scopeRef(), "periods", bound.periodsRef(), "query", bound.descriptor());
        if (!expected.equals(inputs.values()) || !inputs.artifacts().isEmpty())
            throw new BindingException(BindingException.Code.INVALID_BINDING);
        requireAuthorized(bound);
    }

    private boolean authorized(Bound bound) {
        try { return authorizer.mayUse(current, bound.scopeRef(), bound.periodsRef(), bound.request()); }
        catch (SecurityException denied) { return false; }
    }

    private void requireAuthorized(Bound bound) {
        if (!authorized(bound)) throw new SecurityException("STATISTICS_QUERY_ACCESS_DENIED");
    }

    private void validateOutput(Bound bound, Map<String, BoundArtifact> outputs) {
        if (!Set.of(OUTPUT_NAME).equals(outputs.keySet()) || outputs.get(OUTPUT_NAME) == null) outputMismatch();
        BoundArtifact artifact = outputs.get(OUTPUT_NAME);
        ArtifactMetadata metadata = artifact.metadata();
        JsonNode manifest = artifact.payload();
        if (metadata == null || metadata.ref() == null || !validManifest(manifest)) outputMismatch();
        var ref = metadata.ref();
        if (!bound.target().artifactId().equals(ref.artifactId())
                || !CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(ref.type())
                || !CampaignStatisticsResultStore.SCHEMA_VERSION.equals(ref.schemaVersion())
                || !bound.scopeRef().equals(ref.scopeRef()) || !bound.periodsRef().equals(ref.periodsRef())
                || !definition.caller().equals(metadata.owner()) || !definition.runId().equals(metadata.runId())
                || !definition.planId().equals(metadata.planId()) || definition.revision() != metadata.revision()
                || !bound.child().childId().equals(metadata.childId()) || !bound.child().actionId().equals(metadata.actionId())
                || !REF.version().equals(metadata.executorVersion())
                || !ref.artifactId().equals(manifest.path("artifactId").asText())) outputMismatch();
        JsonNode provenance = object(metadata.provenanceJson());
        JsonNode quality = object(metadata.qualityJson());
        if (!validMetadata(metadata, quality)
                || !bound.child().wire().hash().equals(provenance.path("requestHash").asText())
                || !manifest.path("jobId").asText().equals(provenance.path("jobId").asText())
                || !manifest.path("meta").equals(quality)
                || !Objects.equals(bound.request().get("queryKind"), quality.path("queryKind").asText())
                || !Objects.equals(bound.request().get("gid"), quality.path("gid").asText())) outputMismatch();
    }

    private static boolean validManifest(JsonNode manifest) {
        if (manifest == null || !manifest.isObject() || !text(manifest.path("artifactId"))
                || !jobReference(manifest.path("jobId").asText(null))
                || !nonnegative(manifest.path("pageCount")) || !manifest.path("pageCount").canConvertToInt()
                || !nonnegative(manifest.path("receivedPageCount")) || !manifest.path("receivedPageCount").canConvertToInt()
                || !nonnegative(manifest.path("totalRows")) || !hash(manifest.path("chainHash"))
                || !manifest.path("resultComplete").isBoolean() || !manifest.path("resultComplete").booleanValue()
                || !manifest.path("meta").isObject() || !manifest.path("metrics").isObject()) return false;
        long rows = manifest.path("totalRows").longValue();
        int pages = manifest.path("pageCount").intValue();
        return rows / 500 + (rows % 500 == 0 ? 0 : 1) == pages
                && manifest.path("receivedPageCount").intValue() == Math.max(1, pages)
                && nonnegative(manifest.path("meta").path("totalRows"))
                && manifest.path("meta").path("totalRows").longValue() == rows
                && manifest.path("jobId").equals(manifest.path("meta").path("snapshotId"));
    }

    private static boolean validMetadata(ArtifactMetadata metadata, JsonNode quality) {
        if (metadata == null || metadata.ref() == null || metadata.ref().expiresAt() == null
                || quality == null || !quality.isObject()) return false;
        JsonNode provenance;
        try { provenance = object(metadata.provenanceJson()); }
        catch (BindingException invalid) { return false; }
        return "CURRENT_QUERY".equals(provenance.path("scopeMode").asText())
                && hash(provenance.path("requestHash")) && jobReference(provenance.path("jobId").asText(null))
                && provenance.path("jobId").equals(quality.path("snapshotId"))
                && nonnegative(quality.path("snapshotExpiresAt"))
                && quality.path("snapshotExpiresAt").longValue() == metadata.ref().expiresAt().toEpochMilli();
    }

    private static JsonNode object(String json) {
        try {
            JsonNode value = json == null ? null : JSON.readTree(json);
            if (value != null && value.isObject()) return value;
        } catch (JsonProcessingException invalid) { /* Expose only a closed contract error. */ }
        throw new BindingException(BindingException.Code.OUTPUT_CONTRACT_MISMATCH);
    }

    private static boolean text(JsonNode value) { return value.isTextual() && !value.textValue().isBlank(); }
    private static boolean hash(JsonNode value) { return value.isTextual() && value.textValue().matches("[a-f0-9]{64}"); }
    private static boolean nonnegative(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }
    private static boolean jobReference(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }
    private static void outputMismatch() { throw new BindingException(BindingException.Code.OUTPUT_CONTRACT_MISMATCH); }

    private static final class SubmissionUnresolved extends IllegalStateException {
        SubmissionUnresolved() { super("SUBMISSION_UNRESOLVED"); }
    }
}
