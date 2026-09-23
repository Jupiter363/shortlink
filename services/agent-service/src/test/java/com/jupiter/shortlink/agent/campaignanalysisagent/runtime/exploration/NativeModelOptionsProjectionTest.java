package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static org.junit.jupiter.api.Assertions.*;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class NativeModelOptionsProjectionTest {
    @Test void serverGenerationSnapshotIsHashedAndRuntimeChangesRemainForbidden() {
        var defaults = ToolCallingChatOptions.builder().model("configured-model").maxTokens(2000).temperature(0.3).build();
        var approved = NativeExplorationAdapter.generationOptions(defaults);
        var actual = (DefaultToolCallingChatOptions) NativeExplorationAdapter.chatOptions(approved);
        var projected = NativeExplorationAdapter.projectRequest(request(actual), List.of(), approved);
        assertEquals(approved, projected.generationOptions());
        String frozen = ModelInvocationRegistry.encodeRequest(projected);
        assertEquals(projected, ModelInvocationRegistry.decodeRequest(frozen));
        assertTrue(frozen.contains("\"model\":\"configured-model\""));
        assertTrue(frozen.contains("\"maxTokens\":2000"));
        assertTrue(frozen.contains("\"temperature\":0.3"));
        actual.setMaxTokens(4000);
        assertThrows(IllegalArgumentException.class, () -> NativeExplorationAdapter.projectRequest(request(actual), List.of(), approved));
        var changed = NativeExplorationAdapter.generationOptions(actual);
        var changedRequest = NativeExplorationAdapter.projectRequest(request(actual), List.of(), changed);
        assertNotEquals(CampaignRunStore.sha256(frozen), CampaignRunStore.sha256(ModelInvocationRegistry.encodeRequest(changedRequest)));
        actual.setMaxTokens(2000); actual.setModel("changed-model");
        assertThrows(IllegalArgumentException.class, () -> NativeExplorationAdapter.projectRequest(request(actual), List.of(), approved));
        actual.setModel("configured-model"); actual.setTemperature(0.8);
        assertThrows(IllegalArgumentException.class, () -> NativeExplorationAdapter.projectRequest(request(actual), List.of(), approved));
        actual.setTemperature(0.3); actual.setToolContext(Map.of("unapproved", "value"));
        assertThrows(IllegalArgumentException.class, () -> NativeExplorationAdapter.projectRequest(request(actual), List.of(), approved));
        assertThrows(IllegalArgumentException.class, () -> NativeExplorationAdapter.generationOptions(new DefaultToolCallingChatOptions() {}));
        assertThrows(IllegalArgumentException.class, () -> NativeExplorationAdapter.projectRequest(request(NativeExplorationAdapter.chatOptions(approved)), List.of()));
    }

    @Test void legacyV1BytesRemainStableAndGenerationExtensionIsClosed() {
        String legacy = "{\"messages\":[{\"role\":\"user\",\"text\":\"analyse\",\"toolCallId\":null,\"toolCalls\":null,\"toolName\":null}],"
                + "\"schemaVersion\":\"campaign-model-request/v1\",\"tools\":[]}";
        var request = ModelInvocationRegistry.decodeRequest(legacy);
        assertNull(request.generationOptions());
        assertEquals(legacy, ModelInvocationRegistry.encodeRequest(request));
        for (String invalid : List.of("null", "{}", "{\"apiKey\":\"secret\"}", "{\"maxTokens\":\"2000\"}",
                "{\"maxTokens\":1.5}", "{\"temperature\":3}", "{\"model\":null}")) {
            String wire = legacy.substring(0, legacy.length() - 1) + ",\"generationOptions\":" + invalid + "}";
            assertThrows(IllegalArgumentException.class, () -> ModelInvocationRegistry.decodeRequest(wire));
        }
    }

    private static ModelRequest request(ToolCallingChatOptions options) {
        return ModelRequest.builder().messages(List.of(new UserMessage("analyse"))).options(options).build();
    }
}
