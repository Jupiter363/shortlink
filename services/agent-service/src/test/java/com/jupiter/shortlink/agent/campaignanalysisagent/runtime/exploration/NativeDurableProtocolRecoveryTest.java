package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeDurableExplorationLedgerTest.Fixture;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeDurableExplorationLedgerTest.InspectingSaver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** SQL constraints fail actual publication transactions; fresh native state uses only durable facts. */
@Timeout(30)
class NativeDurableProtocolRecoveryTest {
    private static final String PROMPT = "Analyze the authorized evidence";

    @Test
    void committedModelResponseSurvivesAcceptanceTransactionFailureWithoutRepeatingModelOrTool() throws Exception {
        var f = new Fixture(false);
        var model = new ScriptedExplorationChatModel(
                prompt -> ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall("first-call", "function", "read_first", "{}")),
                prompt -> {
                    var paired = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                            .map(message -> (ToolResponseMessage) message).flatMap(message -> message.getResponses().stream())
                            .filter(response -> "first-call".equals(response.id()) && "read_first".equals(response.name())
                                    && response.responseData().contains("artifact-first")).toList();
                    assertEquals(1, paired.size());
                    return ScriptedExplorationChatModel.text("Candidate from the saved evidence.");
                });
        f.jdbc.execute("ALTER TABLE campaign_exploration_turn ADD CONSTRAINT reject_model_accept CHECK (response_hash IS NULL)");
        try {
            var original = f.ledger(f.token, f.step);
            var failed = f.adapter(original, model, new InspectingSaver(f, false)).invoke(PROMPT);
            assertEquals("BLOCKED", failed.get("status"));
            assertEquals(1, model.callCount()); assertEquals(1, f.responses()); assertEquals(1, f.modelChildren());
            assertEquals(0, f.firstCalls.get()); assertEquals(0, f.secondCalls.get()); assertEquals(0, f.callCount());
            assertEquals("READY", f.jdbc.queryForObject("SELECT child_state FROM campaign_child_ledger WHERE child_mode='MODEL'", String.class));
            assertEquals("MODEL", f.jdbc.queryForObject("SELECT decision FROM campaign_exploration_turn", String.class));
            assertNull(f.jdbc.queryForObject("SELECT response_hash FROM campaign_exploration_turn", String.class));
            assertNotEquals("FAILED", f.jdbc.queryForObject("SELECT session_status FROM campaign_exploration_session", String.class));
            assertEquals(0, f.activeChildren()); assertEquals(0, f.activeCalls());
            String modelChildId = f.jdbc.queryForObject("SELECT model_child_id FROM campaign_exploration_turn WHERE turn_index=1", String.class);
            ChildRecord saved = f.runs.child(f.token, modelChildId).orElseThrow();
            String response = f.jdbc.queryForObject("SELECT response_json FROM campaign_model_response WHERE child_id=?", String.class, modelChildId);

            f.jdbc.execute("ALTER TABLE campaign_exploration_turn DROP CONSTRAINT reject_model_accept");
            var emptySaver = new InspectingSaver(f, false);
            var restored = f.ledger(f.token, f.step);
            var adapter = f.adapter(restored, model, emptySaver);
            var result = adapter.invoke(PROMPT);
            assertEquals("CANDIDATE", result.get("status")); assertEquals(List.of("artifact-first"), result.get("artifactIds"));
            assertEquals(2, model.callCount()); model.assertExhausted();
            assertEquals(2, f.responses()); assertEquals(2, f.modelChildren()); assertEquals(1, f.callCount());
            assertEquals(1, f.firstCalls.get()); assertEquals(0, f.secondCalls.get());
            assertEquals(saved, f.runs.child(f.token, modelChildId).orElseThrow());
            assertEquals(response, f.jdbc.queryForObject("SELECT response_json FROM campaign_model_response WHERE child_id=?", String.class, modelChildId));
            assertEquals(1, f.runs.child(f.token, "child-first").orElseThrow().attemptVersion());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE child_mode='MODEL' AND attempt_version<>1", Integer.class));
            assertFalse(emptySaver.writes.isEmpty());
            assertEquals("CANDIDATE", adapter.invoke(PROMPT).get("status"));
            assertEquals(2, model.callCount()); assertEquals(1, f.firstCalls.get());
        } finally { f.steps.callbackExited(f.step); }
        f.exited();
    }

    @Test
    void failedPendingObservationRollsBackProjectionAndResumesKnownJobAsOneReadyToolPair() throws Exception {
        var f = new Fixture(true);
        var model = new ScriptedExplorationChatModel(
                prompt -> ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall("async-call", "function", "submit_async", "{}")),
                prompt -> {
                    var pair = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                            .map(message -> (ToolResponseMessage) message).flatMap(message -> message.getResponses().stream())
                            .filter(response -> "async-call".equals(response.id()) && "submit_async".equals(response.name())).toList();
                    assertEquals(1, pair.size());
                    try {
                        var value = new ObjectMapper().readTree(pair.get(0).responseData());
                        assertEquals("READY", value.path("status").asText()); assertEquals("artifact-async", value.path("artifactId").asText());
                        assertFalse(value.has("jobId"));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw new AssertionError(malformed); }
                    assertEquals(1, prompt.getInstructions().stream().filter(message -> message instanceof UserMessage).count(),
                            "A PENDING observation whose transaction rolled back cannot create an extra READY user message");
                    assertFalse(prompt.getInstructions().toString().contains("trusted_action_observation"));
                    assertFalse(prompt.getInstructions().toString().contains("job-stable"));
                    return ScriptedExplorationChatModel.text("Candidate from the original completed job.");
                });
        f.jdbc.execute("ALTER TABLE campaign_exploration_call ADD CONSTRAINT reject_returned CHECK (call_state<>'RETURNED')");
        StepPermit restoredStep = null;
        boolean originalExited = false;
        try {
            var original = f.ledger(f.token, f.step);
            var result = f.adapter(original, model, new InspectingSaver(f, false)).invoke(PROMPT);
            assertNotEquals("FAILED", result.get("status")); assertNotEquals("CANDIDATE", result.get("status"));
            assertEquals(1, model.callCount()); assertEquals(1, f.responses()); assertEquals(1, f.submits.get());
            ChildRecord waiting = f.runs.child(f.token, "child-async").orElseThrow();
            assertEquals(ChildState.WAITING, waiting.state()); assertEquals("job-stable", waiting.jobId()); assertFalse(waiting.callbackActive());
            assertFalse(f.jdbc.queryForObject("SELECT pending_projected FROM campaign_exploration_turn WHERE turn_index=1", Boolean.class),
                    "Failure of the final RETURNED write rolls back the earlier projection flag in the same transaction");
            assertEquals("UNRESOLVED", f.jdbc.queryForObject("SELECT call_state FROM campaign_exploration_call", String.class));
            assertNull(f.jdbc.queryForObject("SELECT returned_at FROM campaign_exploration_call", Long.class));
            assertEquals(0, f.activeCalls()); assertEquals(0, f.activeChildren());
            String originalModel = f.jdbc.queryForObject("SELECT model_child_id FROM campaign_exploration_turn WHERE turn_index=1", String.class);
            ChildRecord modelFact = f.runs.child(f.token, originalModel).orElseThrow();
            String originalCall = f.jdbc.queryForObject("SELECT call_id FROM campaign_exploration_call", String.class);
            var callFact = f.calls.call(f.token, originalCall).orElseThrow();
            f.jdbc.execute("ALTER TABLE campaign_exploration_call DROP CONSTRAINT reject_returned");
            f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-original-job", f.authorizer);
            f.steps.callbackExited(f.step); originalExited = true;

            RunToken writer = f.steps.acquireRun(f.token);
            DispatchPermit reconciliation = f.runs.beginReconciliation(writer, "child-async");
            try {
                f.runs.publishReady(reconciliation, new ArtifactDraft("artifact-async", "Evidence", "evidence/v1", "scope-frozen", "periods-frozen",
                        "{\"collectionQuality\":\"UNKNOWN\"}", "{}", Instant.parse("2026-09-20T01:00:00Z"), "{\"pv\":13}"));
            } finally { f.runs.callbackExited(reconciliation); }
            assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, f.planStep.stepId()).status());
            restoredStep = f.steps.beginStep(writer, f.planStep.stepId());
            ChildRecord ready = f.runs.child(writer, "child-async").orElseThrow();
            var emptySaver = new InspectingSaver(f, false);
            var restored = f.ledger(writer, restoredStep);
            var adapter = f.adapter(restored, model, emptySaver);
            var completed = adapter.invoke(PROMPT);
            assertEquals("CANDIDATE", completed.get("status")); assertEquals(List.of("artifact-async"), completed.get("artifactIds"));
            assertEquals(2, model.callCount()); model.assertExhausted(); assertEquals(1, f.submits.get());
            assertEquals(2, f.modelChildren()); assertEquals(2, f.responses()); assertEquals(1, f.callCount());
            assertEquals(modelFact, f.runs.child(writer, originalModel).orElseThrow());
            assertEquals(callFact, f.calls.call(writer, originalCall).orElseThrow());
            assertEquals(waiting.spec(), ready.spec()); assertEquals(waiting.jobId(), ready.jobId());
            assertEquals(ready, f.runs.child(writer, "child-async").orElseThrow());
            assertFalse(f.jdbc.queryForObject("SELECT pending_projected FROM campaign_exploration_turn WHERE turn_index=1", Boolean.class));
            assertFalse(emptySaver.writes.isEmpty());
            assertEquals("CANDIDATE", adapter.invoke(PROMPT).get("status"));
            assertEquals(2, model.callCount()); assertEquals(1, f.submits.get());
        } finally {
            if (restoredStep != null) f.steps.callbackExited(restoredStep);
            if (!originalExited) f.steps.callbackExited(f.step);
        }
        f.exited();
    }
}
