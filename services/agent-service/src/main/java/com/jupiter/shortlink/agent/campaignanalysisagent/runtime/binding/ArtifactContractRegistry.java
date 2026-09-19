package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Artifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException.Code.*;

/**
 * Immutable server-owned contracts, not a schema interpreter or model-configurable registry.
 * ONE is a non-null non-array JSON value; MANY is a top-level array of individually valid values.
 * An envelope containing rows is an explicitly registered ONE contract.
 */
public final class ArtifactContractRegistry {
    public record Contract(TypeRef type, String artifactType, String schemaVersion,
                           Predicate<JsonNode> elementValidator,
                           BiPredicate<ArtifactMetadata, JsonNode> metadataAndQualityValidator) {
        public Contract {
            Objects.requireNonNull(type);
            if (type.name() == null || type.name().isBlank() || type.schemaMajorVersion() < 1
                    || type.cardinality() == null || artifactType == null || artifactType.isBlank()
                    || schemaVersion == null || schemaVersion.isBlank()) {
                throw new IllegalArgumentException("Invalid trusted artifact contract");
            }
            Objects.requireNonNull(elementValidator);
            Objects.requireNonNull(metadataAndQualityValidator);
        }
    }

    /** Transient invocation value, never suitable for graph state/checkpoints or durable ledgers. */
    public record BoundArtifact(ArtifactMetadata metadata, JsonNode payload) { }

    private record Key(String type, String schemaVersion) { }

    private final Map<Key, Contract> byMetadata;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public ArtifactContractRegistry(List<Contract> contracts) {
        Map<Key, Contract> registered = new HashMap<>();
        for (Contract contract : List.copyOf(contracts)) {
            Key key = new Key(contract.artifactType(), contract.schemaVersion());
            if (registered.putIfAbsent(key, contract) != null) {
                throw new IllegalArgumentException("Duplicate artifact type/schema registration");
            }
        }
        byMetadata = Map.copyOf(registered);
    }

    /** Metadata typing only. Callers must obtain metadata through current authorized inspection. */
    public TypeRef typeOf(ArtifactMetadata metadata) {
        return contract(metadata).type();
    }

    /**
     * Every call reads through the store's owner, current authorization, expiry and integrity gate.
     * Metadata alone never certifies the actual payload or its quality.
     */
    public BoundArtifact validateArtifact(TypeRef expectedType, String artifactId, CampaignRunStore store,
                                          Caller caller, ArtifactAuthorizer authorizer) {
        Objects.requireNonNull(expectedType);
        if (artifactId == null || artifactId.isBlank()) throw new BindingException(INVALID_BINDING);
        Artifact artifact = Objects.requireNonNull(store).readArtifact(caller, artifactId, authorizer);
        if (artifact == null || artifact.metadata() == null || artifact.metadata().ref() == null
                || !artifactId.equals(artifact.metadata().ref().artifactId())) {
            throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
        }
        Contract contract = contract(artifact.metadata());
        if (!expectedType.equals(contract.type())) throw new BindingException(TYPE_MISMATCH);
        JsonNode payload = parse(artifact.payloadJson());
        JsonNode quality = parse(artifact.metadata().qualityJson());
        if (!quality.isObject() || !validPayload(contract, payload)
                || !contract.metadataAndQualityValidator().test(artifact.metadata(), quality)) {
            throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
        }
        return new BoundArtifact(artifact.metadata(), payload);
    }

    private Contract contract(ArtifactMetadata metadata) {
        if (metadata == null || metadata.ref() == null) throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
        Contract contract = byMetadata.get(new Key(metadata.ref().type(), metadata.ref().schemaVersion()));
        if (contract == null) throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
        return contract;
    }

    private boolean validPayload(Contract contract, JsonNode payload) {
        if (contract.type().cardinality() == Cardinality.ONE) return validElement(contract, payload);
        if (!payload.isArray()) return false;
        for (JsonNode element : payload) if (!validElement(contract, element)) return false;
        return true;
    }

    private boolean validElement(Contract contract, JsonNode value) {
        return value != null && !value.isNull() && !value.isMissingNode() && !value.isArray()
                && contract.elementValidator().test(value);
    }

    private JsonNode parse(String text) {
        if (text == null) throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
        try {
            JsonNode value = json.readTree(text);
            if (value == null) throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
            return value;
        } catch (JsonProcessingException invalid) {
            throw new BindingException(ARTIFACT_CONTRACT_MISMATCH);
        }
    }
}
