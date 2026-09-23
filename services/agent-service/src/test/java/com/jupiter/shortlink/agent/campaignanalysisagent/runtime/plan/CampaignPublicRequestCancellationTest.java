package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real short transactions and durable provider slots, without invoking any model or service. */
@Timeout(20)
class CampaignPublicRequestCancellationTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final String SESSION = "cancel-session", KEY = "cancel-key", CONFIG = "a".repeat(64);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant EXPIRY = CLOCK.instant().plusSeconds(3600);

    @Test
    void cancellationFencesRawAndPlanningCallbacksWithoutPretendingTheyExited() {
        var raw = new Fixture();
        String attempt = raw.requests.begin(raw.original, CONFIG);
        var cancelled = raw.cancel();
        assertTrue(cancelled.callbackActive());
        assertEquals("DISPATCHING", cancelled.state());
        assertEquals(cancelled, raw.cancel());
        assertEquals(cancelled, raw.requests.register(OWNER, SESSION, KEY, "Inspect traffic", EXPIRY));
        assertCancelled(() -> raw.requests.begin(raw.original, CONFIG));
        assertCancelled(() -> raw.requests.complete(raw.original, attempt, "{}"));
        raw.requests.unknown(raw.original, attempt);
        assertTrue(raw.requests.read(raw.original.reference()).callbackActive());
        raw.requests.callbackExited(raw.original, attempt);
        assertFalse(raw.requests.read(raw.original.reference()).callbackActive());
        assertTrue(raw.requests.read(raw.original.reference()).cancelled());
        assertEquals(0, raw.count("campaign_run_ledger"));

        // Cover every pre-Run planning commit boundary with the real approved invocation envelope.
        for (String stage : List.of("PREPARED", "DISPATCHING", "READY", "ACCEPTED")) {
            var f = new Fixture();
            var header = f.registerPlanning();
            var approval = f.approval(header);
            JdbcCampaignPlanningStore.Permit permit = null;
            JdbcCampaignRunIntakeStore.Header typed = null;
            if (!stage.equals("PREPARED")) permit = f.planning.begin(header, approval);
            if (Set.of("READY", "ACCEPTED").contains(stage)) {
                f.planning.publish(permit, approval, new ModelInvocationRegistry.Response("{}", List.of()));
                f.planning.callbackExited(permit);
            }
            var ready = f.planning.header(new WorkRef(header.runId(), header.requestId()));
            if (stage.equals("ACCEPTED")) typed = f.planning.accept(ready, f.definition);
            f.cancel();
            assertCancelled(f::registerPlanning);
            assertCancelled(() -> f.planning.begin(header, approval));
            if (stage.equals("DISPATCHING")) {
                var issued = permit;
                assertTrue(f.planning.header(new WorkRef(header.runId(), header.requestId())).callbackActive());
                assertCancelled(() -> f.planning.mayDispatch(issued));
                assertCancelled(() -> f.planning.publish(issued, approval, new ModelInvocationRegistry.Response("{}", List.of())));
                f.planning.unknown(issued);
                assertTrue(f.planning.header(new WorkRef(header.runId(), header.requestId())).callbackActive());
                f.planning.callbackExited(issued);
                assertFalse(f.planning.header(new WorkRef(header.runId(), header.requestId())).callbackActive());
            }
            if (stage.equals("READY")) assertCancelled(() -> f.planning.accept(ready, f.definition));
            if (typed != null) {
                var accepted = typed;
                assertCancelled(() -> f.intake.freeze(accepted, f.definition));
            }
            assertEquals(0, f.count("campaign_run_ledger"), stage);
        }
    }

    @Test
    void cancellationAndInitialFreezeSerializeInBothCommitOrdersAndRevokeLatestRevision() throws Exception {
        for (boolean cancelFirst : List.of(true, false)) {
            var f = new Fixture();
            var accepted = f.intake.register(OWNER, SESSION, KEY, "profile", "1", f.definition);
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var contenderStarted = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = workers.submit(() -> f.tx.executeWithoutResult(status -> {
                    if (cancelFirst) f.cancel(); else f.intake.freeze(accepted, f.definition);
                    locked.countDown();
                    await(release);
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                Future<?> second = workers.submit(() -> {
                    contenderStarted.countDown();
                    if (cancelFirst) assertCancelled(() -> f.intake.freeze(accepted, f.definition));
                    else f.cancel();
                });
                assertTrue(contenderStarted.await(5, TimeUnit.SECONDS));
                release.countDown();
                first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
                assertTrue(f.requests.read(f.original.reference()).cancelled());
                var run = f.runs.loadRun(OWNER, f.definition.runId());
                if (cancelFirst) assertTrue(run.isEmpty());
                else assertEquals(RunStatus.CANCELLED, run.orElseThrow().status());
                assertCancelled(() -> f.intake.freeze(accepted, f.definition));
            } finally { release.countDown(); workers.shutdownNow(); }
        }

        var f = new Fixture();
        var accepted = f.intake.register(OWNER, SESSION, KEY, "profile", "1", f.definition);
        f.intake.freeze(accepted, f.definition);
        var first = f.runs.loadRun(OWNER, f.definition.runId()).orElseThrow().token();
        var nextDefinition = new RunDefinition(OWNER, SESSION, f.definition.runId(), f.definition.planId(), 2, "{\"revision\":2}");
        f.runs.revise(first, nextDefinition.revision(), nextDefinition.definitionJson());
        f.cancel();
        var latest = f.runs.loadRun(OWNER, f.definition.runId()).orElseThrow();
        assertEquals(2, latest.definition().revision()); assertEquals(RunStatus.CANCELLED, latest.status());
        assertThrows(SecurityException.class, () -> f.requests.cancel(new Caller("1001", "other", 7), SESSION,
                f.original.reference(), f.runs));
    }

    private static void assertCancelled(org.junit.jupiter.api.function.Executable action) {
        assertEquals("CAMPAIGN_REQUEST_CANCELLED", assertThrows(IllegalStateException.class, action).getMessage());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Commit barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final JdbcCampaignRunStore runs;
        final JdbcCampaignRunIntakeStore intake;
        final JdbcCampaignPlanningStore planning;
        final CampaignPublicRequestStore requests;
        final CampaignPublicRequestStore.Request original;
        final RunDefinition definition;
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:public_cancel_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            new ResourceDatabasePopulator(List.of("V20260919__campaign_run_ledger.sql",
                    "V20260920_17__campaign_run_intake.sql", "V20260920_18__campaign_planning_request.sql",
                    "V20260924_3__campaign_public_request.sql", "V20260924_6__campaign_public_request_cancellation.sql")
                    .stream().map(name -> new ClassPathResource("sql/migration/" + name)).toArray(ClassPathResource[]::new)).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
            intake = new JdbcCampaignRunIntakeStore(jdbc, tx, CLOCK, runs, 1024 * 1024);
            requests = new CampaignPublicRequestStore(jdbc, tx, CLOCK);
            intake.installCommitGuard(requests::lockUncancelledForCommit);
            planning = new JdbcCampaignPlanningStore(jdbc, tx, CLOCK, intake, 1024 * 1024, ModelInvocationRegistry.Limits.defaults());
            original = requests.register(OWNER, SESSION, KEY, "Inspect traffic", EXPIRY);
            var identity = JdbcCampaignRunIntakeStore.identity(OWNER, SESSION, KEY);
            definition = new RunDefinition(OWNER, SESSION, identity.runId(), identity.planId(), 1, "{}");
        }
        CampaignPublicRequestStore.Request cancel() { return requests.cancel(OWNER, SESSION, original.reference(), runs); }
        JdbcCampaignPlanningStore.Header registerPlanning() {
            return planning.register(OWNER, SESSION, KEY, "profile", "1", "scripted", "1", CONFIG, "{}", EXPIRY);
        }
        ModelInvocationRegistry.Approval approval(JdbcCampaignPlanningStore.Header header) {
            var models = new ModelInvocationRegistry(List.of(new ModelInvocationRegistry.Contract("scripted", "1", CONFIG, invocation -> true)));
            var request = new ModelInvocationRegistry.Request(ModelInvocationRegistry.REQUEST_SCHEMA,
                    List.of(new ModelInvocationRegistry.Message("user", "Inspect traffic", null, null, null)), List.of());
            return models.approve(new ModelInvocationRegistry.InvocationSpec(header.requestId(), 1, "scripted", "1", CONFIG,
                    "planning", "1", "inputs", ModelInvocationRegistry.encodeRequest(request), Map.of(), EXPIRY));
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    }
}
