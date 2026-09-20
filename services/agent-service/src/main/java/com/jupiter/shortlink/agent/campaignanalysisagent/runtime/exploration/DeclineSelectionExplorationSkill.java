package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionCall;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenDeclineSelection;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Thin native registration for the fixed Skill implementation; observations reference its actual invocation. */
public final class DeclineSelectionExplorationSkill {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final ToolDefinition DEFINITION = ToolDefinition.builder().name(FrozenDeclineSelection.REF.name())
            .description("Compare both frozen periods for all authorized scope members and publish selected declining links with complete observed evidence.")
            .inputSchema("""
                    {"type":"object","additionalProperties":false,"required":["inputBindings","parameters"],
                     "properties":{"inputBindings":{"type":"object","additionalProperties":false,
                       "required":["scope","scopeArtifact","periods","definition"],"properties":{
                         "scope":{"$ref":"#/$defs/input"},"scopeArtifact":{"$ref":"#/$defs/artifact"},
                         "periods":{"$ref":"#/$defs/input"},"definition":{"$ref":"#/$defs/input"}}},
                       "parameters":{"type":"object","additionalProperties":false,"required":["metric"],
                         "properties":{"metric":{"type":"string","enum":["PV","UV","UIP"]}}}},
                     "$defs":{"input":{"type":"object","additionalProperties":false,"required":["source","input"],
                       "properties":{"source":{"const":"INPUT"},"input":{"type":"string","minLength":1},
                         "stepId":{"type":"null"},"output":{"type":"null"},"artifactId":{"type":"null"}}},
                       "artifact":{"type":"object","additionalProperties":false,"required":["source","artifactId"],
                         "properties":{"source":{"const":"ARTIFACT"},"artifactId":{"type":"string","minLength":1},
                           "stepId":{"type":"null"},"output":{"type":"null"},"input":{"type":"null"}}}}}
                    """).build();
    private final DeclineSelectionCall delegate;

    public DeclineSelectionExplorationSkill(DeclineSelectionCall delegate) { this.delegate = Objects.requireNonNull(delegate); }
    public static ToolDefinition definition() { return DEFINITION; }

    public NativeExplorationAdapter.RegisteredTool registration() {
        return new NativeExplorationAdapter.RegisteredTool(new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return DEFINITION; }
            @Override public String call(String input) { throw new IllegalStateException("DECLINE_NATIVE_CALL_CONTEXT_REQUIRED"); }
            @Override public String call(String input, ToolContext context) {
                Object value = context == null ? null : context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
                if (!(value instanceof NativeExplorationAdapter.DispatchScope scope)) throw new IllegalStateException("DECLINE_NATIVE_CALL_CONTEXT_REQUIRED");
                var permit = scope.callPermit();
                try {
                    var result = delegate.execute(permit, input, () -> {
                        try { scope.dispatch(() -> null); }
                        catch (RuntimeException denied) { throw denied; }
                        catch (Exception denied) { throw new IllegalStateException("DECLINE_NATIVE_DISPATCH_FENCED"); }
                    });
                    if (result.state() == CampaignSkillInvocationStore.State.WAITING
                            || result.state() == CampaignSkillInvocationStore.State.DEFERRED)
                        return NativeExplorationAdapter.Observation.skillPending(permit.callId()).json();
                    if (result.state() == CampaignSkillInvocationStore.State.COMPLETED)
                        return NativeExplorationAdapter.Observation.skillReady(permit.callId()).json();
                    throw new IllegalStateException("DECLINE_NATIVE_RECEIPT_UNAVAILABLE");
                } catch (RuntimeException failed) { throw failed; }
                catch (Exception failed) { throw new IllegalStateException("DECLINE_NATIVE_EXECUTION_FAILED"); }
            }
        }, DeclineSelectionExplorationSkill::observation);
    }

    private static NativeExplorationAdapter.Observation observation(String encoded) {
        try {
            var value = JSON.readTree(encoded);
            if (value == null || !value.isObject() || value.size() != 2 || !value.path("skillCallId").isTextual()
                    || !value.path("status").isTextual()) throw new IllegalArgumentException("DECLINE_NATIVE_OBSERVATION_INVALID");
            value.fieldNames().forEachRemaining(name -> {
                if (!Set.of("status", "skillCallId").contains(name)) throw new IllegalArgumentException("DECLINE_NATIVE_OBSERVATION_INVALID");
            });
            return switch (value.get("status").textValue()) {
                case "PENDING" -> NativeExplorationAdapter.Observation.skillPending(value.get("skillCallId").textValue());
                case "READY" -> NativeExplorationAdapter.Observation.skillReady(value.get("skillCallId").textValue());
                default -> throw new IllegalArgumentException("DECLINE_NATIVE_OBSERVATION_INVALID");
            };
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DECLINE_NATIVE_OBSERVATION_INVALID"); }
    }
}
