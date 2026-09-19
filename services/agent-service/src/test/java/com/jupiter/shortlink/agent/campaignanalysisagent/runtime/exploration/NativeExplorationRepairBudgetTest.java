package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeDurableExplorationLedgerTest.Fixture;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.NativeDurableExplorationLedgerTest.InspectingSaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignExplorationCallStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;

/** One real native/H2 repair scenario; the server allowance survives replacement of native state. */
@Timeout(30)
class NativeExplorationRepairBudgetTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String PROMPT = "Analyze the authorized evidence";
    private static final String REPAIR_LIMIT = "EXPLORATION_REPAIR_BUDGET_EXHAUSTED";
    private static final String REJECTED = "{\"executed\":false,\"code\":\"BATCH_REJECTED\"}";

    @Test
    void repeatedBatchRejectionStopsAtDurableRepairAllowanceWithoutDispatchOrUnpairedMessagesAfterReopening() throws Exception {
        var f = new Fixture(false);
        var policy = new ExplorationBudgetPolicy("campaign-explore-repair", "1", 8, 8, 1, 65536);
        var model = new ScriptedExplorationChatModel(
                prompt -> batch("batch-1", "batch-2"),
                prompt -> {
                    assertPairedRejections(prompt.getInstructions(), Set.of("batch-1", "batch-2"));
                    assertNoTools(f);
                    return batch("batch-3", "batch-4");
                });
        var saver = new InspectingSaver(f, false);
        try {
            var ledger = ledger(f, policy);
            Map<String, Object> result = f.adapter(ledger, model, saver).invoke(PROMPT);
            assertBlocked(result);
            assertEquals(List.of(), result.get("artifactIds"));
            assertEquals(2, model.callCount()); model.assertExhausted();
            assertEquals(2, f.responses()); assertEquals(2, f.modelChildren());
            assertNoTools(f);
            assertEquals(1L, repairUsage(f));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_budget_slot WHERE slot_kind='REPAIR'", Integer.class));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_turn WHERE repair_counted=TRUE", Integer.class));
            assertEquals(2L, f.jdbc.queryForObject("SELECT model_turns FROM campaign_exploration_budget", Long.class));
            assertEquals(0L, f.jdbc.queryForObject("SELECT capability_calls FROM campaign_exploration_budget", Long.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_turn WHERE decision='FINAL'", Integer.class),
                    "Model text COMPLETE cannot bypass the batch protocol or publish a candidate");

            var checkpoint = saver.get(RunnableConfig.builder().threadId(ledger.identity().threadId()).build()).orElseThrow();
            var messages = assertInstanceOf(List.class, checkpoint.getState().get("messages"));
            assertPairedRejections(messages, Set.of("batch-1", "batch-2", "batch-3", "batch-4"));
            assertFalse(saver.writes.isEmpty());

            var emptySaver = new InspectingSaver(f, false);
            var fresh = ledger(f, policy);
            assertBlocked(f.adapter(fresh, model, emptySaver).invoke(PROMPT));
            assertTrue(emptySaver.writes.isEmpty(), "A persisted budget stop does not reopen native execution");
            assertEquals(2, model.callCount()); assertNoTools(f);
            assertEquals(1L, repairUsage(f));
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_budget_slot WHERE slot_kind='REPAIR'", Integer.class));
            assertEquals(2, f.responses()); assertEquals(0, f.activeChildren()); assertEquals(0, f.activeCalls());
        } finally { f.steps.callbackExited(f.step); }
        f.exited();
    }

    private static ChatResponse batch(String first, String second) {
        return ScriptedExplorationChatModel.response(AssistantMessage.builder().content("COMPLETE")
                .toolCalls(List.of(new AssistantMessage.ToolCall(first, "function", "read_first", "{}"),
                        new AssistantMessage.ToolCall(second, "function", "read_second", "{\"artifactId\":\"not-produced\"}")))
                .build(), "stop");
    }

    private static void assertPairedRejections(List<?> messages, Set<String> expectedIds) {
        Map<String, String> pending = new LinkedHashMap<>();
        Set<String> calls = new HashSet<>(), responses = new HashSet<>();
        for (Object message : messages) {
            if (message instanceof ToolResponseMessage tool) {
                for (var response : tool.getResponses()) {
                    assertTrue(responses.add(response.id()), "Each rejected ID has exactly one tool response");
                    assertEquals(pending.remove(response.id()), response.name(), "A response must pair with its original tool name and ID");
                    assertEquals(REJECTED, response.responseData());
                }
            } else {
                assertTrue(pending.isEmpty(), "No new model/user message may cross an unpaired batch");
                if (message instanceof AssistantMessage assistant) for (var call : assistant.getToolCalls()) {
                    assertTrue(calls.add(call.id()), "All model call IDs in this scenario are distinct");
                    pending.put(call.id(), call.name());
                }
            }
        }
        assertTrue(pending.isEmpty(), "The final native state must not leave orphaned model call IDs");
        assertEquals(expectedIds, calls); assertEquals(expectedIds, responses);
    }

    private static JdbcExplorationLedger ledger(Fixture f, ExplorationBudgetPolicy policy) {
        return new JdbcExplorationLedger(f.jdbc, f.transactions, CLOCK,
                new JdbcCampaignRunStore(f.jdbc, f.transactions, CLOCK), new JdbcCampaignStepStore(f.jdbc, f.transactions, CLOCK),
                new JdbcCampaignExplorationCallStore(f.jdbc, f.transactions, CLOCK), f.step, f.models, f.configuration,
                f.executors, f.authorizer, policy);
    }
    private static void assertBlocked(Map<String, Object> result) {
        assertEquals("BLOCKED", result.get("status")); assertEquals(REPAIR_LIMIT, result.get("reason"));
    }
    private static void assertNoTools(Fixture f) {
        assertEquals(0, f.firstCalls.get()); assertEquals(0, f.secondCalls.get()); assertEquals(0, f.submits.get());
        assertEquals(0, f.callCount());
    }
    private static long repairUsage(Fixture f) {
        return f.jdbc.queryForObject("SELECT repairs FROM campaign_exploration_budget", Long.class);
    }
}
