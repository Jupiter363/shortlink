package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReleaser.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.ReceiptSpec;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real local pages, release intent and run fencing; only remote status/release calls are fixtures. */
@Timeout(30)
class StatisticsJobResultReleaserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRES = NOW.plusSeconds(3600).toEpochMilli();
    private static final Caller OWNER = new Caller("1001", "analyst-1", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst-1", 7, false);
    private static final String CHILD = "child-1", JOB = "job-1", ARTIFACT = "artifact-1";
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> OWNER.equals(caller);
    private static final String MEMBER_HASH = FrozenQueryScope.memberHash(List.of(99L));
    private static final FrozenQueryScope SCOPE = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET",
            "scope-original", MEMBER_HASH, 1, "b".repeat(64),
            FrozenQueryScope.shardIdFor("scope-original", 0, MEMBER_HASH), 0, 1, MEMBER_HASH, List.of(99L));

    @Test
    void confirmedReleaseKeepsReadyEvidenceAndOriginalBindingsAndNeverCallsRemoteAgain() throws Exception {
        Fixture fixture = fixture(true);
        Gateway gateway = new Gateway();
        Artifact before = fixture.runs.readArtifact(OWNER, ARTIFACT, ALLOW);
        String localPage = fixture.results.readPage(OWNER, ARTIFACT, 0, ALLOW);
        var released = fixture.releaser(gateway).release(fixture.token, CHILD, PRINCIPAL, ALLOW, () -> true);
        assertEquals(CONFIRMED, released.outcome());
        assertEquals(CHILD, released.childId()); assertEquals(JOB, released.jobId());
        assertEquals(1, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        assertEquals(JSON.readTree(JSON.writeValueAsString(fixture.request)), JSON.readTree(JSON.writeValueAsString(gateway.releasedRequest)));
        assertEquals(JSON.readTree(JSON.writeValueAsString(SCOPE.asMap())),
                JSON.readTree(JSON.writeValueAsString(gateway.releasedRequest.get("scope"))));
        assertEquals(fixture.runs.child(fixture.token, CHILD).orElseThrow().spec().wire().bodyJson(),
                JSON.writeValueAsString(fixture.request));
        assertEquals(before, fixture.runs.readArtifact(OWNER, ARTIFACT, ALLOW));
        assertEquals(localPage, fixture.results.readPage(OWNER, ARTIFACT, 0, ALLOW));
        assertEquals(EXPIRES, before.metadata().ref().expiresAt().toEpochMilli());
        assertEquals("scope-original", before.metadata().ref().scopeRef());
        assertEquals("periods-original", before.metadata().ref().periodsRef());
        JsonNode proof = JSON.readTree(before.metadata().provenanceJson()).path("scopeProof");
        assertEquals(MEMBER_HASH, proof.path("shardMemberHash").asText());
        assertEquals("PARTIAL", JSON.readTree(localPage).path("meta").path("completeness").asText());
        var confirmed = fixture.releases.intent(fixture.token, CHILD).orElseThrow();
        assertEquals(CampaignStatisticsReleaseStore.State.CONFIRMED, confirmed.state());
        assertEquals("original-request", confirmed.requestId());
        assertEquals(before.metadata().ref().payloadHash(), confirmed.artifactHash());
        assertEquals(EXPIRES, confirmed.expiresAtMillis());
        Fixture reopened = fixture.reopen();
        assertEquals(ALREADY_CONFIRMED, reopened.releaser(gateway)
                .release(reopened.token, CHILD, PRINCIPAL, ALLOW, () -> true).outcome());
        assertEquals(1, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        assertReadyAndExited(reopened);
        gateway.assertNoOtherCalls();
    }

    @Test
    void lostReleaseAcknowledgementIsConfirmedAfterRestartFromStatusWithoutAnotherRelease() throws Exception {
        Fixture fixture = fixture(true);
        Gateway gateway = new Gateway();
        gateway.loseAcknowledgement = true;
        String originalHash = fixture.runs.inspectArtifact(OWNER, ARTIFACT, ALLOW).ref().payloadHash();
        var uncertain = fixture.releaser(gateway).release(fixture.token, CHILD, PRINCIPAL, ALLOW, () -> true);
        assertEquals(BLOCKED, uncertain.outcome());
        assertEquals(1, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        assertReadyAndExited(fixture);
        var pending = fixture.releases.intent(fixture.token, CHILD).orElseThrow();
        assertEquals(CampaignStatisticsReleaseStore.State.REQUESTED, pending.state(), "A lost acknowledgement cannot confirm release");

        Fixture reopened = fixture.reopen();
        var recovered = reopened.releaser(gateway).release(reopened.token, CHILD, PRINCIPAL, ALLOW, () -> true);
        assertEquals(CONFIRMED, recovered.outcome());
        assertEquals(2, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        assertEquals(originalHash, reopened.runs.inspectArtifact(OWNER, ARTIFACT, ALLOW).ref().payloadHash());
        var confirmed = reopened.releases.intent(reopened.token, CHILD).orElseThrow();
        assertEquals(CampaignStatisticsReleaseStore.State.CONFIRMED, confirmed.state());
        assertEquals(pending.bindingId(), confirmed.bindingId());
        assertEquals(pending.requestHash(), confirmed.requestHash());
        assertEquals(ALREADY_CONFIRMED, reopened.releaser(gateway)
                .release(reopened.token, CHILD, PRINCIPAL, ALLOW, () -> true).outcome());
        assertEquals(2, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        assertReadyAndExited(reopened);
        gateway.assertNoOtherCalls();
    }

    @Test
    void ineligibleOrRevokedLocalEvidenceCannotReleaseAndCancellationKeepsOnlyLateConfirmation() throws Exception {
        for (String scenario : List.of("STAGING", "CORRUPTED", "REVOKED", "AUTHORITY_REVOKED_AFTER_STATUS")) {
            Fixture fixture = fixture(!"STAGING".equals(scenario));
            Gateway gateway = new Gateway();
            AtomicBoolean authorized = new AtomicBoolean(true);
            if ("AUTHORITY_REVOKED_AFTER_STATUS".equals(scenario)) gateway.duringStatus = () -> authorized.set(false);
            if ("CORRUPTED".equals(scenario))
                fixture.jdbc.update("UPDATE campaign_statistics_page SET payload_json='{}' WHERE page_index=0");
            ArtifactAuthorizer artifactAccess = "REVOKED".equals(scenario) ? (caller, metadata) -> false : ALLOW;
            var refused = fixture.releaser(gateway).release(fixture.token, CHILD, PRINCIPAL, artifactAccess, authorized::get);
            assertNotEquals(CONFIRMED, refused.outcome(), scenario);
            assertNotEquals(ALREADY_CONFIRMED, refused.outcome(), scenario);
            assertEquals("AUTHORITY_REVOKED_AFTER_STATUS".equals(scenario) ? 1 : 0, gateway.statusCalls.get(), scenario);
            assertEquals(0, gateway.releaseCalls.get(), scenario);
            if ("AUTHORITY_REVOKED_AFTER_STATUS".equals(scenario)) {
                assertEquals(STOPPED, refused.outcome());
                assertEquals(CampaignStatisticsReleaseStore.State.REQUESTED,
                        fixture.releases.intent(fixture.token, CHILD).orElseThrow().state());
            }
            assertEquals(0, activeCallbacks(fixture), scenario);
            gateway.assertNoOtherCalls();
        }

        Fixture fixture = fixture(true);
        Gateway gateway = new Gateway();
        Artifact before = fixture.runs.readArtifact(OWNER, ARTIFACT, ALLOW);
        gateway.duringRelease = () -> {
            assertEquals(1, activeCallbacks(fixture));
            assertThrows(IllegalStateException.class, () -> fixture.runs.advance(fixture.token),
                    "A live callback prevents writer takeover before cancellation");
            fixture.runs.cancel(fixture.token);
            assertEquals(1, activeCallbacks(fixture), "Cancel is not proof that the wire callback exited");
        };
        var stopped = fixture.releaser(gateway).release(fixture.token, CHILD, PRINCIPAL, ALLOW, () -> true);
        assertEquals(STOPPED, stopped.outcome());
        var cancelled = fixture.runs.loadRun(OWNER, fixture.token.definition().runId()).orElseThrow();
        assertEquals(RunStatus.CANCELLED, cancelled.status());
        assertEquals(before, fixture.runs.readArtifact(OWNER, ARTIFACT, ALLOW));
        assertEquals(ChildState.READY.name(), fixture.jdbc.queryForObject("SELECT child_state FROM campaign_child_ledger", String.class));
        assertEquals(0, activeCallbacks(fixture));
        assertEquals(CampaignStatisticsReleaseStore.State.CONFIRMED,
                fixture.releases.intent(fixture.token, CHILD).orElseThrow().state(),
                "The exact old callback may retain a remote release fact without reviving the Run");
        assertEquals(1, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        var again = fixture.releaser(gateway).release(cancelled.token(), CHILD, PRINCIPAL, ALLOW, () -> true);
        assertEquals(STOPPED, again.outcome());
        assertEquals(1, gateway.statusCalls.get()); assertEquals(1, gateway.releaseCalls.get());
        gateway.assertNoOtherCalls();
    }

    private static Fixture fixture(boolean publish) throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:statistics_release_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
        RunToken token = runs.createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1, "{}"));
        runs.prepareAction(token, new ActionSpec("action-1", "step-1", "TOOL", "statistics-query", "1", "{}"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", "original-request"); request.put("gid", "group-a");
        request.put("startDate", "2026-09-01"); request.put("endDate", "2026-09-02");
        request.put("queryKind", "ACCESS_RECORDS"); request.put("scope", SCOPE.asMap());
        WireRequest wire = new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, JSON.writeValueAsString(request));
        runs.prepareChild(token, new ChildSpec(CHILD, "action-1", ChildMode.ASYNC, "original-request", wire));
        DispatchPermit submitted = runs.beginDispatch(token, CHILD);
        try { runs.recordWaiting(submitted, JOB); } finally { runs.callbackExited(submitted); }
        var results = new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK);
        var protocol = new StatisticsJobResultProtocol(runs.child(token, CHILD).orElseThrow());
        DispatchPermit received = runs.beginReconciliation(token, CHILD);
        try {
            results.initialize(received, new ReceiptSpec(JOB, wire.hash(), ARTIFACT, SCOPE.parentScopeRef(),
                    "periods-original", 1, 1, EXPIRES));
            results.append(received, protocol.page(protocol.status(status(false)), page(), 0));
            if (publish) results.publish(received); else runs.recordWaiting(received, JOB);
        } finally { runs.callbackExited(received); }
        return new Fixture(jdbc, tx, token, request);
    }

    private static Map<String, Object> page() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "group-a"); meta.put("linkIds", List.of(99L));
        meta.put("snapshotId", JOB); meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
        meta.put("sourceCut", Map.of("manifestSelectionHash", "manifest-1"));
        meta.put("manifestVersion", Map.of("selectionHash", "manifest-1"));
        meta.put("requestedStart", day("2026-09-01")); meta.put("requestedEnd", day("2026-09-03"));
        meta.put("effectiveEnd", day("2026-09-03")); meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("snapshotExpiresAt", EXPIRES); meta.put("groupScopeComplete", false);
        meta.put("scopeProof", SCOPE.proof("a".repeat(64))); meta.put("pageIndex", 0); meta.put("nextPageIndex", null);
        meta.put("totalRows", 1L); meta.put("completeness", "PARTIAL");
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        return Map.of("items", List.of(Map.of("eventId", "event-1", "linkId", 99L, "occurredAt", day("2026-09-01"))),
                "metrics", Map.of(), "meta", meta);
    }

    private static Map<String, Object> status(boolean released) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", JOB); data.put("state", "SUCCEEDED"); data.put("rowCount", 1L);
        data.put("pageCount", 1); data.put("expiresAt", EXPIRES);
        data.put("resultState", released ? "RELEASED" : "AVAILABLE"); data.put("resultReady", !released);
        data.put("resultCode", released ? "RESULT_RELEASED" : null);
        return data;
    }

    private static long day(String date) {
        return LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
    private static int activeCallbacks(Fixture fixture) {
        return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class);
    }
    private static void assertReadyAndExited(Fixture fixture) {
        var child = fixture.runs.child(fixture.token, CHILD).orElseThrow();
        assertEquals(ChildState.READY, child.state()); assertEquals(JOB, child.jobId()); assertEquals(ARTIFACT, child.artifactId());
        assertFalse(child.callbackActive()); assertEquals(0, activeCallbacks(fixture));
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final RunToken token;
        final Map<String, Object> request;
        final JdbcCampaignRunStore runs;
        final JdbcCampaignStatisticsResultStore results;
        final JdbcCampaignStatisticsReleaseStore releases;
        Fixture(JdbcTemplate jdbc, TransactionTemplate tx, RunToken token, Map<String, Object> request) {
            this.jdbc = jdbc; this.tx = tx; this.token = token; this.request = request;
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
            results = new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK);
            releases = new JdbcCampaignStatisticsReleaseStore(jdbc, tx, CLOCK);
        }
        Fixture reopen() { return new Fixture(jdbc, tx, token, request); }
        StatisticsJobResultReleaser releaser(Gateway gateway) { return new StatisticsJobResultReleaser(runs, releases, gateway); }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final AtomicInteger statusCalls = new AtomicInteger();
        final AtomicInteger releaseCalls = new AtomicInteger();
        final AtomicInteger otherCalls = new AtomicInteger();
        boolean remoteReleased;
        boolean loseAcknowledgement;
        Runnable duringStatus = () -> {};
        Runnable duringRelease = () -> {};
        Map<String, Object> releasedRequest;
        @Override public ToolResult readStatisticsJob(ToolContext context, String jobId) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, jobId);
            statusCalls.incrementAndGet();
            duringStatus.run();
            return ToolResult.success(status(remoteReleased));
        }
        @Override public ToolResult releaseStatisticsJobResult(ToolContext context, String jobId, Map<String, Object> original) {
            assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, jobId);
            releaseCalls.incrementAndGet(); releasedRequest = original;
            duringRelease.run(); remoteReleased = true;
            if (loseAcknowledgement) return new ToolResult(false, Map.of("code", "REMOTE_UNAVAILABLE"), "Fixture lost acknowledgement");
            return ToolResult.success(status(true));
        }
        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) { return unexpected(); }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> body) { return unexpected(); }
        @Override public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> body) { return unexpected(); }
        @Override public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> body) { return unexpected(); }
        @Override public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int index, int size) { return unexpected(); }
        private ToolResult unexpected() { otherCalls.incrementAndGet(); fail("Release must not submit, query or read remote pages"); return null; }
        void assertNoOtherCalls() { assertEquals(0, otherCalls.get()); }
    }
}
