package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignDueWorkDispatcherTest {
    @Test
    void durableClaimsAreBoundedBackedOffAndCannotOverwriteAnExplicitWake() {
        var f = new Fixture();
        var first = new WorkRef("run-1", "work-1");
        var second = new WorkRef("run-2", "work-2");
        f.store.schedule(first); f.store.schedule(first); f.store.schedule(second);
        assertEquals(2, f.store.due(8).size());
        assertEquals(1, f.store.due(1).size());
        var original = f.store.entry(first);
        var claim = f.store.claim(original, 100, 400).orElseThrow();
        assertTrue(f.store.claim(original, 100, 400).isEmpty());
        assertEquals(List.of(second), f.store.due(8).stream().map(CampaignDueWorkStore.Entry::reference).toList());
        f.clock.advance(99);
        assertTrue(f.store.due(8).stream().noneMatch(value -> value.reference().equals(first)));
        f.clock.advance(1);
        assertEquals(2, f.store.due(8).size());
        var recoveredStore = new CampaignDueWorkStore(f.jdbc, f.transactions, f.clock);
        var next = recoveredStore.claim(recoveredStore.entry(first), 100, 400).orElseThrow();
        assertEquals(f.clock.millis() + 200, recoveredStore.entry(first).dueAtMillis());
        recoveredStore.finish(claim, CampaignDueWorkStore.State.DONE, null);
        assertEquals(CampaignDueWorkStore.State.READY, recoveredStore.entry(first).state());
        recoveredStore.wake(first);
        recoveredStore.finish(next, CampaignDueWorkStore.State.BLOCKED, "OLD_ATTEMPT_FAILED");
        assertEquals(CampaignDueWorkStore.State.READY, recoveredStore.entry(first).state());
        var awakened = recoveredStore.claim(recoveredStore.entry(first), 100, 400).orElseThrow();
        recoveredStore.finish(awakened, CampaignDueWorkStore.State.DONE, null);
        recoveredStore.schedule(first);
        assertEquals(CampaignDueWorkStore.State.DONE, recoveredStore.entry(first).state());
        assertThrows(IllegalArgumentException.class, () -> recoveredStore.schedule(new WorkRef("other-run", first.workId())));
    }

    @Test
    void admittedWorkRemainsDeduplicatedPastDueTimeAndUnknownModelIsNeverResubmitted() {
        var f = new Fixture();
        var reference = new WorkRef("run", "work");
        f.store.schedule(reference);
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        var dispatcher = new CampaignDueWorkDispatcher(f.store, ref -> {
            assertEquals(reference, ref);
            var future = new CompletableFuture<Void>(); accepted.add(future); return future;
        }, ref -> CampaignDueWorkDispatcher.Decision.CONTINUE,
                new CampaignDueWorkDispatcher.Settings(1, 1, 100, 400));
        assertEquals(1, dispatcher.tick().submitted());
        f.clock.advance(100_000);
        assertEquals(0, dispatcher.tick().submitted());
        assertFalse(accepted.get(0).isCancelled()); assertEquals(1, accepted.size());
        accepted.get(0).completeExceptionally(new IllegalStateException("MODEL_RESULT_UNKNOWN"));
        assertEquals(1, dispatcher.tick().completed());
        assertEquals(CampaignDueWorkStore.State.BLOCKED, f.store.entry(reference).state());
        f.clock.advance(100_000);
        assertEquals(0, dispatcher.tick().submitted()); assertEquals(1, accepted.size());
        f.store.schedule(reference);
        assertEquals(0, dispatcher.tick().submitted());
        // A new dispatcher does not infer worker death from elapsed time or reset a blocked item.
        var reopened = new CampaignDueWorkDispatcher(f.store, ref -> { fail("unknown model replay"); return null; },
                ref -> CampaignDueWorkDispatcher.Decision.CONTINUE, CampaignDueWorkDispatcher.Settings.defaults());
        assertEquals(0, reopened.tick().submitted());
    }

    @Test
    void differentWorkReferencesForOneRunCannotAdvanceConcurrently() {
        var f=new Fixture();
        var canonical=new WorkRef("same-run","a-public");
        var alias=new WorkRef("same-run","b-planning");
        var unrelated=new WorkRef("other-run","c-public");
        f.store.schedule(canonical);f.store.schedule(alias);f.store.schedule(unrelated);
        List<WorkRef> started=new ArrayList<>();
        List<CompletableFuture<Void>> futures=new ArrayList<>();
        var dispatcher=new CampaignDueWorkDispatcher(f.store,ref->{started.add(ref);var future=new CompletableFuture<Void>();futures.add(future);return future;},
                ref->ref.equals(alias)?CampaignDueWorkDispatcher.Decision.DONE:CampaignDueWorkDispatcher.Decision.CONTINUE,
                new CampaignDueWorkDispatcher.Settings(3,3,100,400));
        assertEquals(2,dispatcher.tick().submitted());
        assertEquals(List.of(canonical,unrelated),started);
        f.clock.advance(100_000);assertEquals(0,dispatcher.tick().submitted());
        assertFalse(futures.get(0).isCancelled());
        futures.get(0).complete(null);dispatcher.tick();
        assertEquals(CampaignDueWorkStore.State.DONE,f.store.entry(alias).state());
        assertFalse(started.contains(alias));
    }

    @Test
    void callbackStartingAfterInspectionDefersTheRejectedSubmissionButUnknownStillBlocks() {
        var f=new Fixture();
        var reference=new WorkRef("run-race","work-race");
        f.store.schedule(reference);
        var decision=new java.util.concurrent.atomic.AtomicReference<>(CampaignDueWorkDispatcher.Decision.CONTINUE);
        var attempts=new AtomicInteger();
        var dispatcher=new CampaignDueWorkDispatcher(f.store,ref->{
            attempts.incrementAndGet();
            decision.set(CampaignDueWorkDispatcher.Decision.WAIT);
            throw new IllegalStateException("CALLBACK_ALREADY_STARTED");
        },ref->decision.get(),new CampaignDueWorkDispatcher.Settings(1,1,100,400));
        assertEquals(0,dispatcher.tick().submitted());
        assertEquals(CampaignDueWorkStore.State.READY,f.store.entry(reference).state());
        assertEquals("CALLBACK_IN_PROGRESS",f.store.entry(reference).reason());
        assertEquals(1,attempts.get());
        decision.set(CampaignDueWorkDispatcher.Decision.BLOCKED);
        f.clock.advance(100);
        assertEquals(0,dispatcher.tick().submitted());
        assertEquals(CampaignDueWorkStore.State.BLOCKED,f.store.entry(reference).state());
        assertEquals(1,attempts.get());
    }

    @Test
    void provenCapacityRejectionRetriesAfterBackoffAndTheNextPassRechecksCurrentAuthority() {
        var f = new Fixture();
        var reference = new WorkRef("run-capacity", "work-capacity"); f.store.schedule(reference);
        var capacity = new ProcessCapacityExecutor(new ProcessCapacityExecutor.Limits(1, 1, 1, 0),
                ignored -> {}, ignored -> {});
        // Closing guarantees the public admission boundary rejects before invoking the worker.
        capacity.close();
        AtomicInteger checks = new AtomicInteger();
        var dispatcher = new CampaignDueWorkDispatcher(f.store, ref -> {
            if (checks.incrementAndGet() == 1) return capacity.submit(ref, new ProcessCapacityExecutor.Demand(1, 1, 1));
            throw new SecurityException("CURRENT_AUTH_REVOKED");
        }, ref -> CampaignDueWorkDispatcher.Decision.CONTINUE,
                new CampaignDueWorkDispatcher.Settings(1, 1, 100, 400));
        assertEquals(0, dispatcher.tick().submitted());
        assertEquals(CampaignDueWorkStore.State.READY, f.store.entry(reference).state());
        assertEquals(1, checks.get());
        f.clock.advance(99); dispatcher.tick(); assertEquals(1, checks.get());
        f.clock.advance(1); dispatcher.tick(); assertEquals(2, checks.get());
        assertEquals(CampaignDueWorkStore.State.BLOCKED, f.store.entry(reference).state());
        assertEquals("ACCESS_DENIED", f.store.entry(reference).reason());
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignDueWorkStore store;
        Fixture() {
            var data = new JdbcDataSource();
            data.setURL("jdbc:h2:mem:due-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260924_2__campaign_due_work.sql")).execute(data);
            jdbc = new JdbcTemplate(data); transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
            store = new CampaignDueWorkStore(jdbc, transactions, clock);
        }
    }
    private static final class MutableClock extends Clock {
        private long now = Instant.parse("2026-09-23T00:00:00Z").toEpochMilli();
        void advance(long milliseconds) { now += milliseconds; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
