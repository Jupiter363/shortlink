package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec.ExecutorRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import java.util.List;
import java.util.Optional;

/** Durable ownership of a capability callback. RETURNED is not business success or an Artifact. */
public interface CampaignExplorationCallStore {
    enum CallState { PREPARED, RUNNING, RETURNED, UNRESOLVED }

    record Identity(String callId, String actionId) {}

    record CallSpec(String callId, String actionId, String stepId, String modelChildId, String responseHash,
                    String toolCallId, ExecutorRef executor, String arguments) {
        public CallSpec {
            for (String value : List.of(callId, actionId, stepId, modelChildId))
                if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"))
                    throw new IllegalArgumentException("EXPLORATION_CALL_ID_INVALID");
            if (responseHash == null || !responseHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("EXPLORATION_RESPONSE_HASH_INVALID");
            if (executor == null || executor.kind() == null || executor.name() == null || executor.version() == null
                    || executor.version().isBlank() || executor.version().length() > 128
                    || executor.version().chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("EXPLORATION_EXECUTOR_INVALID");
            arguments = new ModelInvocationRegistry.ToolCall(toolCallId, executor.name(), arguments).arguments();
        }
        public String hash() { return CampaignRunStore.sha256(encode(this)); }
    }

    record CallPermit(StepPermit step, String callId, String actionId, String attemptId, long attemptVersion) {}

    record CallRecord(CallSpec spec, CallState state, long rowVersion, String attemptId, long attemptVersion,
                      boolean callbackActive, boolean revoked, String reason, Long returnedAt) {}

    /** The slot excludes arguments, executor versions, response contents and retry attempts. */
    static Identity identity(RunDefinition definition, String stepId, String modelChildId, String toolCallId) {
        if (definition == null || definition.caller() == null || stepId == null || modelChildId == null || toolCallId == null)
            throw new IllegalArgumentException("EXPLORATION_CALL_IDENTITY_REQUIRED");
        var owner = definition.caller();
        String hash = CampaignRunStore.sha256(Codec.write(List.of(owner.tenantId(), owner.subject(), owner.authVersion(),
                definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), stepId,
                modelChildId, toolCallId)));
        return new Identity("explore-call-" + hash, "explore-action-" + hash);
    }

    CallRecord prepare(StepPermit step, CallSpec spec, ModelInvocationRegistry.Approval approval, ArtifactAuthorizer authorizer);

    /** A fresh PREPARED callback only; no implicit retry of returned or unknown callbacks. */
    CallPermit beginCall(StepPermit step, String callId, ModelInvocationRegistry.Approval approval, ArtifactAuthorizer authorizer);

    Optional<CallRecord> call(RunToken token, String callId);

    boolean mayExecute(CallPermit permit);

    /** Revocation closes admission but retains callbackActive until the actual delegate exits. */
    void revoke(CallPermit permit, String reason);

    /** Only records that the callback returned; output and completion checks belong to other stores. */
    void recordReturned(CallPermit permit);

    /** Actual finally signal for the exact original attempt, including after cancellation/fencing. */
    void callbackExited(CallPermit permit);

    static String encode(CallSpec spec) {
        if (spec == null) throw new IllegalArgumentException("EXPLORATION_CALL_REQUIRED");
        return Codec.write(spec);
    }

    static CallSpec decode(String encoded) {
        try { return Codec.JSON.readValue(encoded, CallSpec.class); }
        catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("EXPLORATION_CALL_DEFINITION_INVALID");
        }
    }

    final class Codec {
        private Codec() {}
        private static final JsonMapper JSON = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        private static String write(Object value) {
            try { return JSON.writeValueAsString(value); }
            catch (JsonProcessingException invalid) { throw new IllegalArgumentException("EXPLORATION_CALL_DEFINITION_INVALID"); }
        }
    }
}
