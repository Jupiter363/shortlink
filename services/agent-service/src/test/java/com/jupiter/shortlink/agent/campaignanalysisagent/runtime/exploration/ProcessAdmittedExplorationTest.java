package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.AdmittedCampaignAdvance;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real native callbacks and JDBC facts; only the remote tool/model implementations are scripted. */
@Timeout(40)
class ProcessAdmittedExplorationTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ArtifactAuthorizer ALLOW = (caller, artifact) -> OWNER.equals(caller);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROMPT = "Inspect the authorized evidence", CONFIG = "e".repeat(64);
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
    private static final String PAYLOAD = "{\"pv\":13,\"collectionQuality\":\"UNKNOWN\"}";

    @Test
    void actualNativeCallbackRetainsAdmissionAfterCancellationAndQueuedRunLoadsOnlyAfterExitThenResumesItsOriginalJob() throws Exception {
        var f = new Fixture();
        var first = f.create("first-run", false);
        var second = f.create("second-run", true);
        var third = f.create("third-run", true);
        var workers = Executors.newFixedThreadPool(2);
        var tools = Executors.newFixedThreadPool(2);
        var consumed = new LinkedBlockingQueue<Boolean>();
        Executor trackedWorkers = work -> workers.execute(() -> {
            try { work.run(); }
            finally { consumed.add(Boolean.TRUE); }
        });
        var release = new CountDownLatch(1);
        first.release = release;
        first.model = new ScriptedExplorationChatModel().thenReturn(call("first-call", first.toolName));
        second.model = new ScriptedExplorationChatModel(
                prompt -> call("async-call", second.toolName),
                prompt -> {
                    var receipts = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                            .filter(response -> "async-call".equals(response.id())).toList();
                    assertEquals(1, receipts.size());
                    assertTrue(receipts.get(0).responseData().contains("PENDING"));
                    assertTrue(receipts.get(0).responseData().contains(second.jobId()));
                    String history = prompt.getInstructions().toString();
                    assertEquals(1, occurrences(history, "trusted_action_observation"));
                    assertTrue(history.contains(second.outputId()));
                    assertFalse(history.contains(PAYLOAD), "The native history receives an authorized reference, not raw evidence");
                    return ScriptedExplorationChatModel.text("Evidence is ready; this remains an analysis candidate.");
                });
        third.model = new ScriptedExplorationChatModel();
        var admitted = new AdmittedCampaignAdvance(new ProcessCapacityExecutor.Limits(1, 1, 1, 1), trackedWorkers,
                (reference, scope) -> {
                    RunCase run = f.cases.get(reference.runId());
                    assertEquals("advance", reference.workId());
                    run.loads.incrementAndGet();
                    run.processScope.set(scope);
                    if (run == second && run.loads.get() == 1) {
                        assertTrue(first.callbackReturned.get());
                        assertEquals(0, f.activeCalls(first.id));
                        assertEquals(0, first.processScope.get().activeCount());
                        assertTrue(first.processScope.get().isClosed());
                    }
                    return f.load(run, tools, scope);
                });
        try {
            Future<Void> cancelled = admitted.submit(first.reference());
            assertTrue(first.entered.await(8, TimeUnit.SECONDS), "The real native tool must reach its blocking I/O");
            assertTrue(first.operationReturned.await(8, TimeUnit.SECONDS), "Native timeout must return while the tool still exists");
            assertEquals("BLOCKED", first.result.get().get("status"));
            assertEquals(1, first.model.callCount()); assertEquals(1, first.io.get());
            assertEquals(1, f.activeCalls(first.id)); assertFalse(first.callbackReturned.get());
            assertEquals(ChildState.READY, f.runs.child(first.token, first.childId()).orElseThrow().state());
            assertThrows(TimeoutException.class, () -> cancelled.get(20, TimeUnit.MILLISECONDS),
                    "The admitted operation waits for the real callback, not just native's visible timeout");
            assertTrue(cancelled.cancel(true)); assertTrue(cancelled.isCancelled());

            Future<Void> queued = admitted.submit(second.reference());
            var rejected = assertThrows(ProcessCapacityExecutor.CapacityRejectedException.class,
                    () -> admitted.submit(third.reference()));
            assertEquals(ProcessCapacityExecutor.RejectionReason.QUEUE_FULL, rejected.reason());
            var occupied = admitted.snapshot();
            assertEquals(1, occupied.activeAdvances()); assertEquals(1, occupied.models());
            assertEquals(1, occupied.largePayloads()); assertEquals(1, occupied.queued());
            for (RunCase notAdmitted : List.of(second, third)) {
                assertEquals(0, notAdmitted.loads.get()); assertEquals(0, notAdmitted.historyLoads.get());
                assertEquals(0, notAdmitted.artifactLoads.get()); assertEquals(0, notAdmitted.model.callCount());
                assertEquals(0, f.modelChildren(notAdmitted.id)); assertEquals(0, notAdmitted.io.get());
            }
            assertFalse(queued.isDone());

            release.countDown();
            queued.get(10, TimeUnit.SECONDS);
            awaitConsumed(consumed, 2); // Future completion alone does not assert actual executor exit.
            assertIdle(admitted);
            assertEquals(0, f.activeCalls(first.id)); assertEquals(0, f.activeChildren(first.id));
            assertEquals(0, first.laterIo.get()); assertNotNull(first.lateDenial.get());
            assertFalse(first.result.get().toString().contains("PRIVATE_LATE_TOOL_DETAIL"));
            assertEquals("WAITING", second.result.get().get("status"));
            assertEquals(StepStatus.WAITING, f.steps.step(second.token, "explore").orElseThrow().status());
            ChildRecord original = f.runs.child(second.token, second.childId()).orElseThrow();
            assertEquals(ChildState.WAITING, original.state()); assertEquals(second.jobId(), original.jobId());
            assertFalse(original.callbackActive()); assertEquals(0, f.activeCalls(second.id));
            assertEquals(1, second.io.get()); assertEquals(1, second.model.callCount());
            assertEquals(1, second.loads.get()); assertEquals(1, second.artifactLoads.get());

            second.remoteReady.set(true);
            admitted.submit(second.reference()).get(10, TimeUnit.SECONDS);
            awaitConsumed(consumed, 1);
            assertIdle(admitted);
            assertEquals("CANDIDATE", second.result.get().get("status"));
            assertEquals(List.of(second.outputId()), second.result.get().get("artifactIds"));
            assertEquals(2, second.loads.get()); assertEquals(2, second.historyLoads.get());
            assertEquals(2, second.artifactLoads.get()); assertEquals(2, second.model.callCount());
            assertEquals(1, second.io.get(), "Reconciliation and native recovery cannot resubmit the original ASYNC child");
            assertEquals(1, second.receives.get()); assertEquals(1, f.callCount(second.id));
            ChildRecord ready = f.runs.child(second.token, second.childId()).orElseThrow();
            assertEquals(original.spec(), ready.spec()); assertEquals(original.jobId(), ready.jobId());
            assertEquals(ChildState.READY, ready.state()); assertEquals(2, ready.attemptVersion());
            assertEquals(PAYLOAD, f.runs.readArtifact(OWNER, second.outputId(), ALLOW).payloadJson());
            assertEquals(0, f.modelChildren(third.id)); assertEquals(0, third.loads.get());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE callback_active=TRUE", Integer.class));
            first.model.assertExhausted(); second.model.assertExhausted(); third.model.assertExhausted();
        } finally {
            release.countDown();
            admitted.close();
            tools.shutdown(); workers.shutdown();
            assertTrue(tools.awaitTermination(8, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(8, TimeUnit.SECONDS));
        }
    }

    private static void assertIdle(AdmittedCampaignAdvance admitted) {
        var snapshot = admitted.snapshot();
        assertEquals(0, snapshot.activeAdvances()); assertEquals(0, snapshot.models());
        assertEquals(0, snapshot.largePayloads()); assertEquals(0, snapshot.queued());
        assertEquals(0, snapshot.running()); assertEquals(0, snapshot.dispatched());
    }
    private static void awaitConsumed(BlockingQueue<Boolean> consumed, int count) throws InterruptedException {
        for (int index = 0; index < count; index++) assertEquals(Boolean.TRUE, consumed.poll(8, TimeUnit.SECONDS));
    }
    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        while (release.getCount() != 0) {
            try { release.await(); }
            catch (InterruptedException cancelled) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static int occurrences(String value, String part) {
        int count = 0, offset = 0;
        while ((offset = value.indexOf(part, offset)) >= 0) { count++; offset += part.length(); }
        return count;
    }
    private static org.springframework.ai.chat.model.ChatResponse call(String id, String name) {
        return ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall(id, "function", name, "{}"));
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException(invalid); }
    }
    private static ArtifactDraft artifact(String id) {
        return new ArtifactDraft(id, "Evidence", "evidence/v1", "scope-frozen", "periods-frozen", "{}", "{}", EXPIRY, PAYLOAD);
    }

    private static final class RunCase {
        final String id, toolName;
        final boolean async;
        final PlanSpec.Step planned;
        final AtomicInteger loads = new AtomicInteger(), historyLoads = new AtomicInteger(), artifactLoads = new AtomicInteger();
        final AtomicInteger io = new AtomicInteger(), laterIo = new AtomicInteger(), receives = new AtomicInteger();
        final AtomicBoolean callbackReturned = new AtomicBoolean(), remoteReady = new AtomicBoolean();
        final AtomicReference<ProcessExecutionScope> processScope = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> result = new AtomicReference<>();
        final AtomicReference<Exception> lateDenial = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1), operationReturned = new CountDownLatch(1);
        volatile RunToken token;
        CountDownLatch release;
        ScriptedExplorationChatModel model;
        RunCase(String id, boolean async, PlanSpec.Step planned, RunToken token) {
            this.id = id; this.async = async; this.planned = planned; this.token = token;
            this.toolName = async ? "submit_async" : "inspect_metrics";
        }
        WorkRef reference() { return new WorkRef(id, "advance"); }
        String sourceId() { return id + "-source"; }
        String childId() { return id + "-business"; }
        String jobId() { return id + "-job"; }
        String outputId() { return id + "-evidence"; }
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final Map<String, RunCase> cases = new HashMap<>();
        final ModelInvocationRegistry models = new ModelInvocationRegistry(List.of(
                new ModelInvocationRegistry.Contract("scripted-model", "1", CONFIG, invocation -> true)));
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:process_admission_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql"),
                    new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql")).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK); steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(jdbc, tx, CLOCK);
        }
        RunCase create(String id, boolean async) {
            var executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, async ? "submit_async" : "inspect_metrics", "1");
            var step = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
                    new PlanSpec.ExplorationPolicy("admission-explore", "1", List.of(executor), "scope-frozen", "periods-frozen",
                            List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of())), "bounded-fixture"),
                    List.of(), Map.of(), Map.of(), "analysis-evidence/v1");
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, id + "-plan", 1, id, id + "-inputs",
                    List.of(new PlanSpec.Goal("goal", "Inspect evidence", true, "Evidence-backed answer")), List.of(step));
            var frozen = FrozenCampaignRun.freeze(plan, new FrozenInputSet(id + "-inputs", id, Map.of(), Map.of()),
                    new PlanningAssessment(plan.planId(), 1, "fixture/v1", List.of(), List.of(), List.of()));
            var token = runs.createRun(frozen.definition(OWNER, id + "-session"));
            var value = new RunCase(id, async, step, token);
            // Genuine pre-existing evidence is setup only; admitted loading below reads it afresh.
            runs.prepareAction(token, new ActionSpec("seed", "source", "TOOL", "source_evidence", "1", "{}"));
            runs.prepareChild(token, new ChildSpec("source-child", "seed", ChildMode.SYNC, id + "-source-request",
                    new WireRequest("GET", "/fixture/source", "{}")));
            var seed = runs.beginDispatch(token, "source-child");
            try { runs.publishReady(seed, artifact(value.sourceId())); }
            finally { runs.callbackExited(seed); }
            token = steps.acquireRun(token); value.token = token;
            steps.initialize(token, List.of(new StepSpec("explore", encode(step), List.of(), Set.of(), Set.of())));
            cases.put(id, value); return value;
        }
        AdmittedCampaignAdvance.Operation load(RunCase run, Executor executor, ProcessExecutionScope process) throws Exception {
            if (run.loads.get() > 1) {
                run.token = steps.acquireRun(run.token);
                assertTrue(run.remoteReady.get());
                var reconcile = runs.beginReconciliation(run.token, run.childId());
                try { run.receives.incrementAndGet(); runs.publishReady(reconcile, artifact(run.outputId())); }
                finally { runs.callbackExited(reconcile); }
                assertEquals(StepStatus.READY, steps.refreshWaiting(run.token, "explore").status());
            }
            run.artifactLoads.incrementAndGet();
            Artifact source = runs.readArtifact(OWNER, run.sourceId(), ALLOW);
            assertEquals(PAYLOAD, source.payloadJson());
            StepPermit permit = steps.beginStep(run.token, "explore");
            run.historyLoads.incrementAndGet();
            var tool = new ModelInvocationRegistry.ToolDefinition(run.toolName, "Read approved evidence", JSON.readTree(SCHEMA));
            var configuration = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIG, null,
                    List.of(tool), Map.of("source", source.metadata()), EXPIRY);
            var ledger = new JdbcExplorationLedger(jdbc, tx, CLOCK, new JdbcCampaignRunStore(jdbc, tx, CLOCK),
                    new JdbcCampaignStepStore(jdbc, tx, CLOCK), new JdbcCampaignExplorationCallStore(jdbc, tx, CLOCK), permit,
                    models, configuration, Map.of(run.toolName, run.planned.explorationPolicy().allowedExecutors().get(0)), ALLOW);
            ToolCallback callback = new ToolCallback() {
                @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return org.springframework.ai.tool.definition.ToolDefinition.builder().name(run.toolName)
                            .description(tool.description()).inputSchema(SCHEMA).build();
                }
                @Override public String call(String input) { throw new AssertionError("Actual native dispatch context required"); }
                @Override public String call(String input, ToolContext context) {
                    var dispatchScope = (NativeExplorationAdapter.DispatchScope) context.getContext().get(NativeExplorationAdapter.DISPATCH_SCOPE);
                    var parent = dispatchScope.callPermit();
                    runs.prepareChild(run.token, new ChildSpec(run.childId(), parent.actionId(), run.async ? ChildMode.ASYNC : ChildMode.SYNC,
                            run.id + "-business-request", new WireRequest(run.async ? "POST" : "GET", "/fixture/" + run.toolName, "{}")));
                    var dispatch = runs.beginDispatch(run.token, run.childId(), parent);
                    try {
                        dispatchScope.dispatch(run.io::incrementAndGet);
                        if (run.async) { runs.recordWaiting(dispatch, run.jobId()); return run.jobId(); }
                        runs.publishReady(dispatch, artifact(run.outputId()));
                        runs.callbackExited(dispatch);
                        run.entered.countDown();
                        awaitIgnoringInterrupt(run.release);
                        try { dispatchScope.dispatch(run.laterIo::incrementAndGet); }
                        catch (Exception denied) { run.lateDenial.set(denied); }
                        throw new IllegalStateException("PRIVATE_LATE_TOOL_DETAIL");
                    } catch (Exception failed) { throw new IllegalStateException(failed); }
                    finally { runs.callbackExited(dispatch); run.callbackReturned.set(true); }
                }
            };
            var adapter = new NativeExplorationAdapter(ledger.identity(), ledger, run.model,
                    List.of(new NativeExplorationAdapter.RegisteredTool(callback, raw -> run.async
                            ? NativeExplorationAdapter.Observation.pending(raw) : NativeExplorationAdapter.Observation.ready(raw))),
                    new MemorySaver(), executor, new NativeExplorationAdapter.Limits(0, 4096, 4096, 32768, 8,
                            run.async ? Duration.ofSeconds(5) : Duration.ofSeconds(1)), ledger, process);
            assertSame(process, adapter.processExecutionScope());
            return () -> {
                try {
                    Map<String, Object> result = adapter.invoke(PROMPT); run.result.set(result);
                    if ("WAITING".equals(result.get("status"))) steps.settle(permit, StepStatus.WAITING, Map.of(), null, ALLOW);
                } finally { steps.callbackExited(permit); run.operationReturned.countDown(); }
            };
        }
        int activeCalls(String id) { return count("campaign_exploration_call", id, "callback_active=TRUE"); }
        int activeChildren(String id) { return count("campaign_child_ledger", id, "callback_active=TRUE"); }
        int modelChildren(String id) { return count("campaign_child_ledger", id, "child_mode='MODEL'"); }
        int callCount(String id) { return count("campaign_exploration_call", id, "1=1"); }
        int count(String table, String id, String condition) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=? AND " + condition, Integer.class, id);
        }
    }
}
