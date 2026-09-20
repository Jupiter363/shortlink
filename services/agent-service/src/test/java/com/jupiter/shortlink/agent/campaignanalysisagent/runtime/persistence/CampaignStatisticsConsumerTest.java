package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Outcome;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Target;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignStatisticsConsumerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRY = NOW.plusSeconds(3600).toEpochMilli();
    private static final Caller OWNER = new Caller("1001", "analyst-1", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst-1", 7, false);
    private static final PlanSpec.ExecutorRef EXECUTOR =
            new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics_query_job", "1");
    private static final String CONTRACT = "statistics-job-pages/v1";
    private static final String CHILD = "source-child", JOB = "original-job", ACTION = "source-action";
    private static final String SCOPE = "scope-frozen", PERIODS = "periods-original";
    private static final Target TARGET = new Target("source-pages", SCOPE, PERIODS);
    private static final ArtifactAuthorizer READ = (caller, artifact) -> true;
    private static final Authorizer CONSUME = (token, binding, expected) -> true;

    @Test
    void atomicRevisionAdoptionResumesOriginalPagesWithoutResubmissionAndRevokedConsumerCannotPublish() throws Exception {
        Fixture f = new Fixture(501);
        assertEquals(1, f.gateway.submits.get());
        assertEquals(Outcome.RECEIVING, f.receiver().receive(f.original, CHILD, PRINCIPAL, TARGET, () -> true).outcome());
        assertEquals(List.of(0), f.gateway.pages);
        Binding pinned = f.consumers.resolve(f.original, f.consumer(f.original, "source"), CONSUME).binding();
        assertEquals(EXPIRY, pinned.expiresAtMillis(), "Retention comes from the real structured status response");
        assertEquals(f.wire.hash(), pinned.requestHash());
        assertEquals(1, pinned.producerRevision());
        var partial = f.results.receipt(f.original, CHILD).orElseThrow();
        assertEquals(500, partial.storedRows());
        assertFalse(partial.published());
        assertEquals(0, f.callbacks());

        RunDefinition revision = f.definition(2);
        Expectation incompatible = new Expectation("source", EXECUTOR, CONTRACT, f.wire.hash(),
                new Target(TARGET.artifactId(), "hidden-other-scope", PERIODS));
        assertThrows(RuntimeException.class, () -> f.tx.execute(status -> {
            RunToken next = f.runs.revise(f.original, 2, revision.definitionJson());
            return f.consumers.adopt(next, f.consumer(next, "source"), f.binding(), incompatible, CONSUME);
        }));
        assertEquals(f.original, f.runs.loadRun(OWNER, f.original.definition().runId()).orElseThrow().token());
        assertEquals(1, f.count("campaign_run_ledger"));
        assertEquals(1, f.count("campaign_statistics_consumer"));
        assertEquals(partial, f.results.receipt(f.original, CHILD).orElseThrow(), "Revision failure must not consume the first page");

        RunToken adopted = f.tx.execute(status -> {
            RunToken next = f.runs.revise(f.original, 2, revision.definitionJson());
            f.consumers.adopt(next, f.consumer(next, "source"), f.binding(), f.expected("source"), CONSUME);
            return next;
        });
        assertNotNull(adopted);
        assertThrows(RuntimeException.class, () -> f.runs.beginReconciliation(f.original, CHILD));
        JdbcCampaignRunStore reopenedRuns = new JdbcCampaignRunStore(f.jdbc, f.tx, CLOCK);
        var reopenedConsumers = new JdbcCampaignStatisticsConsumerStore(f.jdbc, f.tx, CLOCK, reopenedRuns);
        var reopenedResults = new JdbcCampaignStatisticsResultStore(f.jdbc, f.tx, CLOCK);
        RunToken current = reopenedRuns.advance(adopted);
        var receiver = new StatisticsJobResultReceiver(reopenedRuns, reopenedResults, f.gateway, CLOCK, 1, reopenedConsumers);
        String currentConsumer = f.consumer(current, "source");
        assertThrows(RuntimeException.class, () -> reopenedConsumers.resolve(adopted, currentConsumer, CONSUME));
        assertEquals(Outcome.READY, receiver.receiveAdopted(current, currentConsumer, PRINCIPAL, () -> true).outcome());
        assertEquals(List.of(0, 1), f.gateway.pages);
        assertEquals(2, f.gateway.statusCalls.get());
        assertEquals(1, f.gateway.submits.get());
        var consumption = reopenedConsumers.resolve(current, currentConsumer, CONSUME);
        var receipt = reopenedResults.receipt(consumption.sourceToken(), CHILD).orElseThrow();
        assertTrue(receipt.complete());
        assertTrue(receipt.published());
        assertEquals(EXPIRY, receipt.spec().expiresAtMillis());
        Artifact artifact = reopenedRuns.readArtifact(OWNER, TARGET.artifactId(), READ);
        assertEquals(1, artifact.metadata().revision());
        assertEquals(f.original.definition().runId(), artifact.metadata().runId());
        assertEquals(ACTION, artifact.metadata().actionId());
        assertEquals(CHILD, artifact.metadata().childId());
        assertEquals(Instant.ofEpochMilli(EXPIRY), artifact.metadata().ref().expiresAt());
        assertEquals(500, JSON.readTree(reopenedResults.readPage(OWNER, TARGET.artifactId(), 0, READ)).path("items").size());
        assertEquals(1, JSON.readTree(reopenedResults.readPage(OWNER, TARGET.artifactId(), 1, READ)).path("items").size());
        assertTrue(artifact.metadata().qualityJson().contains("PARTIAL"));
        assertTrue(artifact.metadata().qualityJson().contains("UNKNOWN"));
        assertEquals(Outcome.ALREADY_READY, receiver.receiveAdopted(current, currentConsumer, PRINCIPAL, () -> true).outcome());
        assertEquals(artifact, reopenedRuns.readArtifact(OWNER, TARGET.artifactId(), READ));
        assertEquals(List.of(0, 1), f.gateway.pages);
        assertEquals(2, f.gateway.statusCalls.get());
        assertEquals(1, f.count("campaign_child_ledger"), "Adoption does not invent a second producer child");
        assertEquals(0, f.callbacks());
        f.gateway.assertOnlyDedicatedCalls();

        // Revoke while the adopted page call is active; actual callback exit, not revocation, permits later recovery.
        Fixture revoked = new Fixture(1);
        revoked.gateway.state = "RUNNING";
        assertEquals(Outcome.WAITING, revoked.receiver().receive(revoked.original, CHILD, PRINCIPAL, TARGET, () -> true).outcome());
        RunToken revocableConsumer = revoked.reviseAndAdopt();
        AtomicBoolean grant = new AtomicBoolean(true);
        revoked.gateway.state = "SUCCEEDED";
        revoked.gateway.afterPage = () -> {
            assertEquals(1, revoked.callbacks());
            grant.set(false);
            assertEquals(1, revoked.callbacks(), "Revocation is not an actual callback exit");
        };
        assertEquals(Outcome.STOPPED, revoked.receiver().receiveAdopted(revocableConsumer,
                revoked.consumer(revocableConsumer, "source"), PRINCIPAL, grant::get).outcome());
        assertEquals(0, revoked.count("campaign_artifact"));
        assertEquals(0, revoked.count("campaign_statistics_page"));
        assertEquals(0, revoked.callbacks());
        assertEquals(JOB, revoked.jdbc.queryForObject("SELECT job_id FROM campaign_child_ledger", String.class));
        grant.set(true);
        revoked.gateway.afterPage = () -> {};
        assertEquals(Outcome.READY, revoked.receiver().receiveAdopted(revocableConsumer,
                revoked.consumer(revocableConsumer, "source"), PRINCIPAL, grant::get).outcome());
        assertEquals(1, revoked.gateway.submits.get());
        assertEquals(List.of(0, 0), revoked.gateway.pages, "A revoked, uncommitted page may be read again; the job is never resubmitted");
        assertEquals(1, revoked.count("campaign_artifact"));
        assertEquals(0, revoked.callbacks());
    }

    @Test
    void adoptionAndCancellationShareOneInterlockAndReleasePinsTheVerifiedLocalCopyWithoutReopeningRemoteUse() throws Exception {
        for (String order : List.of("ADOPT_FIRST", "CANCEL_FIRST", "RACE")) {
            Fixture f = new Fixture(1);
            f.gateway.state = "RUNNING";
            assertEquals(Outcome.WAITING, f.receiver().receive(f.original, CHILD, PRINCIPAL, TARGET, () -> true).outcome());
            assertTrue(f.gateway.pages.isEmpty());
            String first = f.consumer(f.original, "source"), second = f.consumer(f.original, "consumer");
            CancelDecision decision;
            if ("ADOPT_FIRST".equals(order)) {
                f.consumers.adopt(f.original, second, f.binding(), f.expected("consumer"), CONSUME);
                f.consumers.retire(f.original, first);
                decision = f.consumers.requestCancel(f.original, f.binding());
                assertEquals(CancelIntent.NONE, decision.state());
                assertFalse(decision.dispatchRequired(), "Retiring the old consumer must not cancel another consumer's job");
                assertTrue(f.consumers.resolve(f.original, second, CONSUME).consumer().active());
                f.consumers.retire(f.original, second);
                decision = f.consumers.requestCancel(f.original, f.binding());
            } else if ("CANCEL_FIRST".equals(order)) {
                f.consumers.retire(f.original, first);
                decision = f.consumers.requestCancel(f.original, f.binding());
                assertThrows(RuntimeException.class, () ->
                        f.consumers.adopt(f.original, second, f.binding(), f.expected("consumer"), CONSUME));
            } else {
                f.consumers.retire(f.original, first);
                var workers = Executors.newFixedThreadPool(2);
                CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
                try {
                    var adoption = workers.submit(() -> {
                        ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                        try {
                            f.consumers.adopt(f.original, second, f.binding(), f.expected("consumer"), CONSUME);
                            return true;
                        } catch (IllegalStateException | SecurityException rejected) { return false; }
                    });
                    var cancellation = workers.submit(() -> {
                        ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                        return f.consumers.requestCancel(f.original, f.binding());
                    });
                    assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
                    boolean adopted = adoption.get(10, TimeUnit.SECONDS);
                    decision = cancellation.get(10, TimeUnit.SECONDS);
                    assertNotEquals(adopted, decision.dispatchRequired(), "Exactly one side may win the shared job interlock");
                    if (adopted) {
                        assertEquals(CancelIntent.NONE, decision.state());
                        f.consumers.retire(f.original, second);
                        decision = f.consumers.requestCancel(f.original, f.binding());
                    }
                } finally { start.countDown(); workers.shutdownNow(); }
            }
            assertEquals(CancelIntent.REQUESTED, decision.state());
            assertTrue(decision.dispatchRequired());
            var reopened = new JdbcCampaignStatisticsConsumerStore(f.jdbc, f.tx, CLOCK, f.runs);
            CancelDecision lostAck = reopened.requestCancel(f.original, f.binding());
            assertEquals(CancelIntent.REQUESTED, lostAck.state());
            assertEquals(decision.bindingVersion(), lostAck.bindingVersion());
            assertFalse(lostAck.dispatchRequired(), "Missing acknowledgement does not reopen admission or request a second cancel");
            assertThrows(RuntimeException.class, () -> reopened.adopt(f.original, f.consumer(f.original, "late"),
                    f.binding(), f.expected("late"), CONSUME));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_consumer WHERE active=TRUE", Integer.class));
            f.gateway.state = "CANCELLED";
            var status = new StatisticsJobResultProtocol(f.runs.child(f.original, CHILD).orElseThrow())
                    .status(f.gateway.readStatisticsJob(f.context(), JOB).data());
            reopened.confirmCancelled(f.original, f.binding(), decision.bindingVersion(), status);
            assertEquals(CancelIntent.CONFIRMED, reopened.requestCancel(f.original, f.binding()).state());
            assertThrows(RuntimeException.class, () -> reopened.adopt(f.original, second, f.binding(), f.expected("consumer"), CONSUME));
            assertEquals(1, f.gateway.submits.get());
            assertEquals(0, f.callbacks());
            assertTrue(f.gateway.pages.isEmpty());
            f.gateway.assertOnlyDedicatedCalls();
        }

        Fixture released = new Fixture(1);
        assertEquals(Outcome.READY, released.receiver().receive(released.original, CHILD, PRINCIPAL, TARGET, () -> true).outcome());
        Artifact original = released.runs.readArtifact(OWNER, TARGET.artifactId(), READ);
        var releases = new JdbcCampaignStatisticsReleaseStore(released.jdbc, released.tx, CLOCK);
        var intent = releases.prepare(released.original, CHILD, READ);
        assertEquals(CampaignStatisticsReleaseStore.State.REQUESTED, intent.state());
        DispatchPermit releaseAttempt = released.runs.beginRelease(released.original, CHILD);
        try { assertTrue(releases.mayRelease(releaseAttempt, intent), "The shared gate must allow its exact live release attempt"); }
        finally { released.runs.callbackExited(releaseAttempt); }
        assertTrue(released.consumers.resolve(released.original, released.consumer(released.original, "source"), CONSUME)
                .binding().localOnly());
        assertEquals(original, released.runs.readArtifact(OWNER, TARGET.artifactId(), READ));
        assertEquals(1, JSON.readTree(released.results.readPage(OWNER, TARGET.artifactId(), 0, READ)).path("items").size());
        assertThrows(RuntimeException.class, () -> released.consumers.adopt(released.original,
                released.consumer(released.original, "consumer"), released.binding(), released.expected("consumer"), CONSUME));
        assertFalse(released.consumers.requestCancel(released.original, released.binding()).dispatchRequired());
        assertEquals(1, released.gateway.submits.get());
        assertEquals(0, released.callbacks());
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final JdbcCampaignRunStore runs;
        final JdbcCampaignStatisticsConsumerStore consumers;
        final CampaignStatisticsResultStore results;
        final RunToken original;
        final WireRequest wire;
        final Gateway gateway;
        final FrozenQueryScope scope;

        Fixture(int totalRows) throws Exception {
            var source = new DriverManagerDataSource("jdbc:h2:mem:statistics_consumer_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql"),
                    new ClassPathResource("sql/migration/V20260920_19__campaign_statistics_consumers.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
            consumers = new JdbcCampaignStatisticsConsumerStore(jdbc, tx, CLOCK, runs);
            results = new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK);
            String hash = FrozenQueryScope.memberHash(List.of(1L));
            scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", SCOPE, hash, 1,
                    "a".repeat(64), FrozenQueryScope.shardIdFor(SCOPE, 0, hash), 0, 1, hash, List.of(1L));
            wire = new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH,
                    JSON.writeValueAsString(Map.of("requestId", "original-request", "gid", "group-a",
                            "startDate", "2026-09-01", "endDate", "2026-09-02", "queryKind", "ACCESS_RECORDS", "scope", scope.asMap())));
            original = runs.createRun(definition(1));
            PlanSpec.Step step = FrozenCampaignRun.read(original.definition()).plan().steps().get(0);
            runs.prepareAction(original, new ActionSpec(ACTION, "source", EXECUTOR.kind().name(),
                    EXECUTOR.name(), EXECUTOR.version(), JSON.writeValueAsString(step)));
            runs.prepareChild(original, new ChildSpec(CHILD, ACTION, ChildMode.ASYNC, "original-request", wire));
            gateway = new Gateway(totalRows, scope);
            DispatchPermit submitted = runs.beginDispatch(original, CHILD);
            try {
                ToolResult response = gateway.submitFrozenStatisticsJob(context(),
                        StatisticsJobResultProtocol.originalRequest(runs.child(original, CHILD).orElseThrow().spec()));
                assertTrue(response.success());
                runs.recordWaiting(submitted, (String) ((Map<?, ?>) response.data()).get("jobId"));
            } finally { runs.callbackExited(submitted); }
        }

        RunDefinition definition(int revision) {
            var steps = List.of("source", "consumer", "late").stream().map(id -> new PlanSpec.Step(id,
                    List.of("delivery"), PlanSpec.ExecutionMode.FIXED, EXECUTOR, null, List.of(),
                    Map.of("scope", PlanBinding.input("scope-input"), "periods", PlanBinding.input("period-input")),
                    Map.of(), CONTRACT)).toList();
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", revision, "run-1", "inputs-1",
                    List.of(new PlanSpec.Goal("delivery", "Read the existing job", true, "Original pages")), steps);
            var inputs = new FrozenInputSet("inputs-1", "run-1", Map.of(
                    "scope-input", new CapabilityCatalog.Port(new CapabilityCatalog.TypeRef("ScopeRef", 1, CapabilityCatalog.Cardinality.ONE), true),
                    "period-input", new CapabilityCatalog.Port(new CapabilityCatalog.TypeRef("PeriodsRef", 1, CapabilityCatalog.Cardinality.ONE), true)),
                    Map.of("scope-input", SCOPE, "period-input", PERIODS));
            var assessment = new PlanningAssessment("plan-1", revision, "fixture-catalog/1", List.of(
                    new PlanningAssessment.Requirement("delivery-pages", "delivery", PlanningAssessment.RequirementKind.DELIVERY,
                            true, "pages", "1", Map.of())), List.of(new PlanningAssessment.CoverageBinding("delivery-pages",
                    List.of(new PlanningAssessment.EvidenceOutput("source", "pages")))), List.of());
            return FrozenCampaignRun.freeze(plan, inputs, assessment).definition(OWNER, "session-1");
        }

        RunToken reviseAndAdopt() {
            return tx.execute(status -> {
                RunToken next = runs.revise(original, 2, definition(2).definitionJson());
                consumers.adopt(next, consumer(next, "source"), binding(), expected("source"), CONSUME);
                return next;
            });
        }
        String binding() { return bindingId(OWNER, JOB); }
        String consumer(RunToken token, String step) { return consumerId(token.definition(), step, binding()); }
        Expectation expected(String step) { return new Expectation(step, EXECUTOR, CONTRACT, wire.hash(), TARGET); }
        ToolContext context() { return new ToolContext("session-1", PRINCIPAL.username(), Map.of(), PRINCIPAL); }
        StatisticsJobResultReceiver receiver() { return new StatisticsJobResultReceiver(runs, results, gateway, CLOCK, 1, consumers); }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        int callbacks() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class); }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final int totalRows;
        final FrozenQueryScope scope;
        final AtomicInteger submits = new AtomicInteger(), statusCalls = new AtomicInteger(), forbidden = new AtomicInteger();
        final List<Integer> pages = new ArrayList<>();
        String state = "SUCCEEDED";
        Runnable afterPage = () -> {};
        Gateway(int totalRows, FrozenQueryScope scope) { this.totalRows = totalRows; this.scope = scope; }
        @Override public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            assertEquals(PRINCIPAL, context.principal());
            assertEquals("original-request", request.get("requestId"));
            assertEquals(scope, FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope")));
            submits.incrementAndGet();
            return ToolResult.success(Map.of("jobId", JOB, "state", "QUEUED"));
        }
        @Override public ToolResult readStatisticsJob(ToolContext context, String jobId) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, jobId);
            statusCalls.incrementAndGet();
            return ToolResult.success(Map.of("jobId", JOB, "state", state, "rowCount", totalRows,
                    "pageCount", (totalRows + 499) / 500, "byteCount", totalRows * 100L, "expiresAt", EXPIRY));
        }
        @Override public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int index, int size) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, jobId); assertEquals(500, size);
            pages.add(index);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "group-a"); meta.put("linkIds", scope.linkIds());
            meta.put("snapshotId", JOB); meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
            meta.put("sourceCut", Map.of("manifestSelectionHash", "hash-1"));
            meta.put("manifestVersion", Map.of("selectionHash", "hash-1")); meta.put("manifestSelectionHash", "hash-1");
            meta.put("requestedStart", day("2026-09-01")); meta.put("requestedEnd", day("2026-09-03"));
            meta.put("effectiveEnd", day("2026-09-03")); meta.put("businessTimezone", "Asia/Shanghai");
            meta.put("snapshotCreatedAt", NOW.toEpochMilli()); meta.put("snapshotExpiresAt", EXPIRY);
            meta.put("groupScopeComplete", false); meta.put("scopeProof", scope.proof("b".repeat(64)));
            meta.put("pageIndex", index); meta.put("nextPageIndex", (index + 1) * 500 < totalRows ? index + 1 : null);
            meta.put("totalRows", totalRows); meta.put("availability", "AVAILABLE"); meta.put("freshness", "FRESH");
            meta.put("completeness", "PARTIAL"); meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = index * 500; i < Math.min(totalRows, (index + 1) * 500); i++)
                rows.add(Map.of("eventId", "event-" + i, "linkId", 1L, "occurredAt", day("2026-09-01") + i));
            ToolResult result = ToolResult.success(Map.of("items", rows, "metrics", Map.of(), "meta", meta));
            afterPage.run();
            return result;
        }
        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) { return forbidden(); }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> request) { return forbidden(); }
        @Override public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) { return forbidden(); }
        private ToolResult forbidden() { forbidden.incrementAndGet(); throw new AssertionError("Known jobs must not be resubmitted or recovered by identity"); }
        void assertOnlyDedicatedCalls() { assertEquals(0, forbidden.get()); }
    }
    private static long day(String date) {
        return LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}
