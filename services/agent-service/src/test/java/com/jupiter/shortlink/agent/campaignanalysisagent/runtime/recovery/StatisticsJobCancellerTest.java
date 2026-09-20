package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsCancellationStore.State;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class StatisticsJobCancellerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRY = NOW.plusSeconds(3600).toEpochMilli();
    private static final Caller OWNER = new Caller("1001", "analyst-1", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst-1", 7, false);
    private static final PlanSpec.ExecutorRef REF = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics_query_job", "1");
    private static final String CHILD = "source-child", JOB = "original-job", SCOPE = "scope-frozen", PERIODS = "periods-original";
    private static final String SECRET = "untrusted-provider-payload-must-not-escape";

    @Test
    void lostCancellationAcknowledgementReopensOnlyTheOriginalStatusAndConfirmsAfterActualCallbackExit() throws Exception {
        Fixture f = new Fixture();
        AtomicBoolean remoteCancelled = new AtomicBoolean();
        f.gateway.cancelResponse = () -> {
            assertEquals(1, f.scope.activeCount());
            assertTrue(f.operations().operation(f.token, f.binding()).orElseThrow().callbackActive());
            remoteCancelled.set(true); // The external operation happened; only its acknowledgement is lost.
            throw new IllegalStateException(SECRET);
        };
        var first = f.cancel();
        assertEquals(StatisticsJobCanceller.Outcome.PENDING, first.outcome());
        assertEquals("REMOTE_UNAVAILABLE", first.code());
        assertFalse(first.toString().contains(SECRET));
        assertTrue(remoteCancelled.get());
        assertEquals(1, f.gateway.cancelPosts.get());
        assertEquals(1, f.gateway.statusReads.get(), "Only the original Receiver pin has read status so far");
        var unknown = f.operations().operation(f.token, f.binding()).orElseThrow();
        assertEquals(State.UNKNOWN, unknown.state());
        assertFalse(unknown.callbackActive());
        assertEquals(CancelIntent.REQUESTED, f.cancelIntent());
        f.assertExited();

        // A fresh coordinator/store cannot infer non-dispatch from a missing response.
        f.gateway.status = "RUNNING";
        var stillRunning = f.cancel();
        assertEquals(StatisticsJobCanceller.Outcome.PENDING, stillRunning.outcome());
        assertEquals("RUNNING", stillRunning.observedState());
        assertEquals(CancelIntent.REQUESTED, f.cancelIntent());
        assertEquals(1, f.gateway.cancelPosts.get());
        assertEquals(2, f.gateway.statusReads.get());
        f.gateway.status = "CANCELLED";
        var completed = f.cancel();
        assertEquals(StatisticsJobCanceller.Outcome.TERMINAL, completed.outcome());
        assertEquals("CANCELLED", completed.observedState());
        assertEquals(CancelIntent.CONFIRMED, f.cancelIntent());
        assertEquals(1, f.gateway.cancelPosts.get());
        assertEquals(3, f.gateway.statusReads.get());
        var terminal = f.operations().operation(f.token, f.binding()).orElseThrow();
        assertEquals(State.TERMINAL, terminal.state());
        assertEquals(3, terminal.attemptVersion());
        assertFalse(terminal.callbackActive());
        assertEquals(completed, f.cancel());
        assertEquals(1, f.gateway.cancelPosts.get());
        assertEquals(3, f.gateway.statusReads.get());
        assertEquals(1, f.gateway.statisticsSubmits.get());
        assertEquals(JOB, f.runs.child(f.token, CHILD).orElseThrow().jobId());
        assertEquals(f.wire.hash(), f.runs.child(f.token, CHILD).orElseThrow().spec().wire().hash());
        assertEquals(0, f.gateway.pageReads.get());
        assertEquals(0, f.count("campaign_artifact"));
        f.assertExited();
        f.scope.closeAndAwaitActualExit();
        assertThrows(IllegalStateException.class, f::cancel);
        assertEquals(1, f.gateway.cancelPosts.get());
    }

    @Test
    void realTerminalStatesRemainTruthfulWhileLegacyIntentAndInvalidOrRevokedRepliesNeverGainAnotherCancelDispatch() throws Exception {
        for (String terminalState : List.of("SUCCEEDED", "FAILED")) {
            Fixture f = new Fixture();
            f.gateway.cancelResponse = () -> ToolResult.success(status(terminalState));
            var result = f.cancel();
            assertEquals(StatisticsJobCanceller.Outcome.TERMINAL, result.outcome());
            assertEquals(terminalState, result.observedState());
            assertEquals("TERMINAL_ALREADY_FINISHED", result.code());
            assertEquals(CancelIntent.REQUESTED, f.cancelIntent(), "Finished jobs must not be relabelled cancelled");
            assertEquals(result, f.cancel());
            assertEquals(1, f.gateway.cancelPosts.get());
            assertEquals(1, f.gateway.statusReads.get());
            assertEquals(1, f.gateway.statisticsSubmits.get());
            f.assertExited();
        }

        Fixture legacy = new Fixture();
        assertTrue(legacy.consumers.requestCancel(legacy.token, legacy.binding()).dispatchRequired());
        assertTrue(legacy.operations().operation(legacy.token, legacy.binding()).isEmpty());
        assertEquals(StatisticsJobCanceller.Outcome.PENDING, legacy.cancel().outcome());
        assertEquals(0, legacy.gateway.cancelPosts.get(), "A pre-existing REQUESTED intent is not proof that POST was never sent");
        assertEquals(2, legacy.gateway.statusReads.get());
        assertEquals(State.UNKNOWN, legacy.operations().operation(legacy.token, legacy.binding()).orElseThrow().state());
        legacy.assertExited();

        for (String invalid : List.of("WRONG_JOB", "MISSING_EXPIRY", "EXPIRED_REPLY")) {
            Fixture f = new Fixture();
            var malformed = status("CANCELLED");
            if (invalid.equals("WRONG_JOB")) malformed.put("jobId", "some-other-job");
            if (invalid.equals("MISSING_EXPIRY")) malformed.remove("expiresAt");
            if (invalid.equals("EXPIRED_REPLY")) malformed.put("expiresAt", NOW.toEpochMilli());
            f.gateway.cancelResponse = () -> ToolResult.success(malformed);
            var rejected = f.cancel();
            assertEquals(StatisticsJobCanceller.Outcome.BLOCKED, rejected.outcome(), invalid);
            assertEquals("STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE", rejected.code());
            assertEquals(State.UNKNOWN, f.operations().operation(f.token, f.binding()).orElseThrow().state());
            assertEquals(CancelIntent.REQUESTED, f.cancelIntent());
            assertEquals(1, f.gateway.cancelPosts.get());
            assertEquals(StatisticsJobCanceller.Outcome.PENDING, f.cancel().outcome());
            assertEquals(1, f.gateway.cancelPosts.get(), "An invalid acknowledgement permits only GET reconciliation");
            assertEquals(2, f.gateway.statusReads.get());
            assertEquals(1, f.gateway.statisticsSubmits.get());
            f.assertExited();
        }

        Fixture revoked = new Fixture();
        assertThrows(IllegalStateException.class, () -> revoked.tx.execute(status -> revoked.cancel()));
        assertThrows(SecurityException.class, () -> new StatisticsJobCanceller(revoked.operations(), revoked.gateway, CLOCK)
                .cancel(revoked.token, revoked.binding(), new AgentPrincipal("1001", "other-user", 7, false),
                        revoked.authorizer(), revoked.scope));
        assertTrue(revoked.operations().operation(revoked.token, revoked.binding()).isEmpty());
        assertEquals(CancelIntent.NONE, revoked.cancelIntent());
        revoked.grant.set(false);
        assertEquals(StatisticsJobCanceller.Outcome.STOPPED, revoked.cancel().outcome());
        assertTrue(revoked.operations().operation(revoked.token, revoked.binding()).isEmpty());
        assertEquals(CancelIntent.NONE, revoked.cancelIntent());
        assertEquals(0, revoked.gateway.cancelPosts.get());
        revoked.grant.set(true);
        revoked.gateway.cancelResponse = () -> {
            assertEquals(1, revoked.scope.activeCount());
            assertTrue(revoked.operations().operation(revoked.token, revoked.binding()).orElseThrow().callbackActive());
            revoked.grant.set(false);
            return ToolResult.success(status("CANCELLED"));
        };
        assertEquals(StatisticsJobCanceller.Outcome.STOPPED, revoked.cancel().outcome());
        assertEquals(CancelIntent.REQUESTED, revoked.cancelIntent(), "A response arriving after grant revocation is not confirmed");
        assertEquals(State.UNKNOWN, revoked.operations().operation(revoked.token, revoked.binding()).orElseThrow().state());
        assertEquals(1, revoked.gateway.cancelPosts.get());
        assertEquals(1, revoked.gateway.statusReads.get());
        assertEquals(1, revoked.gateway.statisticsSubmits.get());
        revoked.assertExited();
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final JdbcCampaignRunStore runs;
        final JdbcCampaignStatisticsConsumerStore consumers;
        final RunToken token;
        final WireRequest wire;
        final Gateway gateway = new Gateway();
        final AtomicBoolean grant = new AtomicBoolean(true);
        final ProcessExecutionScope scope = new ProcessExecutionScope();

        Fixture() throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:statistics_cancel_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql"),
                    new ClassPathResource("sql/migration/V20260920_20__campaign_statistics_cancellation.sql")).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
            consumers = new JdbcCampaignStatisticsConsumerStore(jdbc, tx, CLOCK, runs);
            var step = new PlanSpec.Step("query", List.of("delivery"), PlanSpec.ExecutionMode.FIXED, REF, null,
                    List.of(), Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods")),
                    Map.of(), "statistics-job-pages/v1");
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                    List.of(new PlanSpec.Goal("delivery", "Read original statistics", true, "Original pages")), List.of(step));
            var inputs = new FrozenInputSet("inputs-1", "run-1", Map.of(
                    "scope", new CapabilityCatalog.Port(new CapabilityCatalog.TypeRef("ScopeRef", 1, CapabilityCatalog.Cardinality.ONE), true),
                    "periods", new CapabilityCatalog.Port(new CapabilityCatalog.TypeRef("PeriodsRef", 1, CapabilityCatalog.Cardinality.ONE), true)),
                    Map.of("scope", SCOPE, "periods", PERIODS));
            var assessment = new PlanningAssessment("plan-1", 1, "fixture-catalog/1", List.of(
                    new PlanningAssessment.Requirement("delivery-pages", "delivery", PlanningAssessment.RequirementKind.DELIVERY,
                            true, "pages", "1", Map.of())), List.of(new PlanningAssessment.CoverageBinding("delivery-pages",
                    List.of(new PlanningAssessment.EvidenceOutput("query", "pages")))), List.of());
            token = runs.createRun(FrozenCampaignRun.freeze(plan, inputs, assessment).definition(OWNER, "session-1"));
            var members = List.of(1L);
            String hash = FrozenQueryScope.memberHash(members);
            var frozen = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", SCOPE, hash, 1, "a".repeat(64),
                    FrozenQueryScope.shardIdFor(SCOPE, 0, hash), 0, 1, hash, members);
            wire = new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, JSON.writeValueAsString(
                    Map.of("requestId", "original-request", "gid", "group-a", "startDate", "2026-09-01",
                            "endDate", "2026-09-02", "queryKind", "ACCESS_RECORDS", "scope", frozen.asMap())));
            runs.prepareAction(token, new ActionSpec("query-action", "query", "TOOL", REF.name(), REF.version(), JSON.writeValueAsString(step)));
            runs.prepareChild(token, new ChildSpec(CHILD, "query-action", ChildMode.ASYNC, "original-request", wire));
            DispatchPermit submit = runs.beginDispatch(token, CHILD);
            try {
                ToolResult response = gateway.submitFrozenStatisticsJob(context(), StatisticsJobResultProtocol.originalRequest(runs.child(token, CHILD).orElseThrow().spec()));
                runs.recordWaiting(submit, (String) ((Map<?, ?>) response.data()).get("jobId"));
            } finally { runs.callbackExited(submit); }
            var receiver = new StatisticsJobResultReceiver(runs, new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK), gateway, CLOCK, consumers);
            assertEquals(StatisticsJobResultReceiver.Outcome.WAITING,
                    receiver.receive(token, CHILD, PRINCIPAL, new StatisticsJobResultReceiver.Target("original-pages", SCOPE, PERIODS), () -> true).outcome());
            consumers.retire(token, consumerId(token.definition(), "query", binding()));
            assertEquals(1, gateway.statisticsSubmits.get());
            assertEquals(1, gateway.statusReads.get());
            assertEquals(0, count("campaign_statistics_cancellation"));
        }
        String binding() { return bindingId(OWNER, JOB); }
        JdbcCampaignStatisticsCancellationStore operations() { return new JdbcCampaignStatisticsCancellationStore(jdbc, tx, CLOCK, consumers); }
        StatisticsJobCanceller.Authorizer authorizer() {
            return (current, binding, request) -> {
                assertEquals(token, current); assertEquals(wire.hash(), binding.requestHash());
                assertEquals("group-a", request.get("gid")); assertEquals("original-request", request.get("requestId"));
                assertEquals(SCOPE, ((Map<?, ?>) request.get("scope")).get("parentScopeRef"));
                return grant.get();
            };
        }
        StatisticsJobCanceller.Result cancel() {
            return new StatisticsJobCanceller(operations(), gateway, CLOCK).cancel(token, binding(), PRINCIPAL, authorizer(), scope);
        }
        CancelIntent cancelIntent() {
            return CancelIntent.valueOf(jdbc.queryForObject("SELECT cancel_intent FROM campaign_statistics_job_binding", String.class));
        }
        ToolContext context() { return new ToolContext("session-1", PRINCIPAL.username(), Map.of(), PRINCIPAL); }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        void assertExited() {
            assertEquals(0, scope.activeCount());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertTrue(operations().operation(token, binding()).map(value -> !value.callbackActive()).orElse(true));
        }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final AtomicInteger statisticsSubmits = new AtomicInteger(), cancelPosts = new AtomicInteger(), statusReads = new AtomicInteger(), pageReads = new AtomicInteger();
        String status = "RUNNING";
        Supplier<ToolResult> cancelResponse = () -> ToolResult.success(status("CANCELLED"));
        @Override public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals("original-request", request.get("requestId"));
            statisticsSubmits.incrementAndGet(); return ToolResult.success(Map.of("jobId", JOB, "state", "QUEUED"));
        }
        @Override public ToolResult cancelStatisticsJob(ToolContext context, String jobId) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, jobId);
            cancelPosts.incrementAndGet(); return cancelResponse.get();
        }
        @Override public ToolResult readStatisticsJob(ToolContext context, String jobId) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, jobId);
            statusReads.incrementAndGet(); return ToolResult.success(status(status));
        }
        @Override public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int page, int size) {
            pageReads.incrementAndGet(); throw new AssertionError("Cancellation cannot fetch evidence pages");
        }
        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy fallback"); }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> request) { throw new AssertionError("No legacy fallback"); }
        @Override public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) { throw new AssertionError("No submission recovery while cancelling"); }
    }
    private static Map<String, Object> status(String state) {
        var result = new LinkedHashMap<String, Object>();
        result.put("jobId", JOB); result.put("state", state); result.put("expiresAt", EXPIRY);
        result.put("resultState", state.equals("SUCCEEDED") ? "AVAILABLE" : List.of("FAILED", "CANCELLED").contains(state) ? "UNAVAILABLE" : "PENDING");
        result.put("resultReady", state.equals("SUCCEEDED")); result.put("resultCode", null);
        return result;
    }
}
