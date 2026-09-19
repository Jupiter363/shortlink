package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real JDBC staging/publication; only the remote job responses and one publish fault are fixtures. */
@Timeout(30)
class StatisticsJobResultReceiverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRES = NOW.plusSeconds(3600).toEpochMilli();
    private static final long START = LocalDate.parse("2026-09-01").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    private static final long END = LocalDate.parse("2026-09-03").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    private static final Caller OWNER = new Caller("1001", "analyst-1", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst-1", 7, false);
    private static final String CHILD = "child-1", JOB = "job-1", ARTIFACT = "artifact-job-1";
    private static final ArtifactAuthorizer ALLOW = (caller, artifact) -> true;
    private static final StatisticsJobResultReceiver.Target TARGET =
            new StatisticsJobResultReceiver.Target(ARTIFACT, "scope-current-group", "periods-original");

    @Test
    void restartContinuesAtTheDurableNextPageAndReadyEvidenceNeverRereadsTheJob() throws Exception {
        Fixture fixture = fixture();
        SourceGateway gateway = new SourceGateway(501);
        var first = fixture.receiver(gateway, 1).receive(fixture.token(), CHILD, PRINCIPAL, TARGET, () -> true);
        assertEquals(RECEIVING, first.outcome());
        assertEquals(List.of(0), gateway.pages);
        Receipt partial = fixture.results().receipt(fixture.token(), CHILD).orElseThrow();
        assertEquals(500, partial.storedRows());
        assertEquals(1, partial.nextPageIndex());
        assertFalse(partial.published());
        assertThrows(IllegalStateException.class, () -> fixture.results().readPage(OWNER, ARTIFACT, 0, ALLOW));
        assertCallbacksExited(fixture);

        Fixture reopened = fixture.reopen();
        var second = reopened.receiver(gateway, 1).receive(reopened.token(), CHILD, PRINCIPAL, TARGET, () -> true);
        assertEquals(READY, second.outcome());
        assertEquals(List.of(0, 1), gateway.pages, "Restart must not fetch the already committed page again");
        assertEquals(2, gateway.statusCalls.get());
        Receipt complete = reopened.results().receipt(reopened.token(), CHILD).orElseThrow();
        assertTrue(complete.complete());
        assertTrue(complete.published());
        assertEquals(501, complete.storedRows());
        assertEquals(ChildState.READY, reopened.runs().child(reopened.token(), CHILD).orElseThrow().state());

        JsonNode page = JSON.readTree(reopened.results().readPage(OWNER, ARTIFACT, 0, ALLOW));
        assertEquals(500, page.path("items").size());
        assertEquals("PARTIAL", page.path("meta").path("completeness").asText());
        assertEquals("UNKNOWN", page.path("meta").path("collectionQuality").path("status").asText());
        assertEquals(1, JSON.readTree(reopened.results().readPage(OWNER, ARTIFACT, 1, ALLOW)).path("items").size());
        assertThrows(SecurityException.class, () -> reopened.results().readPage(OWNER, ARTIFACT, 0, (caller, metadata) -> false));
        String manifestHash = reopened.runs().inspectArtifact(OWNER, ARTIFACT, ALLOW).ref().payloadHash();
        var again = reopened.receiver(gateway, 1).receive(reopened.token(), CHILD, PRINCIPAL, TARGET, () -> true);
        assertEquals(ALREADY_READY, again.outcome());
        assertEquals(manifestHash, reopened.runs().inspectArtifact(OWNER, ARTIFACT, ALLOW).ref().payloadHash());
        assertEquals(2, gateway.statusCalls.get());
        assertEquals(List.of(0, 1), gateway.pages);
        assertCallbacksExited(reopened);
        gateway.assertNoSubmission();
    }

    @Test
    void workQuantumContinuesBeyondTenPagesWithoutTreatingTheFirstFiveThousandRowsAsComplete() throws Exception {
        Fixture fixture = fixture();
        SourceGateway gateway = new SourceGateway(5501);
        StatisticsJobResultReceiver receiver = fixture.receiver(gateway, 3);
        for (int pass = 0; pass < 4; pass++) {
            var received = receiver.receive(fixture.token(), CHILD, PRINCIPAL, TARGET, () -> true);
            assertEquals(pass == 3 ? READY : RECEIVING, received.outcome());
            assertEquals((pass + 1) * 3, received.receivedPages());
            if (pass < 3) assertEquals(0, artifactCount(fixture));
            assertCallbacksExited(fixture);
        }
        assertEquals(java.util.stream.IntStream.range(0, 12).boxed().toList(), gateway.pages);
        assertEquals(4, gateway.statusCalls.get());
        Receipt receipt = fixture.results().receipt(fixture.token(), CHILD).orElseThrow();
        assertEquals(5501, receipt.storedRows());
        assertEquals(12, receipt.nextPageIndex());
        assertTrue(receipt.published());
        JsonNode last = JSON.readTree(fixture.results().readPage(OWNER, ARTIFACT, 11, ALLOW));
        assertEquals(1, last.path("items").size());
        assertEquals("event-5500", last.path("items").get(0).path("eventId").asText());
        gateway.assertNoSubmission();
    }

    @Test
    void revocationAfterStatusAndCancellationDuringTheNextPageKeepStagingButCannotPublish() throws Exception {
        for (boolean cancelDuringPage : List.of(false, true)) {
            Fixture fixture = fixture();
            SourceGateway gateway = new SourceGateway(501);
            AtomicBoolean authorized = new AtomicBoolean(true);
            assertEquals(RECEIVING, fixture.receiver(gateway, 1)
                    .receive(fixture.token(), CHILD, PRINCIPAL, TARGET, authorized::get).outcome());
            String firstChecksum = fixture.jdbc().queryForObject(
                    "SELECT checksum FROM campaign_statistics_page WHERE page_index=0", String.class);
            if (cancelDuringPage) {
                gateway.afterPage = index -> {
                    assertEquals(1, index);
                    assertEquals(1, activeCallbacks(fixture));
                    fixture.runs().cancel(fixture.token());
                };
            } else {
                gateway.afterStatus = () -> {
                    assertEquals(1, activeCallbacks(fixture));
                    authorized.set(false);
                };
            }
            var stopped = fixture.receiver(gateway, 1)
                    .receive(fixture.token(), CHILD, PRINCIPAL, TARGET, authorized::get);
            assertEquals(STOPPED, stopped.outcome());
            assertEquals(cancelDuringPage ? List.of(0, 1) : List.of(0), gateway.pages);
            assertEquals(2, gateway.statusCalls.get());
            assertEquals(1, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_statistics_page", Integer.class));
            assertEquals(firstChecksum, fixture.jdbc().queryForObject(
                    "SELECT checksum FROM campaign_statistics_page WHERE page_index=0", String.class));
            assertEquals(0, artifactCount(fixture));
            assertEquals(false, fixture.jdbc().queryForObject("SELECT published FROM campaign_statistics_receipt", Boolean.class));
            assertEquals(JOB, fixture.jdbc().queryForObject("SELECT job_id FROM campaign_child_ledger", String.class));
            assertNotEquals(ChildState.READY.name(), fixture.jdbc().queryForObject("SELECT child_state FROM campaign_child_ledger", String.class));
            assertCallbacksExited(fixture);
            gateway.assertNoSubmission();
        }
    }

    @Test
    void wrongSnapshotAndRemoteFailedExpiredOrUnsupportedReadsCannotProduceReadyEvidence() throws Exception {
        for (String scenario : List.of("WRONG_SNAPSHOT", "FAILED", "EXPIRED", "PROTOCOL_UNAVAILABLE")) {
            Fixture fixture = fixture();
            SourceGateway gateway = new SourceGateway(1);
            switch (scenario) {
                case "WRONG_SNAPSHOT" -> gateway.pageResponse = index -> {
                    Map<String, Object> data = gateway.pageData(index);
                    @SuppressWarnings("unchecked") var meta = (Map<String, Object>) data.get("meta");
                    meta.put("snapshotId", "another-job");
                    return ToolResult.success(data);
                };
                case "FAILED" -> gateway.statusState = "FAILED";
                case "EXPIRED" -> gateway.expiresAt = NOW.minusMillis(1).toEpochMilli();
                case "PROTOCOL_UNAVAILABLE" -> gateway.statusFailure =
                        new ToolResult(false, Map.of("code", "STATISTICS_READ_PROTOCOL_UNAVAILABLE"), "Unavailable");
                default -> fail("Unknown fixture");
            }
            var blocked = fixture.receiver(gateway, 2)
                    .receive(fixture.token(), CHILD, PRINCIPAL, TARGET, () -> true);
            assertEquals(BLOCKED, blocked.outcome(), scenario);
            assertNotNull(blocked.code());
            assertEquals(scenario.equals("WRONG_SNAPSHOT") ? List.of(0) : List.of(), gateway.pages);
            assertEquals(1, gateway.statusCalls.get());
            assertEquals(0, artifactCount(fixture));
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_statistics_page", Integer.class));
            ChildRecord child = fixture.runs().child(fixture.token(), CHILD).orElseThrow();
            assertEquals(ChildState.UNRESOLVED, child.state());
            assertEquals(JOB, child.jobId());
            assertNull(child.artifactId());
            assertCallbacksExited(fixture);
            gateway.assertNoSubmission();
        }
    }

    @Test
    void completeStagingSurvivesPublishFailureAndRestartPublishesWithoutRefetchingPages() throws Exception {
        Fixture fixture = fixture();
        SourceGateway gateway = new SourceGateway(501);
        PublishOnceFailure failing = new PublishOnceFailure(fixture.results());
        var interrupted = new StatisticsJobResultReceiver(fixture.runs(), failing, gateway, CLOCK, 2)
                .receive(fixture.token(), CHILD, PRINCIPAL, TARGET, () -> true);
        assertEquals(BLOCKED, interrupted.outcome());
        assertEquals("FIXTURE_PUBLISH_INTERRUPTED", interrupted.code());
        assertEquals(1, failing.publishCalls);
        Receipt staged = fixture.results().receipt(fixture.token(), CHILD).orElseThrow();
        assertTrue(staged.complete());
        assertFalse(staged.published());
        assertEquals(0, artifactCount(fixture));
        assertEquals(List.of(0, 1), gateway.pages);
        assertCallbacksExited(fixture);

        Fixture reopened = fixture.reopen();
        var finished = reopened.receiver(gateway, 1).receive(reopened.token(), CHILD, PRINCIPAL, TARGET, () -> true);
        assertEquals(READY, finished.outcome());
        assertEquals(2, gateway.statusCalls.get(), "Recheck original job authority and expiry before publishing staged pages");
        assertEquals(List.of(0, 1), gateway.pages, "Every page was committed before the publish fault");
        Receipt published = reopened.results().receipt(reopened.token(), CHILD).orElseThrow();
        assertEquals(staged.chainHash(), published.chainHash());
        assertTrue(published.published());
        assertEquals(1, artifactCount(reopened));
        assertEquals(ARTIFACT, reopened.runs().child(reopened.token(), CHILD).orElseThrow().artifactId());
        assertEquals(500, JSON.readTree(reopened.results().readPage(OWNER, ARTIFACT, 0, ALLOW)).path("items").size());
        assertCallbacksExited(reopened);
        gateway.assertNoSubmission();
    }

    private static Fixture fixture() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:statistics_job_receiver_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        CampaignRunStore runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
        RunToken token = runs.createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1, "{}"));
        runs.prepareAction(token, new ActionSpec("action-1", "step-1", "TOOL", "fixture-access-records", "1", "{}"));
        String wire = JSON.writeValueAsString(Map.of("requestId", "request-1", "gid", "group-a",
                "startDate", "2026-09-01", "endDate", "2026-09-02", "queryKind", "ACCESS_RECORDS"));
        runs.prepareChild(token, new ChildSpec(CHILD, "action-1", ChildMode.ASYNC, "request-1",
                new WireRequest("POST", "/internal/short-link-admin/v1/agent-tools/statistics/jobs", wire)));
        DispatchPermit submitted = runs.beginDispatch(token, CHILD);
        try { runs.recordWaiting(submitted, JOB); }
        finally { runs.callbackExited(submitted); }
        return new Fixture(jdbc, tx, runs, new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK), token);
    }

    private static int artifactCount(Fixture fixture) {
        return fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class);
    }

    private static int activeCallbacks(Fixture fixture) {
        return fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class);
    }

    private static void assertCallbacksExited(Fixture fixture) { assertEquals(0, activeCallbacks(fixture)); }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate tx, CampaignRunStore runs,
                           CampaignStatisticsResultStore results, RunToken token) {
        Fixture reopen() {
            CampaignRunStore reopened = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
            return new Fixture(jdbc, tx, reopened, new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK), reopened.advance(token));
        }
        StatisticsJobResultReceiver receiver(SourceGateway gateway, int quantum) {
            return new StatisticsJobResultReceiver(runs, results, gateway, CLOCK, quantum);
        }
    }

    private static final class SourceGateway implements ShortLinkBusinessGateway {
        final int rows;
        final int pageCount;
        final long bytes;
        final AtomicInteger statusCalls = new AtomicInteger();
        final AtomicInteger forbiddenCalls = new AtomicInteger();
        final List<Integer> pages = new ArrayList<>();
        String statusState = "SUCCEEDED";
        long expiresAt = EXPIRES;
        ToolResult statusFailure;
        Runnable afterStatus = () -> {};
        IntConsumer afterPage = index -> {};
        IntFunction<ToolResult> pageResponse = index -> ToolResult.success(pageData(index));

        SourceGateway(int rows) throws Exception {
            this.rows = rows;
            this.pageCount = (rows + 499) / 500;
            long count = 0;
            for (int i = 0; i < pageCount; i++) count += JSON.writeValueAsString(items(i)).getBytes(StandardCharsets.UTF_8).length;
            this.bytes = count;
        }
        @Override public ToolResult readStatisticsJob(ToolContext context, String jobId) {
            assertEquals(PRINCIPAL, context.principal());
            assertEquals(JOB, jobId);
            statusCalls.incrementAndGet();
            ToolResult result = statusFailure != null ? statusFailure : ToolResult.success(Map.of(
                    "jobId", JOB, "state", statusState, "rowCount", rows, "byteCount", bytes,
                    "pageCount", pageCount, "expiresAt", expiresAt));
            afterStatus.run();
            return result;
        }
        @Override public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int pageIndex, int size) {
            assertEquals(PRINCIPAL, context.principal());
            assertEquals(JOB, jobId);
            assertEquals(500, size);
            pages.add(pageIndex);
            ToolResult result = pageResponse.apply(pageIndex);
            afterPage.accept(pageIndex);
            return result;
        }
        Map<String, Object> pageData(int index) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "group-a"); meta.put("linkIds", List.of(1L));
            meta.put("snapshotId", JOB); meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
            meta.put("sourceCut", Map.of("manifestSelectionHash", "hash-1"));
            meta.put("manifestVersion", Map.of("selectionHash", "hash-1")); meta.put("manifestSelectionHash", "hash-1");
            meta.put("requestedStart", START); meta.put("requestedEnd", END); meta.put("effectiveEnd", END);
            meta.put("businessTimezone", "Asia/Shanghai"); meta.put("snapshotCreatedAt", NOW.toEpochMilli());
            meta.put("snapshotExpiresAt", expiresAt); meta.put("groupScopeComplete", true);
            meta.put("pageIndex", index); meta.put("nextPageIndex", index + 1 < pageCount ? index + 1 : null);
            meta.put("totalRows", rows); meta.put("availability", "AVAILABLE"); meta.put("freshness", "FRESH");
            meta.put("completeness", "PARTIAL"); meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
            meta.put("missingMetrics", List.of("producerCollectionCompleteness"));
            return new LinkedHashMap<>(Map.of("items", items(index), "metrics", Map.of(), "meta", meta));
        }
        private List<Map<String, Object>> items(int index) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = index * 500; i < Math.min(rows, (index + 1) * 500); i++)
                result.add(Map.of("eventId", "event-" + i, "linkId", 1L, "occurredAt", START + i,
                        "kind", "CLICK", "status", 302));
            return result;
        }
        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> params) { return forbidden(); }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> request) { return forbidden(); }
        @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) { return forbidden(); }
        private ToolResult forbidden() {
            forbiddenCalls.incrementAndGet();
            throw new AssertionError("Known-job reception must use only structured status/page reads");
        }
        void assertNoSubmission() { assertEquals(0, forbiddenCalls.get()); }
    }

    /** Fault only at the application publication boundary; every other operation uses the real JDBC store. */
    private static final class PublishOnceFailure implements CampaignStatisticsResultStore {
        private final CampaignStatisticsResultStore delegate;
        private int publishCalls;
        PublishOnceFailure(CampaignStatisticsResultStore delegate) { this.delegate = delegate; }
        @Override public Optional<Receipt> receipt(RunToken token, String childId) { return delegate.receipt(token, childId); }
        @Override public Receipt initialize(DispatchPermit permit, ReceiptSpec spec) { return delegate.initialize(permit, spec); }
        @Override public Receipt append(DispatchPermit permit, Page page) { return delegate.append(permit, page); }
        @Override public ArtifactRef publish(DispatchPermit permit) {
            if (++publishCalls == 1) throw new IllegalStateException("FIXTURE_PUBLISH_INTERRUPTED");
            return delegate.publish(permit);
        }
        @Override public String readPage(Caller caller, String artifactId, int index, ArtifactAuthorizer authorizer) {
            return delegate.readPage(caller, artifactId, index, authorizer);
        }
    }
}
