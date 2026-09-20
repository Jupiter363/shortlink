package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.*;

/** Versioned server code evaluates frozen conditions; no expressions or checkers come from model text. */
public final class CompletionCriterionRegistry {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public enum State { MET, NOT_MET, UNKNOWN }
    public record Finding(State state, List<String> evidenceArtifactIds, List<String> reasonCodes) {
        public Finding {
            Objects.requireNonNull(state);
            evidenceArtifactIds = List.copyOf(evidenceArtifactIds); reasonCodes = List.copyOf(reasonCodes);
            require(evidenceArtifactIds.size() <= 64 && new HashSet<>(evidenceArtifactIds).size() == evidenceArtifactIds.size(),
                    "COMPLETION_CHECK_EVIDENCE_INVALID");
            require(reasonCodes.size() <= 16 && new HashSet<>(reasonCodes).size() == reasonCodes.size()
                    && reasonCodes.stream().allMatch(code -> code.matches("[A-Z][A-Z0-9_]{0,127}")), "COMPLETION_CHECK_REASON_INVALID");
            require(state == State.MET || !reasonCodes.isEmpty(), "COMPLETION_CHECK_REASON_REQUIRED");
        }
    }
    public record Result(String criterionRef, String checkerVersion, String implementationHash, State state,
                         List<String> evidenceArtifactIds, List<String> reasonCodes) {
        public Result { evidenceArtifactIds = List.copyOf(evidenceArtifactIds); reasonCodes = List.copyOf(reasonCodes); }
    }
    public record Context(RunToken token, PlanSpec.Step step, ExplorationCandidate candidate,
                          Map<String, BoundArtifact> outputs, Map<String, ArtifactMetadata> evidence) {
        public Context {
            Objects.requireNonNull(token); Objects.requireNonNull(step); Objects.requireNonNull(candidate);
            outputs = Map.copyOf(outputs); evidence = Map.copyOf(evidence);
        }
    }
    @FunctionalInterface public interface Checker {
        Finding check(Context context, Map<String, Object> parameters);
    }
    public record Registration(String policyRef, String policyVersion, String criterionRef,
                               String checkerVersion, String implementationHash,
                               CapabilityCatalog.Parameters parameters, Checker checker) {
        public Registration {
            for (String value : List.of(policyRef, policyVersion, criterionRef, checkerVersion))
                require(!value.isBlank() && value.length() <= 128, "COMPLETION_REGISTRATION_INVALID");
            require(implementationHash != null && implementationHash.matches("[a-f0-9]{64}"), "COMPLETION_IMPLEMENTATION_HASH_REQUIRED");
            Objects.requireNonNull(parameters); Objects.requireNonNull(checker);
        }
    }
    private record Key(String policyRef, String policyVersion, String criterionRef) {}
    private final Map<Key, Registration> registrations;

    public CompletionCriterionRegistry(List<Registration> registrations) {
        Map<Key, Registration> accepted = new HashMap<>();
        for (Registration registration : registrations) {
            var key = new Key(registration.policyRef(), registration.policyVersion(), registration.criterionRef());
            require(accepted.putIfAbsent(key, registration) == null, "COMPLETION_CHECKER_DUPLICATE");
        }
        this.registrations = Map.copyOf(accepted);
    }

    public String configurationId(CapabilityCatalog.Policy policy) {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (var use : uses(policy)) {
            Registration registration = registration(policy, use);
            checks.add(Map.of("criterionRef", use.criterionRef(), "parameters", use.parameters(),
                    "checkerVersion", registration.checkerVersion(), "implementationHash", registration.implementationHash(),
                    "requiredParameters", new TreeSet<>(registration.parameters().required()),
                    "parameterNames", new TreeSet<>(registration.parameters().properties().keySet())));
        }
        var executors = policy.allowedExecutors().stream().sorted(Comparator.comparing(ref ->
                ref.kind().name() + "/" + ref.name() + "/" + ref.version())).toList();
        Map<String, Object> definition = Map.of("schemaVersion", "completion-criteria/v1", "policyRef", policy.policyRef(),
                "policyVersion", policy.policyVersion(), "inputPorts", new TreeMap<>(policy.signature().inputs()),
                "outputPorts", new TreeMap<>(policy.signature().outputs()), "outputContractRef", policy.signature().outputContractRef(),
                "allowedExecutors", executors, "terminationPolicyRef", policy.terminationPolicyRef(), "checks", checks);
        try { return "completion-criteria/v1:" + CampaignRunStore.sha256(JSON.writeValueAsString(definition)); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("COMPLETION_CONFIGURATION_INVALID", invalid); }
    }

    public List<Result> evaluate(CapabilityCatalog.Policy policy, Context context) {
        Objects.requireNonNull(context);
        var exploration = context.step().explorationPolicy();
        require(context.candidate().kind() == ExplorationCandidate.Kind.COMPLETE
                && context.step().executionMode() == PlanSpec.ExecutionMode.REACT && exploration != null
                && policy.policyRef().equals(exploration.policyRef()) && policy.policyVersion().equals(exploration.policyVersion())
                && policy.completionCriteria().equals(exploration.completionCriteria()), "COMPLETION_CONTEXT_INVALID");
        Set<String> allowedEvidence = new HashSet<>();
        context.evidence().values().forEach(metadata -> allowedEvidence.add(metadata.ref().artifactId()));
        context.outputs().values().forEach(artifact -> allowedEvidence.add(artifact.metadata().ref().artifactId()));
        List<Result> results = new ArrayList<>();
        for (var use : uses(policy)) {
            Registration registration = registration(policy, use);
            Finding finding = Objects.requireNonNull(registration.checker().check(context, use.parameters()));
            require(allowedEvidence.containsAll(finding.evidenceArtifactIds()), "COMPLETION_CHECK_EVIDENCE_NOT_VISIBLE");
            results.add(new Result(use.criterionRef(), registration.checkerVersion(), registration.implementationHash(),
                    finding.state(), finding.evidenceArtifactIds(), finding.reasonCodes()));
        }
        return List.copyOf(results);
    }

    private List<PlanSpec.CriterionUse> uses(CapabilityCatalog.Policy policy) {
        Objects.requireNonNull(policy); Objects.requireNonNull(policy.signature());
        List<PlanSpec.CriterionUse> uses = policy.completionCriteria();
        require(uses != null && !uses.isEmpty() && uses.size() <= 32
                && uses.stream().map(PlanSpec.CriterionUse::criterionRef).distinct().count() == uses.size(), "COMPLETION_CRITERIA_INVALID");
        return uses;
    }
    private Registration registration(CapabilityCatalog.Policy policy, PlanSpec.CriterionUse use) {
        Registration registration = registrations.get(new Key(policy.policyRef(), policy.policyVersion(), use.criterionRef()));
        require(registration != null, "COMPLETION_CHECKER_UNREGISTERED");
        var schema = registration.parameters();
        require(use.parameters().keySet().containsAll(schema.required()) && schema.properties().keySet().containsAll(use.parameters().keySet()),
                "COMPLETION_PARAMETERS_INVALID");
        use.parameters().forEach((name, value) -> require(value != null && schema.properties().get(name).test(value), "COMPLETION_PARAMETERS_INVALID"));
        return registration;
    }
    private static void require(boolean value, String code) { if (!value) throw new IllegalArgumentException(code); }
}
