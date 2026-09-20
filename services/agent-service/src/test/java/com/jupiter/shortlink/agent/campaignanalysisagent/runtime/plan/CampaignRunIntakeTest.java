package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.Header;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.State;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual intake transactions, admission, recovery coordinator and scoped native fixed Plan scans. */
@Timeout(40)
class CampaignRunIntakeTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final String PROFILE = "fixture-fixed", SESSION = "intake-session";
    private static final PlanSpec.ExecutorRef QUERY = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "fixture-query", "1");
    private static final TypeRef EVIDENCE = new TypeRef("CampaignEvidence", 1, Cardinality.ONE);
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> OWNER.equals(caller);
    private static final ProcessIdentity PROCESS = new ProcessIdentity("53bbebd1-70a5-422b-a223-cab944651592",
            "intake-test-namespace", 11002, NOW.minusSeconds(30).toEpochMilli());

    @Test
    void concurrentRegistrationAndFreezeKeepOneRunWhoseOriginalJobAndOutputsSurviveReplayButNotCancellationOrRevision() throws Exception {
        try (var f = new Fixture()) {
            RunDefinition proposal = proposal(OWNER, SESSION, "request-1", 1, "Inspect authorized evidence");
            var registers = together(() -> f.intake.register(PRINCIPAL, SESSION, "request-1", PROFILE, "1", proposal));
            WorkRef reference = registers.get(0);
            assertEquals(reference, registers.get(1));
            Header pending = f.intake.receipt(PRINCIPAL, reference);
            assertEquals(State.PENDING, pending.state()); assertEquals(1, f.count("campaign_run_intake"));
            assertEquals(0, f.count("campaign_run_ledger")); assertEquals(0, f.runtimeCalls.get());
            assertEquals(0, f.toolCalls.get());

            // The trusted Plan contract is checked before exercising the store's atomic freeze.
            var frozen = FrozenCampaignRun.read(proposal);
            new PlanValidator(f.catalog, id -> Optional.empty()).validate(frozen.plan(), frozen.inputs(), frozen.assessment());
            var freezes = together(() -> f.requests.freeze(pending, proposal));
            assertEquals(freezes.get(0), freezes.get(1)); assertEquals(State.FROZEN, freezes.get(0).state());
            assertEquals(1, f.count("campaign_run_ledger"));
            assertEquals(proposal, f.current(reference).definition());

            RunDefinition changed = proposal(OWNER, SESSION, "request-1", 1, "A different authorized question");
            assertThrows(IllegalStateException.class,
                    () -> f.intake.register(PRINCIPAL, SESSION, "request-1", PROFILE, "1", changed));
            assertThrows(IllegalStateException.class,
                    () -> f.intake.register(PRINCIPAL, SESSION, "request-1", "fixture-other", "1", proposal));
            Caller reauthorized = new Caller("1001", "analyst", 8);
            assertEquals(JdbcCampaignRunIntakeStore.identity(OWNER, SESSION, "request-1"),
                    JdbcCampaignRunIntakeStore.identity(reauthorized, SESSION, "request-1"));
            assertThrows(IllegalStateException.class, () -> f.requests.register(reauthorized, SESSION, "request-1", PROFILE, "1",
                    proposal(reauthorized, SESSION, "request-1", 1, "Inspect authorized evidence")));
            assertEquals(freezes.get(0), f.intake.receipt(PRINCIPAL, reference));

            f.advance(reference);
            RunToken waitingToken = f.current(reference).token();
            assertEquals(WAITING, f.steps.step(waitingToken, "query").orElseThrow().status());
            ChildRecord waiting = f.runs.child(waitingToken, "query-child").orElseThrow();
            assertEquals(ChildState.WAITING, waiting.state()); assertEquals(jobId(reference), waiting.jobId());
            assertEquals(1, f.toolCalls.get()); assertEquals(1, f.executorCalls.get());
            assertEquals(1, f.count("campaign_run_owner"), "The existing recovery coordinator must register the first writer");
            assertEquals(State.FROZEN, f.intake.receipt(PRINCIPAL, reference).state(), "FROZEN is not a completed business result");
            f.assertExited();

            f.advance(reference); // A known WAITING job is neither resubmitted nor treated as ready.
            assertEquals(1, f.toolCalls.get()); assertEquals(1, f.executorCalls.get());
            assertEquals(WAITING, f.steps.step(f.current(reference).token(), "query").orElseThrow().status());
            RunToken receiverToken = f.current(reference).token();
            var receive = f.runs.beginReconciliation(receiverToken, "query-child");
            try { f.runs.publishReady(receive, artifact(reference)); }
            finally { f.runs.callbackExited(receive); }
            Artifact received = f.runs.readArtifact(OWNER, artifactId(reference), ALLOW);
            f.advance(reference);
            RunToken completed = f.current(reference).token();
            var completedStep = f.steps.step(completed, "query").orElseThrow();
            assertEquals(SUCCEEDED, completedStep.status());
            assertEquals(Map.of("evidence", artifactId(reference)), completedStep.outputs());
            ChildRecord ready = f.runs.child(completed, "query-child").orElseThrow();
            assertEquals(waiting.spec(), ready.spec()); assertEquals(waiting.jobId(), ready.jobId());
            assertEquals(1, f.toolCalls.get()); assertEquals(2, f.executorCalls.get());
            f.advance(reference);
            assertEquals(2, f.executorCalls.get()); assertEquals(1, f.toolCalls.get());
            assertEquals(received, f.runs.readArtifact(OWNER, artifactId(reference), ALLOW));
            assertEquals(freezes.get(0), f.intake.receipt(PRINCIPAL, reference));
            assertEquals(0, f.gatewayCalls.get()); f.assertExited();

            int runtimeBeforeCancel = f.runtimeCalls.get();
            f.runs.cancel(f.current(reference).token());
            assertEquals(reference, f.intake.register(PRINCIPAL, SESSION, "request-1", PROFILE, "1", proposal));
            assertEquals("CAMPAIGN_RUN_CANCELLED", f.failedAdvance(reference).getMessage());
            assertEquals(RunStatus.CANCELLED, f.current(reference).status());
            assertEquals(1, f.count("campaign_run_ledger")); assertEquals(runtimeBeforeCancel, f.runtimeCalls.get());
            assertEquals(received, f.runs.readArtifact(OWNER, artifactId(reference), ALLOW));

            RunDefinition revisionProposal = proposal(OWNER, SESSION, "revision-request", 1, "Inspect another approved request");
            WorkRef revisionRef = f.intake.register(PRINCIPAL, SESSION, "revision-request", PROFILE, "1", revisionProposal);
            f.advance(revisionRef);
            RunDefinition next = proposal(OWNER, SESSION, "revision-request", 2, "Explicitly revised approved request");
            f.runs.revise(f.current(revisionRef).token(), 2, next.definitionJson());
            int runtimeBeforeRevisionReplay = f.runtimeCalls.get();
            assertEquals("CAMPAIGN_REQUEST_REVISION_CHANGED", f.failedAdvance(revisionRef).getMessage());
            assertEquals(2, f.current(revisionRef).definition().revision());
            assertEquals(RunStatus.ACTIVE, f.current(revisionRef).status());
            assertEquals(1, f.intake.receipt(PRINCIPAL, revisionRef).revision());
            assertEquals(runtimeBeforeRevisionReplay, f.runtimeCalls.get());
            assertEquals(2, f.toolCalls.get()); assertEquals(2, f.count("campaign_run_intake"));
            assertEquals(3, f.count("campaign_run_ledger"));
            assertEquals(0, f.gatewayCalls.get()); f.assertExited();
        }
    }

    @Test
    void queuedRevocationStopsBeforeProposalParsingOrRuntimeAndReferencesCannotBypassIdentityOrTransactionAdmission() throws Exception {
        try (var f = new Fixture()) {
            String blockedSession = "blocking-session", revokedSession = "revoked-session";
            f.blockedSession = blockedSession;
            WorkRef blocker = f.intake.register(PRINCIPAL, blockedSession, "blocker", PROFILE, "1",
                    proposal(OWNER, blockedSession, "blocker", 1, "Hold the admitted worker"));
            RunDefinition validIdentity = proposal(OWNER, revokedSession, "queued", 1, "Wait for current authority");
            // Registration deliberately stores bounded proposals without parsing. This sentinel
            // would fail parsing if the worker accessed it before its current-principal check.
            RunDefinition unparsed = new RunDefinition(OWNER, revokedSession, validIdentity.runId(), validIdentity.planId(), 1, "{not-json}");
            WorkRef queued = f.intake.register(PRINCIPAL, revokedSession, "queued", PROFILE, "1", unparsed);
            Future<Void> first = f.intake.submit(PRINCIPAL, blocker);
            try {
                assertTrue(f.factoryEntered.await(8, TimeUnit.SECONDS));
                Future<Void> denied = f.intake.submit(PRINCIPAL, queued);
                assertEquals(1, f.intake.snapshot().queued());
                assertEquals(State.PENDING, f.intake.receipt(PRINCIPAL, queued).state());
                assertTrue(f.runs.loadRun(OWNER, queued.runId()).isEmpty());
                assertEquals(0, f.runtimeFor(queued.runId())); assertEquals(0, f.toolFor(queued.runId()));
                f.revokedSessions.add(revokedSession);
                f.releaseFactory.countDown();
                first.get(8, TimeUnit.SECONDS);
                var failure = assertThrows(ExecutionException.class, () -> denied.get(8, TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class, failure.getCause(), "Revocation must win over malformed JSON parsing");
                f.awaitWorkers(2);
                Header preserved = f.requests.header(queued); // Metadata inspection is allowed without reading the proposal.
                assertEquals(State.PENDING, preserved.state()); assertEquals(unparsed.definitionHash(), preserved.definitionHash());
                assertTrue(f.runs.loadRun(OWNER, queued.runId()).isEmpty());
                assertEquals(0, f.runtimeFor(queued.runId())); assertEquals(0, f.toolFor(queued.runId()));
                assertEquals(1, f.count("campaign_run_ledger")); assertEquals(1, f.toolCalls.get());
                assertEquals(0, f.gatewayCalls.get());
                f.revokedSessions.remove(revokedSession);

                var other = new AgentPrincipal("1001", "another-user", 7, false);
                assertThrows(SecurityException.class, () -> f.intake.receipt(other, queued));
                assertThrows(SecurityException.class, () -> f.intake.submit(other, queued));
                assertThrows(IllegalStateException.class, () -> f.intake.submit(PRINCIPAL, new WorkRef("wrong-run", queued.workId())));
                assertThrows(IllegalStateException.class, () -> f.intake.submit(PRINCIPAL, new WorkRef(queued.runId(), "unknown-request")));
                f.tx.executeWithoutResult(status -> {
                    assertEquals("CAMPAIGN_INTAKE_REQUIRES_TOP_LEVEL", assertThrows(IllegalStateException.class,
                            () -> f.intake.submit(PRINCIPAL, queued)).getMessage());
                    assertThrows(IllegalStateException.class, () -> f.intake.register(PRINCIPAL, SESSION, "ambient", PROFILE, "1",
                            proposal(OWNER, SESSION, "ambient", 1, "Not yet committed")));
                });
                assertEquals(2, f.count("campaign_run_intake")); assertEquals(State.PENDING, f.intake.receipt(PRINCIPAL, queued).state());
                assertTrue(f.runs.loadRun(OWNER, queued.runId()).isEmpty());
                assertEquals(0, f.runtimeFor(queued.runId())); assertEquals(0, f.toolFor(queued.runId()));
                f.assertExited();
            } finally { f.releaseFactory.countDown(); }
        }
    }

    private static <T> List<T> together(Callable<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var both = new CountDownLatch(2); var start = new CountDownLatch(1);
        Callable<T> task = () -> { both.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return operation.call(); };
        try {
            Future<T> first = pool.submit(task), second = pool.submit(task);
            assertTrue(both.await(5, TimeUnit.SECONDS)); start.countDown();
            return List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS)); }
    }
    private static String artifactId(WorkRef reference) { return reference.runId() + "-evidence"; }
    private static String jobId(WorkRef reference) { return reference.runId() + "-job"; }
    private static ArtifactDraft artifact(WorkRef reference) {
        return new ArtifactDraft(artifactId(reference), "CAMPAIGN_EVIDENCE", "evidence/v1", "scope-original", "period-original",
                "{\"status\":\"COMPLETE\"}", "{\"snapshotId\":\"original\"}", NOW.plusSeconds(3600), "{\"pv\":13}");
    }
    private static RunDefinition proposal(Caller caller, String session, String key, int revision, String question) {
        var id = JdbcCampaignRunIntakeStore.identity(caller, session, key);
        String inputs = "inputs-" + CampaignRunStore.sha256(id.runId());
        var step = new PlanSpec.Step("query", List.of("goal"), PlanSpec.ExecutionMode.FIXED, QUERY, null,
                List.of(), Map.of(), Map.of(), "evidence/v1");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, id.planId(), revision, id.runId(), inputs,
                List.of(new PlanSpec.Goal("goal", question, true, "Deliver verified evidence")), List.of(step));
        var assessment = new PlanningAssessment(id.planId(), revision, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery-1", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())), List.of(new PlanningAssessment.CoverageBinding("delivery-1",
                        List.of(new PlanningAssessment.EvidenceOutput("query", "evidence")))), List.of());
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet(inputs, id.runId(), Map.of(), Map.of()), assessment).definition(caller, session);
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final JdbcCampaignRunStore runs;
        final CampaignStepStore steps;
        final JdbcCampaignRunIntakeStore requests;
        final CampaignRunIntake intake;
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final BlockingQueue<Boolean> consumed = new LinkedBlockingQueue<>();
        final Set<String> revokedSessions = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> runtimeByRun = new ConcurrentHashMap<>(), toolByRun = new ConcurrentHashMap<>();
        final AtomicInteger runtimeCalls = new AtomicInteger(), executorCalls = new AtomicInteger(), toolCalls = new AtomicInteger(), gatewayCalls = new AtomicInteger();
        final CountDownLatch factoryEntered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        volatile String blockedSession;
        final CapabilityCatalog catalog = new CapabilityCatalog() {
            public String version() { return "catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return QUERY.equals(ref) ? Optional.of(new Capability(QUERY,
                        new Signature(Map.of(), "evidence/v1", Map.of("evidence", new Port(EVIDENCE, true)), Parameters.none()), false)) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) {
                return "delivery".equals(ref) && "1".equals(version) ? Optional.of(new Criterion(ref, version,
                        PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(EVIDENCE))) : Optional.empty();
            }
        };
        final ArtifactContractRegistry contracts = new ArtifactContractRegistry(List.of(new ArtifactContractRegistry.Contract(EVIDENCE,
                "CAMPAIGN_EVIDENCE", "evidence/v1", payload -> payload.path("pv").isIntegralNumber(),
                (metadata, quality) -> "COMPLETE".equals(quality.path("status").asText()))));
        Fixture() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:intake_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_17__campaign_run_intake.sql")).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK); steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            requests = new JdbcCampaignRunIntakeStore(jdbc, tx, CLOCK, runs, 1024 * 1024);
            var recovery = new JdbcCampaignRecoveryStore(jdbc, tx, CLOCK, PROCESS,
                    process -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE));
            var gateway = new ShortLinkBusinessGateway() {
                public ToolResult get(String path, ToolContext context, Map<String, Object> parameters) { return unexpected(); }
                public ToolResult post(String path, ToolContext context, Map<String, Object> parameters) { return unexpected(); }
                public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> parameters) { return unexpected(); }
                private ToolResult unexpected() { gatewayCalls.incrementAndGet(); throw new AssertionError("Known jobs must not be resubmitted or recovered as unknown"); }
            };
            intake = new CampaignRunIntake(requests, runs, recovery, new StatisticsSubmissionReconciler(runs, gateway), null, null,
                    List.of(profile(PROFILE), profile("fixture-other")),
                    (caller, session) -> revokedSessions.contains(session) ? null : PRINCIPAL,
                    new ProcessCapacityExecutor.Limits(1, 1, 1, 1), work -> workers.execute(() -> {
                        try { work.run(); } finally { consumed.add(Boolean.TRUE); }
                    }));
        }
        CampaignRunIntake.Profile profile(String ref) {
            return new CampaignRunIntake.Profile(ref, "1", catalog, contracts,
                    (caller, inputs) -> OWNER.equals(caller), ALLOW, (caller, type, value) -> OWNER.equals(caller), this::runtime);
        }
        CampaignRecoveryCoordinator.Runtime runtime(CampaignRunIntake.RuntimeContext context) throws Exception {
            String runId = context.token().definition().runId();
            runtimeCalls.incrementAndGet(); runtimeByRun.computeIfAbsent(runId, ignored -> new AtomicInteger()).incrementAndGet();
            assertEquals(PRINCIPAL, context.principal()); assertFalse(context.scope().isClosed());
            assertTrue(context.scope().activeCount() > 0);
            if (context.token().definition().sessionId().equals(blockedSession)) {
                factoryEntered.countDown(); assertTrue(releaseFactory.await(10, TimeUnit.SECONDS));
            }
            var policy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { assertTrue(inputs.values().isEmpty()); }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertEquals(Set.of("evidence"), outputs.keySet());
                    assertEquals("scope-original", outputs.get("evidence").metadata().ref().scopeRef());
                }
            };
            var executor = new PersistentPlanDriver.FixedExecutor(QUERY, policy, execution -> {
                executorCalls.incrementAndGet();
                var ref = new WorkRef(runId, "existing-request");
                var child = new ChildSpec("query-child", "query-action", ChildMode.ASYNC, runId + "-request",
                        new WireRequest("POST", "/internal/short-link-admin/v1/agent-tools/statistics/jobs", "{}"));
                ChildRecord receipt = execution.child(child, boundary -> {
                    boundary.beforeIo(); toolCalls.incrementAndGet();
                    toolByRun.computeIfAbsent(runId, ignored -> new AtomicInteger()).incrementAndGet();
                    return CampaignStepExecution.ChildResult.waiting(jobId(ref));
                });
                return receipt.state() == ChildState.READY ? PersistentPlanDriver.Result.succeeded(Map.of("evidence", receipt.artifactId()))
                        : PersistentPlanDriver.Result.waiting();
            });
            var driver = new PersistentPlanDriver(context.token(), runs, steps, catalog, contracts, List.of(executor),
                    context.runAuthorizer(), context.artifactAuthorizer(), context.inputAuthorizer(), List.of(), null, context.scope());
            return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()));
        }
        RunRecord current(WorkRef ref) { return runs.loadRun(OWNER, ref.runId()).orElseThrow(); }
        void advance(WorkRef ref) throws Exception { intake.submit(PRINCIPAL, ref).get(8, TimeUnit.SECONDS); awaitWorkers(1); }
        Throwable failedAdvance(WorkRef ref) throws Exception {
            var future = intake.submit(PRINCIPAL, ref);
            var failure = assertThrows(ExecutionException.class, () -> future.get(8, TimeUnit.SECONDS));
            awaitWorkers(1); return failure.getCause();
        }
        void awaitWorkers(int count) throws InterruptedException {
            for (int index = 0; index < count; index++) assertEquals(Boolean.TRUE, consumed.poll(8, TimeUnit.SECONDS));
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        int runtimeFor(String runId) { return runtimeByRun.getOrDefault(runId, new AtomicInteger()).get(); }
        int toolFor(String runId) { return toolByRun.getOrDefault(runId, new AtomicInteger()).get(); }
        void assertExited() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
            var snapshot = intake.snapshot();
            assertEquals(0, snapshot.activeAdvances()); assertEquals(0, snapshot.models());
            assertEquals(0, snapshot.largePayloads()); assertEquals(0, snapshot.queued());
        }
        @Override public void close() throws InterruptedException {
            releaseFactory.countDown(); intake.close(); workers.shutdown(); assertTrue(workers.awaitTermination(8, TimeUnit.SECONDS));
        }
    }
}
