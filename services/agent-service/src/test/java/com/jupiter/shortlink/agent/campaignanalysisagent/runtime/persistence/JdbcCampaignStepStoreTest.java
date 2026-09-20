package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JdbcCampaignStepStoreTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> true;

    @Test
    void reopeningRetainsFrozenStepSpecificationAndAuthorizedOutputReferences() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        String artifactId = seedArtifact(runs, OWNER, "seed-reopen", NOW.plusSeconds(86400));
        RunToken run = createRun(runs, OWNER, "run-reopen");
        CampaignStepStore store = fixture.steps();
        StepSpec spec = outputStep("summarize");
        store.initialize(run, List.of(spec));
        assertDoesNotThrow(() -> store.initialize(run, List.of(spec)));
        StepPermit permit = store.beginStep(run, spec.stepId());
        fixture.dataSource().forbidPayloadReads = true;
        try {
            store.settle(permit, StepStatus.SUCCEEDED, Map.of("summary", artifactId), null, (current, metadata) -> {
                assertEquals(OWNER, current);
                assertEquals(OWNER, metadata.owner());
                assertEquals("seed-reopen", metadata.runId(), "Explicit same-owner reuse may cross runs");
                return true;
            });
        } finally {
            store.callbackExited(permit);
        }
        StepRecord saved = store.step(run, spec.stepId()).orElseThrow();
        CampaignStepStore reopened = fixture.steps();
        assertEquals(spec, saved.spec());
        assertEquals(StepStatus.SUCCEEDED, saved.status());
        assertEquals(Map.of("summary", artifactId), saved.outputs());
        assertFalse(saved.callbackActive());
        assertEquals(saved, reopened.step(run, spec.stepId()).orElseThrow());
        assertEquals(List.of(saved), reopened.steps(run));
        assertDoesNotThrow(() -> reopened.initialize(run, List.of(spec)));
        StepSpec changed = new StepSpec(spec.stepId(), "{\"executorVersion\":\"summary/2\"}",
                spec.dependsOn(), spec.allowedOutputs(), spec.requiredOutputs());
        assertThrows(IllegalStateException.class, () -> reopened.initialize(run, List.of(changed)));
        assertEquals(saved, fixture.steps().step(run, spec.stepId()).orElseThrow());
        assertEquals(0, fixture.dataSource().payloadReads.get());
    }

    @Test
    void competingAcquisitionsHaveOneWinnerAndAnActiveStepCannotBeBegunTwice() throws Exception {
        Fixture fixture = fixture();
        RunToken original = createRun(fixture.runs(), OWNER, "run-cas");
        CampaignStepStore first = fixture.steps();
        CampaignStepStore second = fixture.steps();
        first.initialize(original, List.of(simpleStep("one"), simpleStep("two")));
        CountDownLatch contendersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AcquireOutcome> left = workers.submit(() -> acquireAfter(first, original, contendersReady, start));
            Future<AcquireOutcome> right = workers.submit(() -> acquireAfter(second, original, contendersReady, start));
            assertTrue(contendersReady.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AcquireOutcome> outcomes = List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter(result -> result.token() != null).count());
            assertEquals(1L, outcomes.stream().filter(result -> result.failure() != null).count());
            RunToken winner = outcomes.stream().map(AcquireOutcome::token).filter(token -> token != null)
                    .findFirst().orElseThrow();
            assertEquals(original.version() + 1, winner.version());
            assertNotEquals(original.advanceToken(), winner.advanceToken());
            assertThrows(IllegalStateException.class, () -> first.beginStep(original, "one"));
            StepPermit active = second.beginStep(winner, "one");
            try {
                assertTrue(second.mayExecute(active));
                assertFalse(first.mayAdvance(winner));
                assertThrows(IllegalStateException.class, () -> first.acquireRun(winner));
                assertThrows(IllegalStateException.class, () -> first.beginStep(winner, "one"));
                assertThrows(IllegalStateException.class, () -> first.beginStep(winner, "two"));
                assertEquals(active.attemptId(), first.step(winner, "one").orElseThrow().attemptId());
                assertTrue(first.step(winner, "one").orElseThrow().callbackActive());
            } finally {
                second.callbackExited(active);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void dependenciesAndRegisteredChildrenMustCompleteBeforeSuccessCanUnlockTheNextStep() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        RunToken run = createRun(runs, OWNER, "run-dependencies");
        CampaignStepStore steps = fixture.steps();
        steps.initialize(run, List.of(simpleStep("collect"), simpleStep("summarize", "collect")));
        assertEquals(StepStatus.PENDING, steps.step(run, "summarize").orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> steps.beginStep(run, "summarize"));
        prepareChild(runs, run, "collect", "source", ChildMode.SYNC);
        StepPermit collect = steps.beginStep(run, "collect");
        try {
            assertThrows(IllegalStateException.class,
                    () -> steps.settle(collect, StepStatus.SUCCEEDED, Map.of(), null, ALLOW));
            assertEquals(StepStatus.RUNNING, steps.step(run, "collect").orElseThrow().status());
            DispatchPermit child = runs.beginDispatch(run, "source");
            try {
                assertTrue(runs.mayDispatch(child), "The active step may dispatch its own registered child");
                runs.publishReady(child, artifact("artifact-source", NOW.plusSeconds(3600)));
            } finally {
                runs.callbackExited(child);
            }
            steps.settle(collect, StepStatus.SUCCEEDED, Map.of(), null, ALLOW);
            assertThrows(IllegalStateException.class, () -> steps.beginStep(run, "summarize"));
        } finally {
            steps.callbackExited(collect);
        }
        StepPermit summarize = steps.beginStep(run, "summarize");
        try {
            assertTrue(steps.mayExecute(summarize));
            assertEquals(StepStatus.RUNNING, steps.step(run, "summarize").orElseThrow().status());
            steps.settle(summarize, StepStatus.SUCCEEDED, Map.of(), null, ALLOW);
        } finally {
            steps.callbackExited(summarize);
        }
        assertEquals(StepStatus.SUCCEEDED, fixture.steps().step(run, "summarize").orElseThrow().status());
    }

    @Test
    void readyChildMustActuallyExitBeforeStepSuccessCanPublishOutputs() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        RunToken run = createRun(runs, OWNER, "run-ready-callback");
        CampaignStepStore steps = fixture.steps();
        steps.initialize(run, List.of(outputStep("collect"), simpleStep("consume", "collect")));
        prepareChild(runs, run, "collect", "source", ChildMode.SYNC);
        StepPermit step = steps.beginStep(run, "collect");
        String artifactId = "artifact-ready-active";
        try {
            DispatchPermit child = runs.beginDispatch(run, "source");
            try {
                runs.publishReady(child, artifact(artifactId, NOW.plusSeconds(3600)));
                assertEquals(ChildState.READY, runs.child(run, "source").orElseThrow().state());
                assertTrue(runs.child(run, "source").orElseThrow().callbackActive());
                StepRecord unchanged = steps.step(run, "collect").orElseThrow();
                fixture.dataSource().forbidPayloadReads = true;
                IllegalStateException blocked = assertThrows(IllegalStateException.class,
                        () -> steps.settle(step, StepStatus.SUCCEEDED, Map.of("summary", artifactId), null, ALLOW));
                assertEquals("CALLBACK_STILL_ACTIVE", blocked.getMessage());
                assertEquals(unchanged, fixture.steps().step(run, "collect").orElseThrow());
                assertTrue(steps.step(run, "collect").orElseThrow().outputs().isEmpty());
                assertTrue(runs.child(run, "source").orElseThrow().callbackActive(),
                        "Rejected settlement cannot substitute for the child delegate's finally");
                assertThrows(IllegalStateException.class, () -> steps.beginStep(run, "consume"));
            } finally {
                runs.callbackExited(child);
            }
            assertFalse(runs.child(run, "source").orElseThrow().callbackActive());
            StepRecord settled = steps.settle(step, StepStatus.SUCCEEDED, Map.of("summary", artifactId), null, ALLOW);
            assertEquals(StepStatus.SUCCEEDED, settled.status());
            assertEquals(Map.of("summary", artifactId), settled.outputs());
            assertTrue(settled.callbackActive(), "The settling Step must still exit its own real callback");
            assertThrows(IllegalStateException.class, () -> steps.beginStep(run, "consume"));
            assertEquals(0, fixture.dataSource().payloadReads.get());
        } finally {
            steps.callbackExited(step);
        }
        StepPermit consumer = steps.beginStep(run, "consume");
        try {
            assertTrue(steps.mayExecute(consumer));
        } finally {
            steps.callbackExited(consumer);
        }
    }

    @Test
    void outputValidationIsAtomicAndUsesAuthorizedUnexpiredMetadataWithoutReadingPayloads() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        String good = seedArtifact(runs, OWNER, "seed-good", NOW.plusSeconds(86400));
        String expired = seedArtifact(runs, OWNER, "seed-expired", NOW.plusSeconds(3600));
        List<String> foreignArtifacts = new ArrayList<>();
        List<Caller> foreignOwners = List.of(new Caller("tenant-b", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "analyst-b", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1));
        for (int i = 0; i < foreignOwners.size(); i++) {
            foreignArtifacts.add(seedArtifact(runs, foreignOwners.get(i), "seed-foreign-" + i, NOW.plusSeconds(86400)));
        }
        RunToken run = createRun(runs, OWNER, "run-outputs");
        Clock atExpiry = Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC);
        CampaignStepStore steps = fixture.steps(atExpiry);
        steps.initialize(run, List.of(outputStep("summarize")));
        StepPermit permit = steps.beginStep(run, "summarize");
        StepRecord unchanged = steps.step(run, "summarize").orElseThrow();
        fixture.dataSource().forbidPayloadReads = true;
        try {
            assertThrows(IllegalStateException.class,
                    () -> steps.settle(permit, StepStatus.SUCCEEDED, Map.of(), null, ALLOW));
            assertEquals(unchanged, fixture.steps(atExpiry).step(run, "summarize").orElseThrow());
            assertThrows(IllegalStateException.class, () -> steps.settle(permit, StepStatus.SUCCEEDED,
                    Map.of("summary", good, "undeclared", good), null, ALLOW));
            assertEquals(unchanged, fixture.steps(atExpiry).step(run, "summarize").orElseThrow());
            for (String foreign : foreignArtifacts) {
                Map<String, String> outputs = new LinkedHashMap<>();
                outputs.put("summary", good);
                outputs.put("detail", foreign);
                assertThrows(SecurityException.class, () -> steps.settle(permit, StepStatus.SUCCEEDED, outputs, null,
                        (current, metadata) -> {
                            assertEquals(current, metadata.owner(), "Foreign identities must fail before authorization");
                            return true;
                        }));
                assertEquals(unchanged, fixture.steps(atExpiry).step(run, "summarize").orElseThrow());
            }
            AtomicInteger deniedChecks = new AtomicInteger();
            assertThrows(SecurityException.class, () -> steps.settle(permit, StepStatus.SUCCEEDED,
                    Map.of("summary", good), null, (current, metadata) -> {
                        deniedChecks.incrementAndGet();
                        return false;
                    }));
            assertEquals(1, deniedChecks.get());
            assertEquals(unchanged, fixture.steps(atExpiry).step(run, "summarize").orElseThrow());
            assertThrows(SecurityException.class, () -> steps.settle(permit, StepStatus.SUCCEEDED,
                    Map.of("summary", expired), null, (current, metadata) -> {
                        fail("An artifact expired at this exact instant must not reach authorization");
                        return true;
                    }));
            assertEquals(unchanged, fixture.steps(atExpiry).step(run, "summarize").orElseThrow());
            StepRecord succeeded = steps.settle(permit, StepStatus.SUCCEEDED, Map.of("summary", good), null, ALLOW);
            assertEquals(Map.of("summary", good), succeeded.outputs());
            assertEquals(StepStatus.SUCCEEDED, succeeded.status());
            assertEquals(0, fixture.dataSource().payloadReads.get());
        } finally {
            steps.callbackExited(permit);
        }
    }

    @Test
    void waitingRequiresARealJobAndRefreshBecomesReadyOnlyAfterEveryChildIsReady() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        RunToken run = createRun(runs, OWNER, "run-waiting");
        CampaignStepStore steps = fixture.steps();
        steps.initialize(run, List.of(simpleStep("explore")));
        StepPermit first = steps.beginStep(run, "explore");
        try {
            assertThrows(IllegalStateException.class,
                    () -> steps.settle(first, StepStatus.WAITING, Map.of(), "awaiting-job", ALLOW));
            prepareChild(runs, run, "explore", "left", ChildMode.ASYNC);
            prepareChild(runs, run, "explore", "right", ChildMode.ASYNC);
            assertThrows(IllegalStateException.class,
                    () -> steps.settle(first, StepStatus.WAITING, Map.of(), "awaiting-job", ALLOW));
            submitWaiting(runs, run, "left");
            assertEquals(StepStatus.WAITING,
                    steps.settle(first, StepStatus.WAITING, Map.of(), "awaiting-job", ALLOW).status());
        } finally {
            steps.callbackExited(first);
        }
        assertEquals(StepStatus.WAITING, steps.refreshWaiting(run, "explore").status());
        completeWaiting(runs, run, "left");
        assertEquals(StepStatus.WAITING, steps.refreshWaiting(run, "explore").status(),
                "One ready child cannot hide another prepared child");
        submitWaiting(runs, run, "right");
        DispatchPermit child = runs.beginReconciliation(run, "right");
        try {
            assertFalse(steps.mayAdvance(run));
            assertThrows(IllegalStateException.class, () -> steps.acquireRun(run));
            assertThrows(IllegalStateException.class, () -> steps.beginStep(run, "explore"));
            runs.publishReady(child, artifact("artifact-right", NOW.plusSeconds(3600)));
        } finally {
            runs.callbackExited(child);
        }
        StepRecord ready = fixture.steps().refreshWaiting(run, "explore");
        assertEquals(StepStatus.READY, ready.status());
        assertFalse(ready.callbackActive());
        assertTrue(ready.outputs().isEmpty(), "Refreshing child receipts cannot synthesize a successful step output");
        StepPermit continued = steps.beginStep(run, "explore");
        try {
            assertNotEquals(first.attemptId(), continued.attemptId());
            assertTrue(continued.attemptVersion() > first.attemptVersion());
            assertEquals(StepStatus.SUCCEEDED,
                    steps.settle(continued, StepStatus.SUCCEEDED, Map.of(), null, ALLOW).status());
        } finally {
            steps.callbackExited(continued);
        }
    }

    @Test
    void unresolvedChildReceiptCannotBeRefreshedIntoReadyOrSuccess() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        RunToken run = createRun(runs, OWNER, "run-unknown");
        CampaignStepStore steps = fixture.steps();
        steps.initialize(run, List.of(simpleStep("explore"), simpleStep("manual-block")));
        prepareChild(runs, run, "explore", "unknown", ChildMode.ASYNC);
        StepPermit permit = steps.beginStep(run, "explore");
        try {
            submitWaiting(runs, run, "unknown");
            steps.settle(permit, StepStatus.WAITING, Map.of(), "awaiting-job", ALLOW);
        } finally {
            steps.callbackExited(permit);
        }
        DispatchPermit polling = runs.beginReconciliation(run, "unknown");
        try {
            runs.markUnresolved(polling);
        } finally {
            runs.callbackExited(polling);
        }
        assertEquals(ChildState.UNRESOLVED, runs.child(run, "unknown").orElseThrow().state());
        assertEquals(UnresolvedReason.JOB_RESULT_UNKNOWN, runs.child(run, "unknown").orElseThrow().reason());
        assertEquals("job-unknown", runs.child(run, "unknown").orElseThrow().jobId());
        StepRecord refreshed = fixture.steps().refreshWaiting(run, "explore");
        assertFalse(Set.of(StepStatus.READY, StepStatus.SUCCEEDED).contains(refreshed.status()));
        assertTrue(refreshed.outputs().isEmpty());
        assertThrows(IllegalStateException.class, () -> steps.beginStep(run, "explore"));
        assertFalse(steps.mayExecute(permit));
        assertEquals(ChildState.UNRESOLVED, runs.child(run, "unknown").orElseThrow().state());

        prepareChild(runs, run, "manual-block", "manual-source", ChildMode.SYNC);
        StepPermit manual = steps.beginStep(run, "manual-block");
        try {
            DispatchPermit source = runs.beginDispatch(run, "manual-source");
            try {
                runs.publishReady(source, artifact("artifact-manual-source", NOW.plusSeconds(3600)));
            } finally {
                runs.callbackExited(source);
            }
            steps.settle(manual, StepStatus.BLOCKED, Map.of(), "ARTIFACT_ACCESS_DENIED", ALLOW);
        } finally {
            steps.callbackExited(manual);
        }
        StepRecord explicitlyBlocked = fixture.steps().step(run, "manual-block").orElseThrow();
        assertEquals(ChildState.READY, runs.child(run, "manual-source").orElseThrow().state());
        assertEquals(explicitlyBlocked, steps.refreshWaiting(run, "manual-block"),
                "Ready children cannot erase an explicit authorization or schema block");
        assertEquals(StepStatus.BLOCKED, explicitlyBlocked.status());
        assertEquals("ARTIFACT_ACCESS_DENIED", explicitlyBlocked.reason());
    }

    @Test
    void callbackExitMarksUnknownResultsAndFencedPermitsCannotPublishOrClearANewerCallback() {
        Fixture interrupted = fixture();
        RunToken interruptedRun = createRun(interrupted.runs(), OWNER, "run-interrupted");
        CampaignStepStore interruptedSteps = interrupted.steps();
        interruptedSteps.initialize(interruptedRun, List.of(simpleStep("work")));
        StepPermit abandoned = interruptedSteps.beginStep(interruptedRun, "work");
        interruptedSteps.callbackExited(abandoned);
        StepRecord blocked = interrupted.steps().step(interruptedRun, "work").orElseThrow();
        assertEquals(StepStatus.BLOCKED, blocked.status());
        assertEquals("STEP_RESULT_UNKNOWN", blocked.reason());
        assertFalse(blocked.callbackActive());
        assertFalse(interruptedSteps.mayExecute(abandoned));
        assertThrows(IllegalStateException.class,
                () -> interruptedSteps.settle(abandoned, StepStatus.SUCCEEDED, Map.of(), null, ALLOW));

        Fixture retry = fixture();
        RunToken retryRun = createRun(retry.runs(), OWNER, "run-retry");
        CampaignStepStore retrySteps = retry.steps();
        retrySteps.initialize(retryRun, List.of(simpleStep("work")));
        prepareChild(retry.runs(), retryRun, "work", "retry-source", ChildMode.ASYNC);
        StepPermit oldAttempt = retrySteps.beginStep(retryRun, "work");
        try {
            submitWaiting(retry.runs(), retryRun, "retry-source");
            retrySteps.settle(oldAttempt, StepStatus.WAITING, Map.of(), "awaiting-job", ALLOW);
            assertThrows(IllegalStateException.class, () -> retrySteps.beginStep(retryRun, "work"));
        } finally {
            retrySteps.callbackExited(oldAttempt);
        }
        completeWaiting(retry.runs(), retryRun, "retry-source");
        assertEquals(StepStatus.READY, retrySteps.refreshWaiting(retryRun, "work").status());
        StepPermit newAttempt = retrySteps.beginStep(retryRun, "work");
        try {
            assertThrows(IllegalStateException.class, () -> retrySteps.callbackExited(oldAttempt));
            assertThrows(IllegalStateException.class,
                    () -> retrySteps.settle(oldAttempt, StepStatus.SUCCEEDED, Map.of(), null, ALLOW));
            assertTrue(retrySteps.step(retryRun, "work").orElseThrow().callbackActive());
            assertTrue(retrySteps.mayExecute(newAttempt));
            retrySteps.settle(newAttempt, StepStatus.SUCCEEDED, Map.of(), null, ALLOW);
        } finally {
            retrySteps.callbackExited(newAttempt);
        }

        for (boolean revise : List.of(false, true)) {
            Fixture fixture = fixture();
            CampaignRunStore runs = fixture.runs();
            CampaignStepStore steps = fixture.steps();
            RunToken original = createRun(runs, OWNER, "run-fenced");
            steps.initialize(original, List.of(simpleStep("work")));
            StepPermit old = steps.beginStep(original, "work");
            RunToken next = null;
            if (revise) {
                next = runs.revise(original, 2, "{\"scopeRef\":\"scope-next\"}");
                steps.initialize(next, List.of(simpleStep("work")));
                RunToken blockedByOldCallback = next;
                assertThrows(IllegalStateException.class, () -> steps.beginStep(blockedByOldCallback, "work"));
                assertThrows(IllegalStateException.class, () -> steps.acquireRun(blockedByOldCallback));
            } else {
                runs.cancel(original);
            }
            assertFalse(steps.mayExecute(old));
            assertThrows(IllegalStateException.class,
                    () -> steps.settle(old, StepStatus.SUCCEEDED, Map.of(), null, ALLOW));
            steps.callbackExited(old);
            if (revise) {
                StepPermit current = steps.beginStep(next, "work");
                try {
                    steps.callbackExited(old);
                    assertTrue(steps.step(next, "work").orElseThrow().callbackActive());
                    assertTrue(steps.mayExecute(current));
                    assertFalse(steps.mayExecute(old));
                    steps.settle(current, StepStatus.SUCCEEDED, Map.of(), null, ALLOW);
                } finally {
                    steps.callbackExited(current);
                }
            } else {
                assertEquals(RunStatus.CANCELLED, runs.loadRun(OWNER, original.definition().runId()).orElseThrow().status());
                assertThrows(IllegalStateException.class, () -> steps.beginStep(original, "work"));
            }
        }
    }

    private static RunToken createRun(CampaignRunStore runs, Caller caller, String runId) {
        return runs.createRun(new RunDefinition(caller, "session-" + runId, runId, "plan-" + runId, 1,
                "{\"scopeRef\":\"scope-frozen\",\"periodsRef\":\"periods-frozen\"}"));
    }

    private static StepSpec simpleStep(String stepId, String... dependencies) {
        return new StepSpec(stepId, "{\"executionMode\":\"FIXED\",\"executorVersion\":\"tool/1\"}",
                List.of(dependencies), Set.of(), Set.of());
    }

    private static StepSpec outputStep(String stepId) {
        return new StepSpec(stepId, "{\"executionMode\":\"FIXED\",\"executorVersion\":\"summary/1\"}",
                List.of(), Set.of("summary", "detail"), Set.of("summary"));
    }

    private static void prepareChild(CampaignRunStore runs, RunToken run, String stepId, String childId, ChildMode mode) {
        String actionId = "action-" + childId;
        runs.prepareAction(run, new ActionSpec(actionId, stepId, "TOOL", "campaign_stats", "stats/1", "{}"));
        runs.prepareChild(run, new ChildSpec(childId, actionId, mode, run.definition().runId() + "-" + childId,
                new WireRequest("POST", "/analytics/query", "{\"scopeRef\":\"scope-frozen\"}")));
    }

    private static String seedArtifact(CampaignRunStore runs, Caller caller, String runId, Instant expiresAt) {
        RunToken run = createRun(runs, caller, runId);
        prepareChild(runs, run, "producer", "child-" + runId, ChildMode.SYNC);
        DispatchPermit permit = runs.beginDispatch(run, "child-" + runId);
        String artifactId = "artifact-" + runId;
        try {
            runs.publishReady(permit, artifact(artifactId, expiresAt));
        } finally {
            runs.callbackExited(permit);
        }
        return artifactId;
    }

    private static ArtifactDraft artifact(String artifactId, Instant expiresAt) {
        return new ArtifactDraft(artifactId, "CAMPAIGN_STATS", "stats/1", "scope-frozen", "periods-frozen",
                "{\"complete\":true}", "{\"snapshotId\":\"snapshot-fixed\"}", expiresAt, "{\"pv\":42}");
    }

    private static void submitWaiting(CampaignRunStore runs, RunToken run, String childId) {
        DispatchPermit permit = runs.beginDispatch(run, childId);
        try {
            runs.recordWaiting(permit, "job-" + childId);
        } finally {
            runs.callbackExited(permit);
        }
    }

    private static void completeWaiting(CampaignRunStore runs, RunToken run, String childId) {
        DispatchPermit permit = runs.beginReconciliation(run, childId);
        try {
            runs.publishReady(permit, artifact("artifact-" + childId, NOW.plusSeconds(3600)));
        } finally {
            runs.callbackExited(permit);
        }
    }

    private static AcquireOutcome acquireAfter(CampaignStepStore store, RunToken token,
                                                CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Acquire contenders did not start");
        try {
            return new AcquireOutcome(store.acquireRun(token), null);
        } catch (IllegalStateException conflict) {
            return new AcquireOutcome(null, conflict);
        }
    }

    private static Fixture fixture() {
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_steps_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        delegate.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"))
                .execute(delegate);
        PayloadGuardDataSource guarded = new PayloadGuardDataSource(delegate);
        return new Fixture(new JdbcTemplate(guarded),
                new TransactionTemplate(new DataSourceTransactionManager(guarded)), guarded);
    }

    private record AcquireOutcome(RunToken token, IllegalStateException failure) {}

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, PayloadGuardDataSource dataSource) {
        private CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        private CampaignStepStore steps() { return steps(CLOCK); }
        private CampaignStepStore steps(Clock clock) { return new JdbcCampaignStepStore(jdbc, transactions, clock); }
    }

    private static final class PayloadGuardDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger payloadReads = new AtomicInteger();
        private volatile boolean forbidPayloadReads;

        private PayloadGuardDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public Connection getConnection() throws SQLException { return guard(delegate.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return guard(delegate.getConnection(username, password));
        }

        private Connection guard(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (args != null && args.length > 0 && args[0] instanceof String sql) {
                            String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
                            if (normalized.startsWith("select") && normalized.contains("campaign_artifact_payload")) {
                                payloadReads.incrementAndGet();
                                if (forbidPayloadReads) throw new AssertionError("Step output validation loaded artifact payload");
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
