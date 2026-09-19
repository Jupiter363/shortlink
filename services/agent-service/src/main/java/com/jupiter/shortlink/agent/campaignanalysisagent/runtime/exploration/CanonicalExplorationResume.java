package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** P0 canonical reconstruction of one unfinished action; no arbitrary Message enters the ledger API. */
final class CanonicalExplorationResume {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OBSERVATION_METADATA = "trustedReadyObservationId";

    private CanonicalExplorationResume() {}

    static Prepared prepare(NativeExplorationAdapter.ExecutionKey identity, ExplorationLedger.ResumeFacts facts,
            Set<String> tools, NativeExplorationAdapter.Limits limits) {
        try {
            require(identity.equals(facts.identity()));
            require(facts.originalInput() != null && facts.originalInput().length() <= limits.inputCharacters());
            require(facts.pendingCalls().size() == 1 && facts.readyReceipts().size() == 1);
            var call = facts.pendingCalls().get(0);
            var ready = facts.readyReceipts().get(0);
            for (String value : List.of(call.actionId(), call.assistantMessageId(), call.jobId(),
                    ready.observationId(), ready.actionId(), ready.jobId(), ready.artifactId()))
                require(value.matches("[A-Za-z0-9_-]{1,128}"));
            require(call.toolCallId() != null && !call.toolCallId().isBlank() && call.toolCallId().length() <= 128);
            require(tools.contains(call.toolName()));
            require(ready.actionId().equals(call.actionId()) && ready.jobId().equals(call.jobId()));
            require(call.arguments() != null && call.arguments().length() <= limits.modelResponseCharacters());
            require(JSON.readTree(call.arguments()).isObject());
            require(call.assistantText() != null && call.assistantText().length() <= limits.modelResponseCharacters());
            String observation = JSON.writeValueAsString(Map.of("type", "trusted_action_observation", "status", "READY",
                    "observationId", ready.observationId(), "actionId", ready.actionId(), "jobId", ready.jobId(),
                    "artifactId", ready.artifactId()));
            require(observation.length() <= limits.observationCharacters());
            var assistant = AssistantMessage.builder().content(call.assistantText())
                    .properties(Map.of("canonicalAssistantMessageId", call.assistantMessageId()))
                    .toolCalls(List.of(new AssistantMessage.ToolCall(call.toolCallId(), "function", call.toolName(), call.arguments())))
                    .build();
            var pending = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                    call.toolCallId(), call.toolName(), NativeExplorationAdapter.Observation.pending(call.jobId()).json()))).build();
            var observed = UserMessage.builder().text(observation)
                    .metadata(Map.of(OBSERVATION_METADATA, ready.observationId(), "source", "trusted_ledger")).build();
            return new Prepared(ready.observationId(), call, ready,
                    List.of(new UserMessage(facts.originalInput()), assistant, pending, observed));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid canonical resume facts");
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid canonical resume facts");
    }

    record Prepared(String observationId, ExplorationLedger.PendingCall call, ExplorationLedger.ReadyReceipt ready,
                    List<Message> messages) {
        Prepared { messages = List.copyOf(messages); }

        boolean repeatsOriginalCall(AssistantMessage.ToolCall requested) {
            if (call.toolCallId().equals(requested.id())) return true;
            if (!call.toolName().equals(requested.name())) return false;
            try { return JSON.readTree(call.arguments()).equals(JSON.readTree(requested.arguments())); }
            catch (Exception malformed) { return true; }
        }

        /** Reject missing, duplicated, orphaned or changed pairings; never dispatch to repair them. */
        boolean matchesPendingPair(Object nativeMessages) {
            if (!(nativeMessages instanceof List<?> history)) return false;
            var unmatched = new LinkedHashMap<String, AssistantMessage.ToolCall>();
            String assistantText = null;
            int matchingResponses = 0;
            try {
                for (Object value : history) {
                    if (value instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                        if (!unmatched.isEmpty()) return false;
                        assistantText = assistant.getText();
                        for (var candidate : assistant.getToolCalls()) {
                            if (candidate.id() == null || candidate.id().isBlank()
                                    || unmatched.putIfAbsent(candidate.id(), candidate) != null) return false;
                        }
                    } else if (value instanceof ToolResponseMessage response) {
                        if (response.getResponses().isEmpty()) return false;
                        for (var candidate : response.getResponses()) {
                            var requested = unmatched.remove(candidate.id());
                            if (requested == null || !Objects.equals(requested.name(), candidate.name())) return false;
                            // toolCallId is only unique inside its assistant group. Identify the
                            // unfinished action by its frozen job, not an earlier group's same ID.
                            com.fasterxml.jackson.databind.JsonNode data;
                            try { data = JSON.readTree(candidate.responseData()); }
                            catch (Exception historicalTextResponse) { continue; }
                            if (data == null || !"PENDING".equals(data.path("status").asText())
                                    || !call.jobId().equals(data.path("jobId").asText())) continue;
                            matchingResponses++;
                            if (!call.toolCallId().equals(candidate.id()) || !call.toolName().equals(requested.name())
                                    || !call.arguments().equals(requested.arguments())
                                    || !call.assistantText().equals(assistantText)) return false;
                        }
                    } else if (!(value instanceof Message) || !unmatched.isEmpty()) return false;
                }
                return unmatched.isEmpty() && matchingResponses == 1;
            } catch (Exception invalid) {
                return false;
            }
        }

        boolean matchesAppliedObservation(Object nativeMessages) {
            if (!(nativeMessages instanceof List<?> history)) return false;
            int count = 0;
            try {
                for (Object value : history) {
                    if (value instanceof UserMessage user && observationId.equals(user.getMetadata().get(OBSERVATION_METADATA))) {
                        count++;
                        var data = JSON.readTree(user.getText());
                        if (!"READY".equals(data.path("status").asText())
                                || !ready.actionId().equals(data.path("actionId").asText())
                                || !ready.jobId().equals(data.path("jobId").asText())
                                || !ready.artifactId().equals(data.path("artifactId").asText())) return false;
                    }
                }
                return count == 1;
            } catch (Exception invalid) {
                return false;
            }
        }
    }
}
