package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore.Definition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore.State;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignScopeCollector.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignScopeCollectorTest {
    private static final Caller OWNER = new Caller("1", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1", "analyst", 7, false);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String VERSION = "a".repeat(64);
    private static final Definition DEFINITION = new Definition("membership-a",
            new ActionSpec("collect-a", "scope-step", "TOOL", "group-members-page", "1", "{}"),
            "group-a", NOW.plusSeconds(3600));

    @Test
    void reopenedCollectionSkipsDurablePagesAndBothPeriodsReadTheSameBoundedShards() throws Exception {
        Fixture f = new Fixture();
        CampaignRunStore runs = f.runs();
        RunToken first = f.run(runs);
        var scopes = f.scopes(runs);
        List<Long> cursors = new ArrayList<>();
        PageReader reader = (principal, gid, after, version) -> {
            cursors.add(after);
            assertEquals(PRINCIPAL, principal);
            assertEquals(after == null ? null : VERSION, version);
            return page(after, VERSION, 501);
        };
        var partial = new CampaignScopeCollector(runs, scopes, reader, 1).collect(first, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.PROGRESS, partial.outcome());
        assertEquals(1, partial.collection().pageCount());
        assertEquals(500, partial.collection().memberCount());
        assertNull(partial.collection().artifactId());
        Artifact firstArtifact = runs.readArtifact(OWNER, runs.children(first).get(0).artifactId(), (c, m) -> true);
        assertFalse(firstArtifact.payloadJson().contains("linkIds"));

        CampaignRunStore reopened = f.runs();
        RunToken current = reopened.advance(first);
        var stored = f.scopes(reopened);
        var done = new CampaignScopeCollector(reopened, stored, reader, 1).collect(current, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.READY, done.outcome());
        assertEquals(2, done.collection().pageCount());
        assertEquals(501, done.collection().memberCount());
        assertEquals(java.util.Arrays.asList(null, 500L), cursors);
        Artifact artifact = reopened.readArtifact(OWNER, done.collection().artifactId(), (c, m) -> true);
        var manifest = new ObjectMapper().readTree(artifact.payloadJson());
        assertEquals("campaign-scope/v1", manifest.path("schemaVersion").asText());
        assertEquals(2, manifest.path("shardCount").asInt());
        assertFalse(artifact.payloadJson().contains("linkIds"));
        assertTrue(artifact.payloadJson().length() < 2048);
        assertEquals(2, reopened.children(current).size());
        assertTrue(reopened.children(current).stream().allMatch(c -> c.state() == ChildState.READY && !c.callbackActive()));
        assertEquals(artifact.metadata().childId(), reopened.children(current).stream()
                .filter(c -> c.artifactId().equals(artifact.metadata().ref().artifactId())).findFirst().orElseThrow().spec().childId());
        List<FrozenQueryScope> previousPeriod = List.of(stored.shard(OWNER, done.collection().artifactId(), 0, (c, m) -> true),
                stored.shard(OWNER, done.collection().artifactId(), 1, (c, m) -> true));
        var nextStore = f.scopes(f.runs());
        List<FrozenQueryScope> currentPeriod = List.of(nextStore.shard(OWNER, done.collection().artifactId(), 0, (c, m) -> true),
                nextStore.shard(OWNER, done.collection().artifactId(), 1, (c, m) -> true));
        assertEquals(previousPeriod, currentPeriod);
        assertEquals(500, currentPeriod.get(0).linkIds().size());
        assertEquals(List.of(501L), currentPeriod.get(1).linkIds());
        assertEquals(FrozenQueryScope.memberHash(LongStream.rangeClosed(1, 501).boxed().toList()), currentPeriod.get(0).parentMemberHash());
        assertEquals(artifact.metadata().ref().scopeRef(), currentPeriod.get(1).parentScopeRef());
        assertThrows(SecurityException.class, () -> stored.shard(OWNER, done.collection().artifactId(), 0, (c, m) -> false));
        assertThrows(RuntimeException.class, () -> stored.shard(new Caller("2", "analyst", 7), done.collection().artifactId(), 0, (c, m) -> true));
        assertEquals(Outcome.READY, new CampaignScopeCollector(reopened, stored, reader, 1)
                .collect(current, DEFINITION, PRINCIPAL, () -> true).outcome());
        assertEquals(2, cursors.size());
        assertEquals(firstArtifact, reopened.readArtifact(OWNER, firstArtifact.metadata().ref().artifactId(), (c, m) -> true));
    }

    @Test
    void firstUnknownNeverRereadsWhilePinnedUnknownResumesAndVersionChangesInvalidateTheWholeCollection() {
        Fixture firstFixture = new Fixture();
        var firstRuns = firstFixture.runs();
        var firstToken = firstFixture.run(firstRuns);
        AtomicInteger firstReads = new AtomicInteger();
        var unknownFirst = new CampaignScopeCollector(firstRuns, firstFixture.scopes(firstRuns), (p, g, a, v) -> {
            firstReads.incrementAndGet(); throw new IllegalStateException("REMOTE_UNAVAILABLE");
        }, 1);
        assertEquals(Outcome.BLOCKED, unknownFirst.collect(firstToken, DEFINITION, PRINCIPAL, () -> true).outcome());
        assertEquals("READ_RESULT_UNKNOWN", unknownFirst.collect(firstToken, DEFINITION, PRINCIPAL, () -> true).code());
        assertEquals(1, firstReads.get());

        Fixture f = new Fixture();
        var runs = f.runs();
        var token = f.run(runs);
        var scopes = f.scopes(runs);
        AtomicInteger reads = new AtomicInteger();
        PageReader reader = (p, g, a, v) -> {
            if (reads.incrementAndGet() == 2) throw new IllegalStateException("REMOTE_UNAVAILABLE");
            return page(a, VERSION, 501);
        };
        var collector = new CampaignScopeCollector(runs, scopes, reader, 2);
        assertEquals(Outcome.BLOCKED, collector.collect(token, DEFINITION, PRINCIPAL, () -> true).outcome());
        ChildRecord unknown = runs.children(token).stream().filter(c -> c.state() == ChildState.UNRESOLVED).findFirst().orElseThrow();
        var resumedRuns = f.runs();
        var resumed = resumedRuns.advance(token);
        var result = new CampaignScopeCollector(resumedRuns, f.scopes(resumedRuns), reader, 1)
                .collect(resumed, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.READY, result.outcome());
        var recovered = resumedRuns.child(resumed, unknown.spec().childId()).orElseThrow();
        assertEquals(unknown.spec(), recovered.spec());
        assertEquals(DispatchPurpose.AUTHORITY_PAGE_READ, recovered.purpose());
        assertEquals(3, reads.get());

        Fixture changed = new Fixture();
        var changedRuns = changed.runs();
        var changedToken = changed.run(changedRuns);
        var changedScopes = changed.scopes(changedRuns);
        AtomicInteger changedReads = new AtomicInteger();
        var changedCollector = new CampaignScopeCollector(changedRuns, changedScopes, (p, g, a, v) -> {
            changedReads.incrementAndGet(); return page(a, a == null ? VERSION : "b".repeat(64), 501);
        }, 2);
        var invalid = changedCollector.collect(changedToken, DEFINITION, PRINCIPAL, () -> true);
        assertEquals("QUERY_SCOPE_CHANGED", invalid.code());
        assertEquals(State.INVALID, changedScopes.load(changedToken, DEFINITION.collectionId()).state());
        assertNull(invalid.collection().artifactId());
        assertEquals(1, invalid.collection().pageCount());
        assertEquals(Outcome.BLOCKED, changedCollector.collect(changedToken, DEFINITION, PRINCIPAL, () -> true).outcome());
        assertEquals(2, changedReads.get());
    }

    @Test
    void terminalPublicationRollsBackAsAUnitAndCancellationRevocationAndEmptyGroupsKeepTheirMeaning() {
        Fixture f = new Fixture();
        var runs = f.runs();
        var token = f.run(runs);
        PageReader reader = (p, g, a, v) -> page(a, VERSION, 501);
        new CampaignScopeCollector(runs, f.scopes(runs), reader, 1).collect(token, DEFINITION, PRINCIPAL, () -> true);
        var limited = new JdbcCampaignRunStore(f.jdbc, f.transactions, CLOCK, new Limits(1024 * 1024, 1024 * 1024, 1));
        var failed = new CampaignScopeCollector(limited, f.scopes(limited), reader, 1)
                .collect(token, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.BLOCKED, failed.outcome());
        var prefix = f.scopes(runs).load(token, DEFINITION.collectionId());
        assertEquals(State.COLLECTING, prefix.state());
        assertEquals(1, prefix.pageCount());
        assertEquals(500, prefix.memberCount());
        assertNull(prefix.artifactId());
        assertEquals(1, runs.children(token).stream().filter(c -> c.state() == ChildState.READY).count());
        assertTrue(runs.children(token).stream().noneMatch(ChildRecord::callbackActive));
        assertEquals(Outcome.READY, new CampaignScopeCollector(runs, f.scopes(runs), reader, 1)
                .collect(token, DEFINITION, PRINCIPAL, () -> true).outcome());

        Fixture revoked = new Fixture();
        var revokedRuns = revoked.runs();
        var revokedToken = revoked.run(revokedRuns);
        AtomicBoolean authorized = new AtomicBoolean(true);
        var stopped = new CampaignScopeCollector(revokedRuns, revoked.scopes(revokedRuns), (p, g, a, v) -> {
            authorized.set(false); return page(a, VERSION, 0);
        }, 1).collect(revokedToken, DEFINITION, PRINCIPAL, authorized::get);
        assertEquals(Outcome.STOPPED, stopped.outcome());
        assertTrue(revokedRuns.children(revokedToken).stream().allMatch(c -> c.artifactId() == null && !c.callbackActive()));

        Fixture cancelled = new Fixture();
        var cancelledRuns = cancelled.runs();
        var cancelledToken = cancelled.run(cancelledRuns);
        var cancelledResult = new CampaignScopeCollector(cancelledRuns, cancelled.scopes(cancelledRuns), (p, g, a, v) -> {
            cancelledRuns.cancel(cancelledToken); return page(a, VERSION, 0);
        }, 1).collect(cancelledToken, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.STOPPED, cancelledResult.outcome());
        var dead = cancelledRuns.loadRun(OWNER, cancelledToken.definition().runId()).orElseThrow();
        assertEquals(RunStatus.CANCELLED, dead.status());
        assertTrue(cancelledRuns.children(dead.token()).stream().allMatch(c -> c.artifactId() == null && !c.callbackActive()));

        Fixture late = new Fixture();
        var actualLateRuns = late.runs();
        var lateRuns = mock(CampaignRunStore.class, delegatesTo(actualLateRuns));
        var lateToken = late.run(lateRuns);
        doAnswer(invocation -> {
            actualLateRuns.callbackExited(invocation.getArgument(0));
            actualLateRuns.cancel(lateToken);
            return null;
        }).when(lateRuns).callbackExited(any());
        var lateResult = new CampaignScopeCollector(lateRuns, late.scopes(actualLateRuns), (p, g, a, v) -> page(a, VERSION, 0), 1)
                .collect(lateToken, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.STOPPED, lateResult.outcome());
        assertEquals(State.PUBLISHED, lateResult.collection().state(), "Late cancellation preserves the committed fact");
        assertNotNull(lateRuns.readArtifact(OWNER, lateResult.collection().artifactId(), (c, m) -> true));

        Fixture empty = new Fixture();
        var emptyRuns = empty.runs();
        var emptyToken = empty.run(emptyRuns);
        var emptyScopes = empty.scopes(emptyRuns);
        var emptyCollector = new CampaignScopeCollector(emptyRuns, emptyScopes, (p, g, a, v) -> page(a, VERSION, 0), 1);
        assertThrows(IllegalStateException.class, () -> empty.transactions.execute(status ->
                emptyCollector.collect(emptyToken, DEFINITION, PRINCIPAL, () -> true)));
        assertTrue(emptyRuns.children(emptyToken).isEmpty());
        var emptyResult = emptyCollector.collect(emptyToken, DEFINITION, PRINCIPAL, () -> true);
        assertEquals(Outcome.READY, emptyResult.outcome());
        assertEquals(0, emptyResult.collection().memberCount());
        assertEquals(1, emptyResult.collection().pageCount());
        assertTrue(emptyRuns.readArtifact(OWNER, emptyResult.collection().artifactId(), (c, m) -> true).payloadJson().contains("\"shardCount\":0"));
        assertThrows(IllegalArgumentException.class, () -> emptyScopes.shard(OWNER, emptyResult.collection().artifactId(), 0, (c, m) -> true));
        assertEquals(1, emptyRuns.children(emptyToken).size());
        assertEquals(ChildMode.SYNC, emptyRuns.children(emptyToken).get(0).spec().mode());
    }

    private static GroupMembersPage page(Long after, String version, int total) {
        long start = after == null ? 1 : after + 1;
        long end = Math.min(total, start + 499);
        List<Long> members = LongStream.rangeClosed(start, end).boxed().toList();
        return new GroupMembersPage(GroupMembersPage.SCHEMA, "1", "analyst", 7, "group-a", version,
                after, members, end < total ? end : null);
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transactions;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:scope_collection_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_4__campaign_authority_page_read.sql"),
                    new ClassPathResource("sql/migration/V20260920_5__campaign_scope_collection.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        }
        CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        CampaignScopeStore scopes(CampaignRunStore runs) { return new JdbcCampaignScopeStore(jdbc, transactions, CLOCK, runs); }
        RunToken run(CampaignRunStore runs) { return runs.createRun(new RunDefinition(OWNER, "session", "run", "plan", 1, "{}")); }
    }
}
