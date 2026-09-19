package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeDurableExplorationLedgerTest.Fixture;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeDurableExplorationLedgerTest.InspectingSaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** Small native scenarios prove durable admission; no stress loop or artificial history expansion. */
@Timeout(30)
class NativeExplorationBudgetTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String PROMPT = "Analyze the authorized evidence";
    private static final String MODEL_LIMIT = "EXPLORATION_MODEL_TURN_BUDGET_EXHAUSTED";
    private static final String CONTEXT_LIMIT = "EXPLORATION_CONTEXT_BUDGET_EXHAUSTED";

    @Test
    void completedEvidenceAndModelBudgetSurviveFreshLedgerAndRevisionIncludingBackfillOfExistingFacts() throws Exception {
        var f = new Fixture(false);
        var policy = new ExplorationBudgetPolicy("campaign-explore", "1", 2, 8, 2, 65536);
        var model = new ScriptedExplorationChatModel(
                prompt -> ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall("first-call", "function", "read_first", "{}")),
                prompt -> {
                    long paired = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                            .map(message -> (ToolResponseMessage) message).flatMap(message -> message.getResponses().stream())
                            .filter(response -> "first-call".equals(response.id()) && "read_first".equals(response.name())
                                    && response.responseData().contains("artifact-first")).count();
                    assertEquals(1, paired, "The admitted second request retains its complete first tool pairing");
                    return ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall("second-call", "function", "read_second",
                            "{\"artifactId\":\"artifact-first\"}"));
                });
        StepPermit revisedStep = null;
        boolean originalExited = false;
        try {
            var ledger = ledger(f, f.step, policy);
            var firstSaver = new InspectingSaver(f, false);
            assertBlocked(f.adapter(ledger, model, firstSaver).invoke(PROMPT), MODEL_LIMIT);
            assertEquals(2, model.callCount()); model.assertExhausted();
            assertEquals(2, f.responses()); assertEquals(2, f.modelChildren());
            assertEquals(1, f.firstCalls.get()); assertEquals(1, f.secondCalls.get());
            assertEquals(2L, modelUsage(f)); assertEquals(2L, callUsage(f));
            assertEquals(PROMPT, f.jdbc.queryForObject("SELECT original_input FROM campaign_exploration_session", String.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name)='campaign_exploration_turn' "
                    + "AND LOWER(column_name)='invocation_json'", Integer.class), "The turn index must not duplicate each full MODEL request");
            assertEquals(2, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE child_mode='MODEL' AND model_invocation_json IS NOT NULL", Integer.class));
            Artifact first = f.runs.readArtifact(f.token.definition().caller(), "artifact-first", f.authorizer);
            Artifact second = f.runs.readArtifact(f.token.definition().caller(), "artifact-second", f.authorizer);
            var originalChildren = childFacts(f);

            var emptySaver = new InspectingSaver(f, false);
            assertBlocked(f.adapter(ledger(f, f.step, policy), model, emptySaver).invoke(PROMPT), MODEL_LIMIT);
            assertTrue(emptySaver.writes.isEmpty()); assertEquals(2, model.callCount());
            assertEquals(2L, modelUsage(f)); assertEquals(1, f.firstCalls.get()); assertEquals(1, f.secondCalls.get());
            f.steps.settle(f.step, StepStatus.BLOCKED, Map.of(), MODEL_LIMIT, f.authorizer);
            f.steps.callbackExited(f.step); originalExited = true;

            // Simulate upgrading an existing _10 database: all authoritative MODEL/CALL/turn facts remain.
            f.jdbc.update("DELETE FROM campaign_exploration_budget_slot WHERE run_id=?", f.token.definition().runId());
            f.jdbc.update("DELETE FROM campaign_exploration_budget WHERE run_id=?", f.token.definition().runId());
            assertEquals(2, f.responses()); assertEquals(2, f.callCount());
            RunToken revised = f.runs.revise(f.token, 2, revision(f.token.definition()).definitionJson());
            f.steps.initialize(revised, List.of(new StepSpec(f.planStep.stepId(), new ObjectMapper().writeValueAsString(f.planStep), List.of(), Set.of(), Set.of())));
            revisedStep = f.steps.beginStep(revised, f.planStep.stepId());
            var revisedSaver = new InspectingSaver(f, false);
            assertBlocked(f.adapter(ledger(f, revisedStep, policy), model, revisedSaver).invoke(PROMPT), MODEL_LIMIT);
            assertEquals(2L, modelUsage(f)); assertEquals(2L, callUsage(f));
            assertEquals(2, model.callCount()); assertEquals(2, f.responses()); assertEquals(2, f.modelChildren());
            assertEquals(1, f.firstCalls.get()); assertEquals(1, f.secondCalls.get());
            assertEquals(first, f.runs.readArtifact(revised.definition().caller(), "artifact-first", f.authorizer));
            assertEquals(second, f.runs.readArtifact(revised.definition().caller(), "artifact-second", f.authorizer));
            assertEquals(originalChildren, childFacts(f), "Revision fencing preserves the old requests, attempts and result identities");
            assertEquals(PROMPT, f.jdbc.queryForObject("SELECT original_input FROM campaign_exploration_session WHERE revision=1", String.class));
        } finally {
            if (revisedStep != null) f.steps.callbackExited(revisedStep);
            if (!originalExited) f.steps.callbackExited(f.step);
        }
        f.exited();
    }

    @Test
    void completeChineseRequestIsLimitedByUtf8BytesBeforeAnyModelToolOrCheckpoint() throws Exception {
        var f = new Fixture(false);
        String input = "统计".repeat(128);
        var request = new ModelInvocationRegistry.Request(ModelInvocationRegistry.REQUEST_SCHEMA,
                List.of(new ModelInvocationRegistry.Message("user", input, null, null, null)), f.configuration.tools());
        String encoded = ModelInvocationRegistry.encodeRequest(request);
        int characters = encoded.length(), bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        long maximumBytes = (characters + bytes) / 2L;
        assertTrue(characters < maximumBytes && maximumBytes < bytes,
                "This is the full canonical request, including both tool schemas and JSON field names");
        assertTrue(input.getBytes(StandardCharsets.UTF_8).length < maximumBytes,
                "The full request, rather than the prompt alone, is what exceeds the budget");
        var policy = new ExplorationBudgetPolicy("campaign-explore", "1", 4, 4, 2, maximumBytes);
        var model = new ScriptedExplorationChatModel();
        var saver = new InspectingSaver(f, false);
        try {
            assertBlocked(f.adapter(ledger(f, f.step, policy), model, saver).invoke(input), CONTEXT_LIMIT);
            assertEquals(0, model.callCount()); assertEquals(0, f.firstCalls.get()); assertEquals(0, f.secondCalls.get());
            assertEquals(0, f.responses()); assertEquals(0, f.modelChildren()); assertEquals(0, f.callCount());
            assertTrue(saver.writes.isEmpty(), "Oversized canonical context never enters a native checkpoint");
            assertEquals(0L, modelUsage(f)); assertEquals(0L, callUsage(f));
            String stored = f.jdbc.queryForObject("SELECT original_input FROM campaign_exploration_session", String.class);
            if (stored != null) assertEquals(input, stored, "An admitted original input must never be truncated to make it fit");
            var reopenedSaver = new InspectingSaver(f, false);
            assertBlocked(f.adapter(ledger(f, f.step, policy), model, reopenedSaver).invoke(input), CONTEXT_LIMIT);
            assertEquals(0, model.callCount()); assertTrue(reopenedSaver.writes.isEmpty());
        } finally { f.steps.callbackExited(f.step); }
        f.exited();
    }

    private static JdbcExplorationLedger ledger(Fixture f, StepPermit step, ExplorationBudgetPolicy policy) {
        return new JdbcExplorationLedger(f.jdbc, f.transactions, CLOCK,
                new JdbcCampaignRunStore(f.jdbc, f.transactions, CLOCK), new JdbcCampaignStepStore(f.jdbc, f.transactions, CLOCK),
                new JdbcCampaignExplorationCallStore(f.jdbc, f.transactions, CLOCK), step, f.models, f.configuration, f.executors, f.authorizer, policy);
    }
    private static RunDefinition revision(RunDefinition original) {
        var prior = FrozenCampaignRun.read(original); var plan = prior.plan(); var assessment = prior.assessment();
        var nextPlan = new PlanSpec(plan.schemaVersion(), plan.planId(), 2, plan.runId(), plan.inputSetRef(), plan.goals(), plan.steps());
        var nextAssessment = new PlanningAssessment(assessment.planId(), 2, assessment.capabilityCatalogVersion(), assessment.requirements(), assessment.coverageBindings(), assessment.gaps());
        return FrozenCampaignRun.freeze(nextPlan, prior.inputs(), nextAssessment).definition(original.caller(), original.sessionId());
    }
    private static long modelUsage(Fixture f) { return f.jdbc.queryForObject("SELECT model_turns FROM campaign_exploration_budget", Long.class); }
    private static long callUsage(Fixture f) { return f.jdbc.queryForObject("SELECT capability_calls FROM campaign_exploration_budget", Long.class); }
    private static List<Map<String, Object>> childFacts(Fixture f) {
        return f.jdbc.queryForList("SELECT child_id,action_id,child_state,request_id,wire_hash,job_id,artifact_id,attempt_id,attempt_version,"
                + "parent_call_id,parent_call_attempt_id,callback_active FROM campaign_child_ledger WHERE revision=1 AND child_mode<>'MODEL' ORDER BY child_id");
    }
    private static void assertBlocked(Map<String, Object> result, String reason) {
        assertEquals("BLOCKED", result.get("status")); assertEquals(reason, result.get("reason"));
    }
}
