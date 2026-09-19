package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Explicit, server-composed contracts for pure deterministic local calculations. This registry
 * does not execute calculations or establish authority over artifacts. The run store must verify
 * the actual frozen inputs, current authorization, expiry and execution fences before reuse or
 * publication. No model-provided flag or executor name alone can create an Approval.
 */
public final class LocalCalculationRegistry {
    private static final JsonMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public record OutputBinding(String artifactId, String type, String schemaVersion,
                                String scopeRef, String periodsRef) {
        public OutputBinding {
            text(artifactId, "LOCAL_ARTIFACT_ID_REQUIRED");
            text(type, "LOCAL_ARTIFACT_TYPE_REQUIRED");
            text(schemaVersion, "LOCAL_ARTIFACT_SCHEMA_REQUIRED");
            text(scopeRef, "LOCAL_SCOPE_REFERENCE_REQUIRED");
            text(periodsRef, "LOCAL_PERIODS_REFERENCE_REQUIRED");
        }
    }

    public record TypeContract(String type, String schemaVersion) {
        public TypeContract {
            text(type, "LOCAL_ARTIFACT_TYPE_REQUIRED");
            text(schemaVersion, "LOCAL_ARTIFACT_SCHEMA_REQUIRED");
        }
    }

    public record InvocationSpec(String contractName, String contractVersion, String implementationHash,
                                 String parametersJson, Map<String, ArtifactMetadata> inputs,
                                 Map<String, OutputBinding> outputs, Instant expiresAt) {
        public InvocationSpec {
            text(contractName, "LOCAL_CONTRACT_NAME_REQUIRED");
            text(contractVersion, "LOCAL_CONTRACT_VERSION_REQUIRED");
            LocalCalculationRegistry.hash(implementationHash, "LOCAL_IMPLEMENTATION_HASH_INVALID");
            parametersJson = write(canonical(object(parametersJson, "LOCAL_PARAMETERS_INVALID")));
            inputs = ordered(inputs, "LOCAL_INPUTS_REQUIRED");
            outputs = ordered(outputs, "LOCAL_OUTPUTS_REQUIRED");
            for (ArtifactMetadata input : inputs.values()) {
                require(input.ref() != null, "LOCAL_INPUT_REFERENCE_REQUIRED");
                LocalCalculationRegistry.hash(input.ref().payloadHash(), "LOCAL_INPUT_HASH_INVALID");
            }
            require(new HashSet<>(outputs.values().stream().map(OutputBinding::artifactId).toList()).size()
                    == outputs.size(), "LOCAL_OUTPUT_IDS_NOT_DISTINCT");
            require(expiresAt != null, "LOCAL_EXPIRY_REQUIRED");
            try { expiresAt = Instant.ofEpochMilli(expiresAt.toEpochMilli()); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException("LOCAL_EXPIRY_INVALID", invalid); }
        }

        public String hash() { return CampaignRunStore.sha256(encode(this)); }
    }

    /** Predicates are trusted service code, not serialized instructions or a general schema DSL. */
    public record Contract(String name, String version, String implementationHash,
                           Map<String, TypeContract> inputs, Map<String, TypeContract> outputs,
                           Predicate<JsonNode> parametersValidator,
                           Predicate<Map<String, JsonNode>> outputsValidator) {
        public Contract {
            text(name, "LOCAL_CONTRACT_NAME_REQUIRED");
            text(version, "LOCAL_CONTRACT_VERSION_REQUIRED");
            hash(implementationHash, "LOCAL_IMPLEMENTATION_HASH_INVALID");
            inputs = ordered(inputs, "LOCAL_INPUTS_REQUIRED");
            outputs = ordered(outputs, "LOCAL_OUTPUTS_REQUIRED");
            require(parametersValidator != null && outputsValidator != null, "LOCAL_VALIDATORS_REQUIRED");
        }
    }

    private final Map<Key, Contract> contracts;

    public LocalCalculationRegistry(List<Contract> contracts) {
        require(contracts != null, "LOCAL_CONTRACTS_REQUIRED");
        Map<Key, Contract> registered = new java.util.HashMap<>();
        for (Contract contract : contracts) {
            require(contract != null, "LOCAL_CONTRACT_REQUIRED");
            require(registered.putIfAbsent(new Key(contract.name(), contract.version()), contract) == null,
                    "LOCAL_CONTRACT_DUPLICATE");
        }
        this.contracts = Map.copyOf(registered);
    }

