package com.jupiter.shortlink.agent.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignPlanningStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignPublicRequestStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignDueWorkDispatcher;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignDueWorkStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignPublicWorkSchedulerTest {
    @Test
    void twoSchedulersWaitForTheOriginalCallbackThenResumeWithoutRepeatingInterpretation() {
        var f=new Fixture();
        var original=f.original;
        var future=new CompletableFuture<Void>();
        var attempt=new AtomicReference<String>();
        var advances=new AtomicInteger();
        CampaignDueWorkDispatcher.Submit submit=ref->{
            assertEquals(original.reference(),ref);
            if (advances.incrementAndGet()==1) {
                attempt.set(f.requests.begin(original,"a".repeat(64)));
                return future;
            }
            assertEquals("READY",f.requests.read(ref).state());
            assertFalse(f.requests.read(ref).callbackActive());
            f.requests.needsInput(f.requests.read(ref),"MISSING_SCOPE");
            return CompletableFuture.completedFuture(null);
        };
        var first=f.dispatcher(submit);
        var second=f.dispatcher(submit);
        assertEquals(1,first.tick().submitted());
        f.clock.advance(100);
        assertEquals(0,second.tick().submitted());
        var waiting=f.due.entry(original.reference());
        assertEquals(CampaignDueWorkStore.State.READY,waiting.state());
        assertEquals("CALLBACK_IN_PROGRESS",waiting.reason());
        assertEquals(f.clock.millis()+200,waiting.dueAtMillis());
        assertEquals(1,advances.get());
        assertFalse(future.isCancelled());

        f.requests.complete(original,attempt.get(),"{}");
        // Publishing the response alone does not prove the provider callback exited.
        assertEquals(CampaignDueWorkDispatcher.Decision.WAIT,f.state.inspect(original.reference()));
        f.requests.callbackExited(original,attempt.get());
        future.complete(null);
        assertEquals(1,first.tick().completed());
        assertEquals(waiting,f.due.entry(original.reference()),"The older claim cannot erase the newer backoff");
        f.clock.advance(199);
        assertEquals(0,second.tick().submitted());
        f.clock.advance(1);
        assertEquals(1,second.tick().submitted());
        assertEquals(2,advances.get());
        assertEquals(attempt.get(),f.jdbc.queryForObject("SELECT attempt_id FROM campaign_public_request WHERE request_id=?",
                String.class,original.reference().workId()),"Continuation must not replace the original model attempt");
    }

    @Test
    void unknownOutcomeRemainsBlockedAcrossTwoSchedulersAndLateOldCompletion() {
        var f=new Fixture();
        var future=new CompletableFuture<Void>();
        var attempt=new AtomicReference<String>();
        var submissions=new AtomicInteger();
        CampaignDueWorkDispatcher.Submit submit=ref->{
            assertEquals(1,submissions.incrementAndGet(),"An unknown model must never be sent again");
            attempt.set(f.requests.begin(f.original,"b".repeat(64)));
            return future;
        };
        var first=f.dispatcher(submit);
        var second=f.dispatcher(submit);
        first.tick();
        f.clock.advance(100);
        second.tick();
        f.requests.unknown(f.original,attempt.get());
        assertTrue(f.requests.read(f.original.reference()).callbackActive());
        f.clock.advance(200);
        assertEquals(0,second.tick().submitted());
        var blocked=f.due.entry(f.original.reference());
        assertEquals(CampaignDueWorkStore.State.BLOCKED,blocked.state());
        assertEquals("DURABLE_WORK_BLOCKED",blocked.reason());
        f.requests.callbackExited(f.original,attempt.get());
        future.complete(null);
        first.tick();
        assertEquals(blocked,f.due.entry(f.original.reference()),"The older completion must not overwrite a newer verdict");
        f.clock.advance(100_000);
        first.tick(); second.tick();
        assertEquals(1,submissions.get());

        // A distinct request whose DISPATCHING callback is no longer active is unresolved, not retryable.
        var detached=f.requests.register(Fixture.OWNER,"other-session","other-key","Inspect traffic",
                f.clock.instant().plusSeconds(3600));
        var detachedAttempt=f.requests.begin(detached,"c".repeat(64));
        f.requests.callbackExited(detached,detachedAttempt);
        assertEquals(CampaignDueWorkDispatcher.Decision.BLOCKED,f.state.inspect(detached.reference()));
    }

    @Test
    void publicReferenceAlsoWaitsForItsExactPlanningCallbackWithoutReplayingUnknownPlanning() {
        var f=new Fixture();
        String interpretation=f.requests.begin(f.original,"d".repeat(64));
        f.requests.complete(f.original,interpretation,"{}");
        f.requests.callbackExited(f.original,interpretation);
        var runs=new JdbcCampaignRunStore(f.jdbc,f.transactions,f.clock);
        var intake=new JdbcCampaignRunIntakeStore(f.jdbc,f.transactions,f.clock,runs,1024*1024);
        var planning=new JdbcCampaignPlanningStore(f.jdbc,f.transactions,f.clock,intake,1024*1024,
                ModelInvocationRegistry.Limits.defaults());
        var header=planning.register(Fixture.OWNER,"session","key","profile","1","scripted","1",
                "e".repeat(64),"{}",f.clock.instant().plusSeconds(3600));
        f.requests.bind(f.requests.read(f.original.reference()),new WorkRef(header.runId(),header.requestId()));
        var calls=new AtomicInteger();
        var future=new CompletableFuture<Void>();
        CampaignDueWorkDispatcher.Submit submit=ref->{
            assertEquals(1,calls.incrementAndGet());
            // Only model-boundary state is needed here; no provider or runtime is invoked.
            f.jdbc.update("UPDATE campaign_planning_request SET request_state='DISPATCHING',callback_active=TRUE,"
                    +"attempt_id=?,invocation_hash=?,invocation_json='{}' WHERE request_id=?",
                    UUID.randomUUID().toString(),"f".repeat(64),header.requestId());
            return future;
        };
        var first=f.dispatcher(submit);
        var second=f.dispatcher(submit);
        assertEquals(1,first.tick().submitted());
        f.clock.advance(100);
        assertEquals(0,second.tick().submitted());
        assertEquals("CALLBACK_IN_PROGRESS",f.due.entry(f.original.reference()).reason());
        f.jdbc.update("UPDATE campaign_planning_request SET request_state='UNKNOWN' WHERE request_id=?",header.requestId());
        f.clock.advance(200);
        assertEquals(0,second.tick().submitted());
        assertEquals(CampaignDueWorkStore.State.BLOCKED,f.due.entry(f.original.reference()).state());
        assertEquals(1,calls.get());
        assertFalse(future.isCancelled());
    }

    @Test
    void expiredPublicCallbackStopsPollingWithoutClearingItsAttemptOrSubmittingAgain() {
        var f=new Fixture();
        var future=new CompletableFuture<Void>();
        var calls=new AtomicInteger();
        CampaignDueWorkDispatcher.Submit submit=ref->{
            assertEquals(1,calls.incrementAndGet(),"Expiry must not authorize another model invocation");
            f.requests.begin(f.original,"a".repeat(64));
            return future;
        };
        var first=f.dispatcher(submit);
        var second=f.dispatcher(submit);
        assertEquals(1,first.tick().submitted());
        var invocation=f.jdbc.queryForMap("SELECT request_state,callback_active,attempt_id,prompt_hash,expires_at"
                +" FROM campaign_public_request WHERE request_id=?",f.original.reference().workId());
        f.clock.advance(f.original.expiresAt().toEpochMilli()-f.clock.millis()-1);
        assertEquals(CampaignDueWorkDispatcher.Decision.WAIT,f.state.inspect(f.original.reference()));
        f.clock.advance(1);
        assertEquals(0,second.tick().submitted());
        var blocked=f.due.entry(f.original.reference());
        assertEquals(CampaignDueWorkStore.State.BLOCKED,blocked.state());
        assertEquals("DURABLE_WORK_BLOCKED",blocked.reason());
        assertEquals(invocation,f.jdbc.queryForMap("SELECT request_state,callback_active,attempt_id,prompt_hash,expires_at"
                +" FROM campaign_public_request WHERE request_id=?",f.original.reference().workId()));
        assertTrue(f.requests.read(f.original.reference()).callbackActive());
        assertFalse(future.isCancelled());
        future.complete(null);
        first.tick();
        assertEquals(blocked,f.due.entry(f.original.reference()),"An older claim cannot overwrite the expiry verdict");
        f.clock.advance(100_000);
        assertEquals(0,second.tick().submitted());
        assertEquals(blocked,f.due.entry(f.original.reference()),"Expired work must stop polling");
        assertEquals(1,calls.get());
    }

    @Test
    void linkedPlanningCallbackUsesItsOwnExpiryAndKeepsTheOriginalInvocationForManualRecovery() {
        var f=new Fixture();
        String interpretation=f.requests.begin(f.original,"b".repeat(64));
        f.requests.complete(f.original,interpretation,"{}");
        f.requests.callbackExited(f.original,interpretation);
        var runs=new JdbcCampaignRunStore(f.jdbc,f.transactions,f.clock);
        var intake=new JdbcCampaignRunIntakeStore(f.jdbc,f.transactions,f.clock,runs,1024*1024);
        var planning=new JdbcCampaignPlanningStore(f.jdbc,f.transactions,f.clock,intake,1024*1024,
                ModelInvocationRegistry.Limits.defaults());
        Instant planningExpiry=f.clock.instant().plusSeconds(600);
        var header=planning.register(Fixture.OWNER,"session","key","profile","1","scripted","1",
                "c".repeat(64),"{}",planningExpiry);
        f.requests.bind(f.requests.read(f.original.reference()),new WorkRef(header.runId(),header.requestId()));
        var future=new CompletableFuture<Void>();
        var calls=new AtomicInteger();
        CampaignDueWorkDispatcher.Submit submit=ref->{
            assertEquals(1,calls.incrementAndGet(),"Expired planning must not be sent again");
            f.jdbc.update("UPDATE campaign_planning_request SET request_state='DISPATCHING',callback_active=TRUE,"
                    +"attempt_id=?,invocation_hash=?,invocation_json='{}' WHERE request_id=?",
                    UUID.randomUUID().toString(),"d".repeat(64),header.requestId());
            return future;
        };
        var first=f.dispatcher(submit);
        var second=f.dispatcher(submit);
        assertEquals(1,first.tick().submitted());
        var invocation=f.jdbc.queryForMap("SELECT request_state,callback_active,attempt_id,invocation_hash,invocation_json,expires_at"
                +" FROM campaign_planning_request WHERE request_id=?",header.requestId());
        f.clock.advance(planningExpiry.toEpochMilli()-f.clock.millis()-1);
        assertEquals(CampaignDueWorkDispatcher.Decision.WAIT,f.state.inspect(f.original.reference()));
        f.clock.advance(1);
        assertTrue(f.original.expiresAt().isAfter(f.clock.instant()),"The linked planning deadline must apply independently");
        assertEquals(0,second.tick().submitted());
        var blocked=f.due.entry(f.original.reference());
        assertEquals(CampaignDueWorkStore.State.BLOCKED,blocked.state());
        assertEquals("DURABLE_WORK_BLOCKED",blocked.reason());
        assertEquals(invocation,f.jdbc.queryForMap("SELECT request_state,callback_active,attempt_id,invocation_hash,invocation_json,expires_at"
                +" FROM campaign_planning_request WHERE request_id=?",header.requestId()));
        assertFalse(future.isCancelled());
        future.complete(null);
        first.tick();
        assertEquals(blocked,f.due.entry(f.original.reference()));
        f.clock.advance(100_000);
        assertEquals(0,second.tick().submitted());
        assertEquals(blocked,f.due.entry(f.original.reference()));
        assertEquals(1,calls.get());
    }

    private static final class Fixture {
        static final Caller OWNER=new Caller("1001","analyst",7);
        final MutableClock clock=new MutableClock();
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final CampaignDueWorkStore due;
        final CampaignPublicRequestStore requests;
        final CampaignPublicRequestStore.Request original;
        final CampaignDueWorkDispatcher.WorkState state;
        Fixture() {
            var source=new JdbcDataSource();
            source.setURL("jdbc:h2:mem:scheduler-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(List.of("V20260919__campaign_run_ledger.sql",
                    "V20260920_17__campaign_run_intake.sql","V20260920_18__campaign_planning_request.sql",
                    "V20260924_2__campaign_due_work.sql","V20260924_3__campaign_public_request.sql",
                    "V20260924_6__campaign_public_request_cancellation.sql")
                    .stream().map(name->new ClassPathResource("sql/migration/"+name)).toArray(ClassPathResource[]::new)).execute(source);
            jdbc=new JdbcTemplate(source);
            transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
            due=new CampaignDueWorkStore(jdbc,transactions,clock);
            requests=new CampaignPublicRequestStore(jdbc,transactions,clock);
            original=requests.register(OWNER,"session","key","Inspect traffic",clock.instant().plusSeconds(3600));
            due.schedule(original.reference());
            state=CampaignPublicWorkScheduler.schedulingState(jdbc,requests,clock);
        }
        CampaignDueWorkDispatcher dispatcher(CampaignDueWorkDispatcher.Submit submit) {
            return new CampaignDueWorkDispatcher(new CampaignDueWorkStore(jdbc,transactions,clock),submit,state,
                    new CampaignDueWorkDispatcher.Settings(1,1,100,400));
        }
    }
    private static final class MutableClock extends Clock {
        private long now=Instant.parse("2026-09-24T00:00:00Z").toEpochMilli();
        void advance(long milliseconds) { now+=milliseconds; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
