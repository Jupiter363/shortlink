package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Ledger permits only; actual authority I/O and response-version verification remain the collector's job. */
class PinnedAuthorityPageDispatchTest {
    private static final Caller OWNER = new Caller("1", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BODY = "{\"gid\":\"group-a\",\"afterLinkId\":500,\"ownershipVersion\":\""
            + "a".repeat(64) + "\"}";

    @Test
    void pinnedUnknownPageRestartsWithTheSameWireAndReadyEvidenceIsNeverDispatchedAgain() {
        Fixture f = new Fixture();
        CampaignRunStore runs = f.runs();
        RunToken original = f.run(runs);
        ChildSpec spec = f.child(runs, original, "page-500", ChildMode.SYNC, wire("POST", BODY));
        DispatchPermit initial = runs.beginDispatch(original, spec.childId());
        try { runs.markUnresolved(initial); }
        finally { runs.callbackExited(initial); }

        CampaignRunStore reopened = f.runs();
        RunToken current = reopened.advance(original);
        assertThrows(IllegalStateException.class,
                () -> reopened.beginAuthorityPageReconciliation(original, spec.childId()));
        DispatchPermit firstRead = reopened.beginAuthorityPageReconciliation(current, spec.childId());
        try {
            assertEquals(DispatchPurpose.AUTHORITY_PAGE_READ, firstRead.purpose());
            assertTrue(firstRead.attemptVersion() > initial.attemptVersion());
            assertEquals(spec, reopened.child(current, spec.childId()).orElseThrow().spec());
            assertTrue(reopened.mayDispatch(firstRead));
            assertFalse(reopened.mayDispatch(initial));
            assertThrows(IllegalStateException.class, () -> reopened.callbackExited(initial));
            assertThrows(IllegalStateException.class, () -> reopened.advance(current));
            assertThrows(IllegalStateException.class, () -> reopened.recordWaiting(firstRead, "not-a-job"));
            assertThrows(IllegalStateException.class, () -> reopened.recordLateJob(firstRead, "not-a-job"));
            reopened.markUnresolved(firstRead);
            assertEquals(UnresolvedReason.READ_RESULT_UNKNOWN,
                    reopened.child(current, spec.childId()).orElseThrow().reason());
            assertThrows(IllegalStateException.class,
                    () -> reopened.beginAuthorityPageReconciliation(current, spec.childId()),
                    "A failed read does not prove its callback has exited");
        } finally { reopened.callbackExited(firstRead); }

        CampaignRunStore restored = f.runs();
        RunToken next = restored.advance(current);
        DispatchPermit completedRead = restored.beginAuthorityPageReconciliation(next, spec.childId());
        ArtifactDraft page = artifact();
        try {
            assertNotEquals(firstRead.attemptId(), completedRead.attemptId());
            assertTrue(completedRead.attemptVersion() > firstRead.attemptVersion());
            assertEquals(spec, restored.child(next, spec.childId()).orElseThrow().spec());
            restored.publishReady(completedRead, page);
            assertEquals(ChildState.READY, restored.child(next, spec.childId()).orElseThrow().state());
            assertFalse(restored.mayDispatch(completedRead));
            assertThrows(IllegalStateException.class, () -> restored.advance(next));
        } finally { restored.callbackExited(completedRead); }
        RunToken finished = restored.advance(next);
        assertThrows(IllegalStateException.class,
                () -> restored.beginAuthorityPageReconciliation(finished, spec.childId()));
        assertThrows(IllegalStateException.class, () -> restored.beginDispatch(finished, spec.childId()));
        assertThrows(IllegalStateException.class, () -> restored.beginReconciliation(finished, spec.childId()));
        assertEquals(page.payloadJson(), f.runs().readArtifact(OWNER, page.artifactId(), (caller, metadata) -> true).payloadJson());
        assertEquals(spec, restored.child(finished, spec.childId()).orElseThrow().spec());
    }

    @Test
    void unpinnedOrUnrelatedReadsAndCancelledAttemptsCannotObtainFreshAuthority() {
        Fixture f = new Fixture();
        CampaignRunStore runs = f.runs();
        RunToken run = f.run(runs);
        List<WireRequest> invalid = List.of(
                wire("POST", "{\"gid\":\"group-a\"}"),
                wire("GET", BODY),
                new WireRequest("POST", "/internal/statistics", BODY),
                wire("POST", BODY.replace("500", "500.5")),
                wire("POST", BODY.replace("500", "0")),
                wire("POST", BODY.replace("{", "{\"tenantId\":\"other\",")),
                wire("POST", BODY.replace("{", "{\"gid\":\"different\",")));
        for (int i = 0; i < invalid.size(); i++) {
            ChildSpec spec = f.child(runs, run, "invalid-" + i, ChildMode.SYNC, invalid.get(i));
            assertThrows(IllegalStateException.class,
                    () -> runs.beginAuthorityPageReconciliation(run, spec.childId()));
            unknown(runs, run, spec.childId());
            ChildRecord before = runs.child(run, spec.childId()).orElseThrow();
            assertThrows(IllegalStateException.class,
                    () -> runs.beginAuthorityPageReconciliation(run, spec.childId()));
            assertEquals(before, runs.child(run, spec.childId()).orElseThrow());
            assertThrows(IllegalStateException.class, () -> runs.beginDispatch(run, spec.childId()));
        }
        ChildSpec async = f.child(runs, run, "async", ChildMode.ASYNC, wire("POST", BODY));
        unknown(runs, run, async.childId());
        assertThrows(IllegalStateException.class,
                () -> runs.beginAuthorityPageReconciliation(run, async.childId()));

        ChildSpec cancelled = f.child(runs, run, "cancelled", ChildMode.SYNC, wire("POST", BODY));
        unknown(runs, run, cancelled.childId());
        DispatchPermit read = runs.beginAuthorityPageReconciliation(run, cancelled.childId());
        try {
            runs.cancel(run);
            assertFalse(runs.mayDispatch(read));
            assertThrows(IllegalStateException.class,
                    () -> runs.beginAuthorityPageReconciliation(run, cancelled.childId()));
            assertThrows(IllegalStateException.class, () -> runs.publishReady(read, artifact()));
        } finally { f.runs().callbackExited(read); }
        RunRecord stopped = runs.loadRun(OWNER, run.definition().runId()).orElseThrow();
        assertEquals(RunStatus.CANCELLED, stopped.status());
        ChildRecord retained = runs.child(stopped.token(), cancelled.childId()).orElseThrow();
        assertFalse(retained.callbackActive());
        assertNull(retained.artifactId());
        assertEquals(cancelled, retained.spec());
        assertThrows(IllegalStateException.class, () -> runs.advance(stopped.token()));
    }

    private static void unknown(CampaignRunStore runs, RunToken run, String child) {
        DispatchPermit permit = runs.beginDispatch(run, child);
        try { runs.markUnresolved(permit); }
        finally { runs.callbackExited(permit); }
    }

    private static WireRequest wire(String method, String body) {
        return new WireRequest(method, AgentAuthorityClient.GROUP_MEMBERS_PATH, body);
    }

    private static ArtifactDraft artifact() {
        return new ArtifactDraft("authority-page-500", "GroupMembersPage", "group-members-page/v1",
                "scope-a", "scope-collection", "{}", "{}", NOW.plusSeconds(3600), "{\"linkIds\":[501]}");
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transactions;

        private Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:pinned_authority_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_4__campaign_authority_page_read.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        }

        CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        RunToken run(CampaignRunStore runs) {
            return runs.createRun(new RunDefinition(OWNER, "session", "run", "plan", 1, "{}"));
        }
        ChildSpec child(CampaignRunStore runs, RunToken run, String id, ChildMode mode, WireRequest wire) {
            runs.prepareAction(run, new ActionSpec("action-" + id, "collect", "TOOL", "authority-page", "1", "{}"));
            ChildSpec spec = new ChildSpec(id, "action-" + id, mode, "request-" + id, wire);
            runs.prepareChild(run, spec);
            return spec;
        }
    }
}