    public Approval approve(InvocationSpec invocation) {
        require(invocation != null, "LOCAL_INVOCATION_REQUIRED");
        Contract contract = contracts.get(new Key(invocation.contractName(), invocation.contractVersion()));
        require(contract != null, "LOCAL_CONTRACT_NOT_REGISTERED");
        require(contract.implementationHash().equals(invocation.implementationHash()), "LOCAL_IMPLEMENTATION_CHANGED");
        require(contract.inputs().keySet().equals(invocation.inputs().keySet()), "LOCAL_INPUT_PORTS_MISMATCH");
        require(contract.outputs().keySet().equals(invocation.outputs().keySet()), "LOCAL_OUTPUT_PORTS_MISMATCH");
        invocation.inputs().forEach((name, metadata) -> {
            TypeContract expected = contract.inputs().get(name);
            require(expected.type().equals(metadata.ref().type())
                    && expected.schemaVersion().equals(metadata.ref().schemaVersion()), "LOCAL_INPUT_TYPE_MISMATCH");
        });
        invocation.outputs().forEach((name, binding) -> {
            TypeContract expected = contract.outputs().get(name);
            require(expected.type().equals(binding.type())
                    && expected.schemaVersion().equals(binding.schemaVersion()), "LOCAL_OUTPUT_TYPE_MISMATCH");
        });
        boolean valid;
        try { valid = contract.parametersValidator().test(object(invocation.parametersJson(), "LOCAL_PARAMETERS_INVALID")); }
        catch (RuntimeException rejected) { throw new IllegalArgumentException("LOCAL_PARAMETERS_REJECTED", rejected); }
        require(valid, "LOCAL_PARAMETERS_REJECTED");
        return new Approval(invocation, contract);
    }

    public static final class Approval {
        private final InvocationSpec invocation;
        private final Contract contract;

        private Approval(InvocationSpec invocation, Contract contract) {
            this.invocation = invocation;
            this.contract = contract;
        }

        public InvocationSpec invocation() { return invocation; }

        public void validateOutputs(Map<String, ArtifactDraft> drafts) {
            Map<String, ArtifactDraft> frozenDrafts = ordered(drafts, "LOCAL_OUTPUTS_REQUIRED");
            require(frozenDrafts.keySet().equals(invocation.outputs().keySet()), "LOCAL_OUTPUT_PORTS_MISMATCH");
            Map<String, JsonNode> payloads = new TreeMap<>();
            var artifactIds = new HashSet<String>();
            frozenDrafts.forEach((name, draft) -> {
                OutputBinding expected = invocation.outputs().get(name);
                require(expected.artifactId().equals(draft.artifactId())
                        && expected.type().equals(draft.type())
                        && expected.schemaVersion().equals(draft.schemaVersion())
                        && expected.scopeRef().equals(draft.scopeRef())
                        && expected.periodsRef().equals(draft.periodsRef())
                        && invocation.expiresAt().equals(draft.expiresAt()), "LOCAL_OUTPUT_BINDING_MISMATCH");
                require(artifactIds.add(draft.artifactId()), "LOCAL_OUTPUT_IDS_NOT_DISTINCT");
                payloads.put(name, object(draft.payloadJson(), "LOCAL_OUTPUT_PAYLOAD_INVALID"));
            });
            boolean valid;
            try { valid = contract.outputsValidator().test(Collections.unmodifiableMap(payloads)); }
            catch (RuntimeException rejected) { throw new IllegalArgumentException("LOCAL_OUTPUTS_REJECTED", rejected); }
            require(valid, "LOCAL_OUTPUTS_REJECTED");
        }
    }

    public static String encode(InvocationSpec invocation) {
        require(invocation != null, "LOCAL_INVOCATION_REQUIRED");
        return write(invocation);
    }

    public static InvocationSpec decode(String encoded) {
        JsonNode tree = object(encoded, "LOCAL_INVOCATION_INVALID");
        // JavaTimeModule also accepts numeric timestamps; this persisted protocol accepts ISO text only.
        require(tree.path("expiresAt").isTextual(), "LOCAL_EXPIRY_INVALID");
        require(tree.path("inputs").isObject(), "LOCAL_INPUTS_REQUIRED");
        tree.path("inputs").elements().forEachRemaining(input ->
                require(input.path("ref").path("expiresAt").isTextual(), "LOCAL_INPUT_EXPIRY_INVALID"));
        try { return JSON.treeToValue(tree, InvocationSpec.class); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("LOCAL_INVOCATION_INVALID", invalid); }
    }

    private static JsonNode object(String encoded, String code) {
        require(encoded != null, code);
        try {
            JsonNode value = JSON.readTree(encoded);
            require(value != null && value.isObject(), code);
            return value;
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException(code, invalid); }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(field -> fields.put(field.getKey(), field.getValue()));
            fields.forEach((key, item) -> result.set(key, canonical(item)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("LOCAL_INVOCATION_INVALID", invalid); }
    }

    private static <T> Map<String, T> ordered(Map<String, T> values, String code) {
        require(values != null && !values.isEmpty(), code);
        Map<String, T> result = new TreeMap<>();
        values.forEach((name, value) -> {
            text(name, code);
            require(value != null, code);
            result.put(name, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void hash(String value, String code) {
        require(value != null && value.matches("[a-f0-9]{64}"), code);
    }

    private static void text(String value, String code) { require(value != null && !value.isBlank(), code); }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
    private record Key(String name, String version) {}
}
