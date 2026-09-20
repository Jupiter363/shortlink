package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsReleaseStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignStatisticsReleaseStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Caller OWNER = new Caller("1001", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long EXPIRY = NOW.plusSeconds(3600).toEpochMilli();
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> true;

    @Test
    void stagingCorruptedLastPageRevokedGrantAndExpiryNeverRegisterAReleaseIntent() throws Exception {
        Fixture staging = fixture();
        Seed partial = seed(staging, "staging", 501, false);
        assertThrows(IllegalStateException.class, () -> staging.releases(CLOCK).prepare(partial.run(), partial.childId(), ALLOW));
        assertEquals(0, staging.intents());

        Fixture corrupted = fixture();
        Seed ready = seed(corrupted, "corrupted", 501, true);
        corrupted.jdbc().update("UPDATE campaign_statistics_page SET payload_json=REPLACE(payload_json,'event-500','event-999') WHERE page_index=1");
        assertThrows(IllegalStateException.class, () -> corrupted.releases(CLOCK).prepare(ready.run(), ready.childId(), ALLOW));
        assertEquals(0, corrupted.intents(), "Even a corrupt final page must prevent release of the remote copy");
        assertEquals(ChildState.READY, corrupted.runs().child(ready.run(), ready.childId()).orElseThrow().state());

        Fixture authorized = fixture();
        Seed complete = seed(authorized, "authority", 1, true);
        AtomicInteger denied = new AtomicInteger();
        assertThrows(SecurityException.class, () -> authorized.releases(CLOCK).prepare(complete.run(), complete.childId(),
                (current, artifact) -> { denied.incrementAndGet(); return false; }));
        assertEquals(1, denied.get());
        assertThrows(SecurityException.class, () -> authorized.releases(Clock.fixed(Instant.ofEpochMilli(EXPIRY), ZoneOffset.UTC))
                .prepare(complete.run(), complete.childId(), ALLOW));
        assertEquals(0, authorized.intents());
        assertTrue(authorized.results().receipt(complete.run(), complete.childId()).orElseThrow().published());
    }

    @Test
    void completePageProofSurvivesReopenIsIdempotentRejectsAnotherProducerAndPinsTheLocalCopy() throws Exception {
        Fixture fixture = fixture();
        Seed source = seed(fixture, "producer", 501, true);
        AtomicInteger checks = new AtomicInteger();
        ArtifactAuthorizer grant = (current, artifact) -> { checks.incrementAndGet(); return true; };
        Intent requested = fixture.releases(CLOCK).prepare(source.run(), source.childId(), grant);
        assertTrue(checks.get() >= 6, "Manifest and both physical pages must pass the live read grant");
        assertEquals(State.REQUESTED, requested.state());
        assertEquals(1, requested.version());
        assertEquals(source.run().definition().runId(), requested.producerRunId());
        assertEquals(source.requestHash(), requested.requestHash());
        assertEquals(source.artifactId(), requested.artifactId());
        assertEquals(EXPIRY, requested.expiresAtMillis());
        var reopened = fixture.releases(CLOCK);
        assertEquals(requested, reopened.intent(source.run(), source.childId()).orElseThrow());
        assertEquals(requested, reopened.prepare(source.run(), source.childId(), ALLOW));
        assertEquals(1, fixture.intents());
        assertEquals(source.artifactHash(), requested.artifactHash());
        assertEquals(source.chainHash(), requested.chainHash());
        assertTrue(fixture.results().readPage(OWNER, source.artifactId(), 1, ALLOW).contains("event-500"));
        assertThrows(DataIntegrityViolationException.class, () -> fixture.jdbc()
                .update("DELETE FROM campaign_artifact_payload WHERE artifact_id=?", source.artifactId()));
        assertThrows(DataIntegrityViolationException.class, () -> fixture.jdbc()
                .update("DELETE FROM campaign_artifact WHERE artifact_id=?", source.artifactId()));

        Seed second = seed(fixture, "second-producer", 1, true); // Same physical job, distinct request/producer.
        assertThrows(IllegalStateException.class, () -> reopened.prepare(second.run(), second.childId(), ALLOW));
        assertThrows(IllegalStateException.class, () -> reopened.prepare(source.run(), source.childId(), ALLOW));
        assertEquals(1, fixture.intents(), "No second remote consumer may be promised by this P2 binding");
        assertEquals(requested, reopened.intent(source.run(), source.childId()).orElseThrow());
    }

    @Test
    void confirmationUsesExactReleaseAttemptAndCanRecordLateFactsWithoutReopeningCancelledOrRevisedRuns() throws Exception {
        Fixture fixture = fixture();
        Seed source = seed(fixture, "cancelled", 0, true); // Zero rows still require a verified response page.
        var releases = fixture.releases(CLOCK);
        Intent requested = releases.prepare(source.run(), source.childId(), ALLOW);
        DispatchPermit permit = fixture.runs().beginRelease(source.run(), source.childId());
        assertTrue(releases.mayRelease(permit, requested));
        Intent changed = new Intent(requested.bindingId(), requested.producerRunId(), requested.revision(), requested.childId(),
                requested.jobId(), requested.requestId(), "0".repeat(64), requested.artifactId(), requested.artifactHash(),
                requested.chainHash(), requested.expiresAtMillis(), requested.version(), requested.state());
        assertFalse(releases.mayRelease(permit, changed));
        assertThrows(IllegalStateException.class, () -> releases.confirm(permit, changed, EXPIRY));
        assertThrows(IllegalStateException.class, () -> releases.confirm(permit, requested, EXPIRY + 1));
        assertEquals(State.REQUESTED, releases.intent(source.run(), source.childId()).orElseThrow().state());
        assertFalse(fixture.releases(Clock.fixed(Instant.ofEpochMilli(EXPIRY), ZoneOffset.UTC)).mayRelease(permit, requested));
        try {
            fixture.runs().cancel(source.run());
            assertFalse(releases.mayRelease(permit, requested));
            releases.confirm(permit, requested, EXPIRY);
            RunRecord cancelled = fixture.runs().loadRun(OWNER, source.run().definition().runId()).orElseThrow();
            assertEquals(RunStatus.CANCELLED, cancelled.status());
            assertEquals(ChildState.READY, fixture.runs().child(cancelled.token(), source.childId()).orElseThrow().state());
            assertEquals(source.artifactId(), fixture.runs().child(cancelled.token(), source.childId()).orElseThrow().artifactId());
            assertEquals(State.CONFIRMED, releases.intent(source.run(), source.childId()).orElseThrow().state());
            assertFalse(releases.mayRelease(permit, requested));
        } finally {
            fixture.runs().callbackExited(permit);
        }
        releases.confirm(permit, requested, EXPIRY); // Same immutable fact is an idempotent no-op after real exit.
        assertEquals(1, releases.intent(source.run(), source.childId()).orElseThrow().version());

        Fixture revision = fixture();
        Seed newer = seed(revision, "revised", 1, true);
        var revisionReleases = revision.releases(CLOCK);
        Intent intent = revisionReleases.prepare(newer.run(), newer.childId(), ALLOW);
        DispatchPermit oldAttempt = revision.runs().beginRelease(newer.run(), newer.childId());
        revision.runs().callbackExited(oldAttempt);
        DispatchPermit currentAttempt = revision.runs().beginRelease(newer.run(), newer.childId());
        try {
            assertFalse(revisionReleases.mayRelease(oldAttempt, intent));
            assertThrows(IllegalStateException.class, () -> revisionReleases.confirm(oldAttempt, intent, EXPIRY));
            assertTrue(revisionReleases.mayRelease(currentAttempt, intent));
            RunToken newRevision = revision.runs().revise(newer.run(), 2, "{\"revision\":2}");
            assertFalse(revisionReleases.mayRelease(currentAttempt, intent));
            revisionReleases.confirm(currentAttempt, intent, EXPIRY);
            assertEquals(newRevision, revision.runs().loadRun(OWNER, newRevision.definition().runId()).orElseThrow().token());
            assertEquals("READY", revision.jdbc().queryForObject("SELECT child_state FROM campaign_child_ledger WHERE run_id=? "
                    + "AND revision=1 AND child_id=?", String.class, newer.run().definition().runId(), newer.childId()));
        } finally {
            revision.runs().callbackExited(currentAttempt);
        }
    }

    @Test
    void pendingRequestedIsBoundedDeterministicAndReadOnlyAndExcludesConfirmedTerminalOrForeignRows() throws Exception {
        Fixture ordered = fixture();
        Seed firstSeed = seed(ordered, "a", 0, true, "job-a");
        Seed middleSeed = seed(ordered, "m", 0, true, "job-m");
        Seed lastSeed = seed(ordered, "z", 0, true, "job-z");
        var releases = ordered.releases(CLOCK);
        releases.prepare(firstSeed.run(), firstSeed.childId(), ALLOW);
        releases.prepare(middleSeed.run(), middleSeed.childId(), ALLOW);
        releases.prepare(lastSeed.run(), lastSeed.childId(), ALLOW);

        List<Map<String, Object>> before = ordered.jdbc().queryForList(
                "SELECT binding_id,release_state,binding_version,confirmed_at FROM campaign_statistics_release ORDER BY binding_id");
        List<PendingIntent> firstRead = releases.pendingRequested(OWNER, 2);
        assertEquals(List.of("run-a", "run-m"), firstRead.stream().map(PendingIntent::runId).toList());
        assertEquals(List.of("job-a", "job-m"), firstRead.stream().map(PendingIntent::jobId).toList());
        assertTrue(firstRead.stream().allMatch(entry -> entry.state() == State.REQUESTED
                && entry.bindingVersion() == 1 && entry.expiresAtMillis() == EXPIRY));
        assertEquals(firstRead, releases.pendingRequested(OWNER, 2));
        assertEquals(before, ordered.jdbc().queryForList(
                "SELECT binding_id,release_state,binding_version,confirmed_at FROM campaign_statistics_release ORDER BY binding_id"));
        assertTrue(releases.pendingRequested(new Caller("1001", "other-subject", 7), 10).isEmpty());
        assertTrue(releases.pendingRequested(new Caller("foreign-tenant", "analyst-a", 7), 10).isEmpty());

        Fixture filtered = fixture();
        Seed pending = seed(filtered, "pending", 0, true, "job-pending");
        Seed confirmed = seed(filtered, "confirmed", 0, true, "job-confirmed");
        Seed terminal = seed(filtered, "terminal", 0, true, "job-terminal");
        var filteredReleases = filtered.releases(CLOCK);
        filteredReleases.prepare(pending.run(), pending.childId(), ALLOW);
        filteredReleases.prepare(confirmed.run(), confirmed.childId(), ALLOW);
        filteredReleases.prepare(terminal.run(), terminal.childId(), ALLOW);
        filtered.jdbc().update("UPDATE campaign_statistics_release SET release_state='CONFIRMED',confirmed_at=? "
                + "WHERE producer_run_id=? AND revision=? AND child_id=?", NOW.toEpochMilli(),
                confirmed.run().definition().runId(), confirmed.run().definition().revision(), confirmed.childId());
        filtered.runs().cancel(terminal.run());
        assertEquals(List.of("run-pending"), filteredReleases.pendingRequested(OWNER, 10).stream()
                .map(PendingIntent::runId).toList());
        assertThrows(IllegalArgumentException.class, () -> filteredReleases.pendingRequested(OWNER, 0));
        assertThrows(IllegalArgumentException.class, () -> filteredReleases.pendingRequested(OWNER, 257));
    }

    @Test
    void pendingRequestedFailsClosedWhenTheStoredBindingNoLongerMatchesTheChildProof() throws Exception {
        Fixture fixture = fixture();
        Seed seed = seed(fixture, "corrupt-pending", 0, true, "job-corrupt");
        var releases = fixture.releases(CLOCK);
        releases.prepare(seed.run(), seed.childId(), ALLOW);
        fixture.jdbc().update("UPDATE campaign_statistics_release SET request_hash=? WHERE producer_run_id=? "
                        + "AND revision=? AND child_id=?", "0".repeat(64), seed.run().definition().runId(),
                seed.run().definition().revision(), seed.childId());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> releases.pendingRequested(OWNER, 10));
        assertEquals("RELEASE_PENDING_BINDING_CORRUPTED", failure.getMessage());
    }

    private static Seed seed(Fixture fixture, String suffix, int total, boolean publish) throws Exception {
        return seed(fixture, suffix, total, publish, "physical-job");
    }

    private static Seed seed(Fixture fixture, String suffix, int total, boolean publish, String jobId) throws Exception {
        var runs = fixture.runs();
        RunToken run = runs.createRun(new RunDefinition(OWNER, "session-1", "run-" + suffix, "plan-1", 1, "{}"));
        String childId = "child-" + suffix;
        String artifactId = "artifact-" + suffix;
        String scopeRef = "scope-" + suffix;
        List<Long> members = List.of(1L);
        String hash = FrozenQueryScope.memberHash(members);
        FrozenQueryScope scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", scopeRef, hash, 1,
                "a".repeat(64), FrozenQueryScope.shardIdFor(scopeRef, 0, hash), 0, 1, hash, members);
        String requestId = "request-" + suffix;
        WireRequest wire = new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH,
                JSON.writeValueAsString(Map.of("requestId", requestId, "gid", "group-1", "startDate", "2026-09-01",
                        "endDate", "2026-09-02", "queryKind", "ACCESS_RECORDS", "scope", scope.asMap())));
        runs.prepareAction(run, new ActionSpec("action-" + suffix, "step-1", "TOOL", "statistics", "1", "{}"));
        runs.prepareChild(run, new ChildSpec(childId, "action-" + suffix, ChildMode.ASYNC, requestId, wire));
        DispatchPermit submission = runs.beginDispatch(run, childId);
        try { runs.recordWaiting(submission, jobId); }
        finally { runs.callbackExited(submission); }
        DispatchPermit receipt = runs.beginReconciliation(run, childId);
        int pageCount = total / 500 + (total % 500 == 0 ? 0 : 1);
        ArtifactRef artifact = null;
        try {
            var results = fixture.results();
            results.initialize(receipt, new CampaignStatisticsResultStore.ReceiptSpec(jobId, wire.hash(), artifactId,
                    scopeRef, "periods-frozen", total, pageCount, EXPIRY));
            var protocol = new StatisticsJobResultProtocol(runs.child(run, childId).orElseThrow());
            var status = new StatisticsJobResultProtocol.Status(jobId, "SUCCEEDED", total, pageCount, EXPIRY, null);
            int received = publish ? Math.max(1, pageCount) : 1;
            for (int index = 0; index < received; index++) {
                results.append(receipt, protocol.page(status, page(scope, total, index, pageCount, jobId), index));
            }
            if (publish) artifact = results.publish(receipt);
            else runs.markUnresolved(receipt);
        } finally { runs.callbackExited(receipt); }
        return new Seed(run, childId, artifactId, wire.hash(), artifact == null ? null : artifact.payloadHash(),
                fixture.results().receipt(run, childId).orElseThrow().chainHash());
    }

    private static Map<String, Object> page(FrozenQueryScope scope, int total, int index, int pages) {
        return page(scope, total, index, pages, "physical-job");
    }

    private static Map<String, Object> page(FrozenQueryScope scope, int total, int index, int pages, String jobId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "group-1"); meta.put("linkIds", scope.linkIds());
        meta.put("snapshotId", jobId); meta.put("snapshotExpiresAt", EXPIRY);
        meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
        meta.put("sourceCut", Map.of("manifestSelectionHash", "cut-1"));
        meta.put("manifestVersion", Map.of("selectionHash", "cut-1"));
        meta.put("requestedStart", day("2026-09-01")); meta.put("requestedEnd", day("2026-09-03"));
        meta.put("effectiveEnd", day("2026-09-03")); meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("groupScopeComplete", false); meta.put("scopeProof", scope.proof("b".repeat(64)));
        meta.put("completeness", "PARTIAL"); meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        meta.put("totalRows", total); meta.put("pageIndex", index); meta.put("nextPageIndex", index + 1 < pages ? index + 1 : null);
        List<Map<String, Object>> rows = IntStream.range(index * 500, Math.min(total, (index + 1) * 500))
                .mapToObj(row -> Map.<String, Object>of("eventId", "event-" + row, "linkId", 1L)).toList();
        return Map.of("items", rows, "metrics", Map.of("pv", total), "meta", meta);
    }

    private static long day(String value) { return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    private static Fixture fixture() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:release_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql")).execute(source);
        return new Fixture(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
    private record Seed(RunToken run, String childId, String artifactId, String requestHash, String artifactHash, String chainHash) { }
    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions) {
        CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        CampaignStatisticsResultStore results() { return new JdbcCampaignStatisticsResultStore(jdbc, transactions, CLOCK); }
        CampaignStatisticsReleaseStore releases(Clock clock) { return new JdbcCampaignStatisticsReleaseStore(jdbc, transactions, clock); }
        int intents() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_release", Integer.class); }
    }
}
