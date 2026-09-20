package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionChangeCall;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDimensionChange;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Native registration for a non-exploring Skill; receipts retain its actual durable invocation. */
public final class DimensionChangeExplorationSkill {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final ToolDefinition DEFINITION = ToolDefinition.builder().name(FrozenDimensionChange.REF_V3.name())
            .description("Compare the frozen geographic and device dimensions for an authorized sealed decline selection. Use its visible selectedEntities and selectionEvidence artifacts; do not supply new members or periods.")
            .inputSchema("""
                    {"type":"object","additionalProperties":false,"required":["inputBindings","parameters"],
                     "properties":{"inputBindings":{"type":"object","additionalProperties":false,
                       "required":["periods","definition","selectedEntities","selectionEvidence"],"properties":{
                         "periods":{"$ref":"#/$defs/input"},"definition":{"$ref":"#/$defs/input"},
                         "selectedEntities":{"$ref":"#/$defs/artifact"},"selectionEvidence":{"$ref":"#/$defs/artifact"}}},
                       "parameters":{"type":"object","additionalProperties":false,"properties":{}}},
                     "$defs":{"input":{"type":"object","additionalProperties":false,"required":["source","input"],
                       "properties":{"source":{"const":"INPUT"},"input":{"type":"string","minLength":1},
                         "stepId":{"type":"null"},"output":{"type":"null"},"artifactId":{"type":"null"}}},
                       "artifact":{"type":"object","additionalProperties":false,"required":["source","artifactId"],
                         "properties":{"source":{"const":"ARTIFACT"},"artifactId":{"type":"string","minLength":1},
                           "stepId":{"type":"null"},"output":{"type":"null"},"input":{"type":"null"}}}}}
                    """).build();
    private final DimensionChangeCall delegate;

    public DimensionChangeExplorationSkill(DimensionChangeCall delegate) { this.delegate = Objects.requireNonNull(delegate); }
    public static ToolDefinition definition() { return DEFINITION; }

    public NativeExplorationAdapter.RegisteredTool registration() {
        return new NativeExplorationAdapter.RegisteredTool(new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
            @Override public String call(String input) { throw new IllegalStateException("DIMENSION_NATIVE_CALL_CONTEXT_REQUIRED"); }
            @Override public String call(String input, ToolContext context) {
                Object value = context == null ? null : context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
                if (!(value instanceof NativeExplorationAdapter.DispatchScope scope)) throw new IllegalStateException("DIMENSION_NATIVE_CALL_CONTEXT_REQUIRED");
                var permit = scope.callPermit();
                try {
                    var result = delegate.execute(permit, input, () -> {
                        try { scope.dispatch(() -> null); }
                        catch (RuntimeException denied) { throw denied; }
                        catch (Exception denied) { throw new IllegalStateException("DIMENSION_NATIVE_DISPATCH_FENCED"); }
                    });
                    if (result.state() == CampaignSkillInvocationStore.State.WAITING
                            || result.state() == CampaignSkillInvocationStore.State.DEFERRED)
                        return NativeExplorationAdapter.Observation.skillPending(permit.callId()).json();
                    if (result.state() == CampaignSkillInvocationStore.State.COMPLETED)
                        return NativeExplorationAdapter.Observation.skillReady(permit.callId()).json();
                    throw new IllegalStateException("DIMENSION_NATIVE_RECEIPT_UNAVAILABLE");
                } catch (RuntimeException failed) { throw failed; }
                catch (Exception failed) { throw new IllegalStateException("DIMENSION_NATIVE_EXECUTION_FAILED"); }
            }
        }, DimensionChangeExplorationSkill::observation);
    }

    private static NativeExplorationAdapter.Observation observation(String encoded) {
        try {
            var value = JSON.readTree(encoded);
            if (value == null || !value.isObject() || value.size() != 2 || !value.path("skillCallId").isTextual()
                    || !value.path("status").isTextual()) throw new IllegalArgumentException("DIMENSION_NATIVE_OBSERVATION_INVALID");
            value.fieldNames().forEachRemaining(name -> {
                if (!Set.of("status", "skillCallId").contains(name)) throw new IllegalArgumentException("DIMENSION_NATIVE_OBSERVATION_INVALID");
            });
            return switch (value.get("status").textValue()) {
                case "PENDING" -> NativeExplorationAdapter.Observation.skillPending(value.get("skillCallId").textValue());
                case "READY" -> NativeExplorationAdapter.Observation.skillReady(value.get("skillCallId").textValue());
                default -> throw new IllegalArgumentException("DIMENSION_NATIVE_OBSERVATION_INVALID");
            };
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DIMENSION_NATIVE_OBSERVATION_INVALID"); }
    }
}
