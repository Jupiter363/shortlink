package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStepExecution;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.PersistentPlanDriver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.Observation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.State;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real takeover, JDBC ledgers and native PlanGraph; process observations and remote responses are fixtures. */
@Timeout(30)
class CampaignRecoveryCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst-1", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), false);
    private static final ProcessIdentity OLD_PROCESS = new ProcessIdentity("a59ec8f9-81d7-4b55-a380-95e77cf0bf01",
            "fixture-process-domain", 11001, NOW.minusSeconds(120).toEpochMilli());
    private static final ProcessIdentity NEW_PROCESS = new ProcessIdentity("53bbebd1-70a5-422b-a223-cab944651592",
            "fixture-process-domain", 11002, NOW.minusSeconds(30).toEpochMilli());
    private static final ArtifactAuthorizer ALLOW_ARTIFACT = (caller, metadata) -> true;
    private static final PlanSpec.ExecutorRef TOOL = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "fixture-query", "1");
    private static final TypeRef EVIDENCE = new TypeRef("CampaignEvidence", 1, Cardinality.ONE);
    private static final String SUBMIT_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";

    @Test
    void mixedRecoveryOnlyFindsUnknownAsyncSubmissionAndKeepsReadyEvidenceWhileIndependentWorkContinues() throws Exception {
        Fixture fixture = fixture();
        seedUnresolved(fixture, "sync", ChildMode.SYNC);
        seedUnresolved(fixture, "async", ChildMode.ASYNC);
        seedWaiting(fixture, "known", "job-known");
        seedInterruptedReady(fixture);
        Artifact before = fixture.runs().readArtifact(OWNER, "artifact-ready", ALLOW_ARTIFACT);
        ChildSpec originalUnknown = fixture.runs().child(fixture.token(), "child-async").orElseThrow().spec();
        AtomicInteger factoryCalls = new AtomicInteger();
        RecordingGateway gateway = new RecordingGateway();
        gateway.recovery = (context, request) -> {
            assertEquals(PRINCIPAL, context.principal());
            assertEquals("request-async", request.get("requestId"));
            assertEquals("group-frozen", request.get("gid"));
            return ToolResult.success(Map.of("jobId", "job-recovered", "state", "RUNNING"));
        };
        CampaignRecoveryCoordinator coordinator = coordinator(fixture, probe(State.DEAD), gateway,
                factory(fixture, factoryCalls, new AtomicReference<>(), () -> true), (definition, current) -> true);

        CampaignRecoveryCoordinator.ResumeResult result = coordinator.resume(fixture.token(), PRINCIPAL);

        assertEquals(CampaignRecoveryCoordinator.Outcome.SCANNED, result.outcome());
        assertNotNull(result.scan());
        assertTrue(result.scan().scanCompleted());
        assertEquals(2, result.recoveredCallbacks(), "The crashed READY child and RUNNING step both require death proof");
        assertNotEquals(fixture.token(), result.token());
        assertEquals(1, factoryCalls.get());
        assertEquals(1, gateway.recoveries.get());
        assertNoOrdinaryHttp(gateway);
        assertEquals(StatisticsSubmissionReconciler.Outcome.UNRESOLVED, receipt(result, "sync").outcome());
        assertEquals("READ_RESULT_UNKNOWN", receipt(result, "sync").code());
        assertEquals(StatisticsSubmissionReconciler.Outcome.RECOVERED, receipt(result, "async").outcome());
        assertEquals("job-recovered", receipt(result, "async").jobId());
        assertEquals(StatisticsSubmissionReconciler.Outcome.KNOWN_JOB, receipt(result, "known").outcome());
        assertEquals("job-known", receipt(result, "known").jobId());
        assertEquals(StatisticsSubmissionReconciler.Outcome.ALREADY_READY, receipt(result, "ready").outcome());
        ChildRecord unresolved = fixture.runs().child(result.token(), "child-sync").orElseThrow();
        assertEquals(ChildState.UNRESOLVED, unresolved.state());
        assertEquals(UnresolvedReason.READ_RESULT_UNKNOWN, unresolved.reason());
        ChildRecord recovered = fixture.runs().child(result.token(), "child-async").orElseThrow();
        assertEquals(originalUnknown, recovered.spec());
        assertEquals(ChildState.WAITING, recovered.state());
        assertEquals("job-recovered", recovered.jobId());
        assertEquals(StepStatus.WAITING, fixture.steps().step(result.token(), "known").orElseThrow().status());
        assertEquals(StepStatus.BLOCKED, fixture.steps().step(result.token(), "sync").orElseThrow().status());
        assertEquals(StepStatus.PENDING, fixture.steps().step(result.token(), "dependent").orElseThrow().status());
        assertEquals(StepStatus.SUCCEEDED, fixture.steps().step(result.token(), "ready").orElseThrow().status());
        assertEquals(StepStatus.SUCCEEDED, fixture.steps().step(result.token(), "independent").orElseThrow().status());
        assertEquals(Map.of("evidence", "artifact-ready"), fixture.steps().step(result.token(), "ready").orElseThrow().outputs());
        assertEquals(before, fixture.runs().readArtifact(OWNER, "artifact-ready", ALLOW_ARTIFACT));
        assertEquals(1, fixture.executions().ready.get());
        assertEquals(1, fixture.executions().independent.get());
        assertEquals(0, fixture.executions().unexpected.get());
        assertCallbacksExited(fixture);
    }

    @Test
    void liveOrUnprovenOldOwnerAndDeniedCurrentScopeStopBeforeFactoryOrHttpWithoutChangingTheWriter() throws Exception {
        for (String scenario : List.of("ALIVE", "UNKNOWN", "SCOPE_DENIED")) {
            Fixture fixture = fixture();
            seedInterruptedAsync(fixture);
            AtomicInteger factoryCalls = new AtomicInteger();
            RecordingGateway gateway = new RecordingGateway();
            State oldState = scenario.equals("UNKNOWN") ? State.UNKNOWN : State.ALIVE;
            CampaignRecoveryCoordinator coordinator = coordinator(fixture, probe(oldState), gateway,
                    factory(fixture, factoryCalls, new AtomicReference<>(), () -> true),
                    (definition, current) -> !scenario.equals("SCOPE_DENIED"));

            CampaignRecoveryCoordinator.ResumeResult result = coordinator.resume(fixture.token(), PRINCIPAL);

            assertEquals(scenario.equals("SCOPE_DENIED") ? CampaignRecoveryCoordinator.Outcome.STOPPED
                    : CampaignRecoveryCoordinator.Outcome.BLOCKED, result.outcome(), scenario);
            assertNull(result.token());
            assertNull(result.scan());
            assertTrue(result.reconciliations().isEmpty());
            assertEquals(0, result.recoveredCallbacks());
            assertEquals(fixture.token(), fixture.runs().loadRun(OWNER, "run-1").orElseThrow().token());
            assertTrue(fixture.runs().child(fixture.token(), "child-async").orElseThrow().callbackActive());
            assertTrue(fixture.steps().step(fixture.token(), "async").orElseThrow().callbackActive());
            assertEquals(0, factoryCalls.get());
            assertEquals(0, gateway.recoveries.get());
            assertNoOrdinaryHttp(gateway);
            assertEquals(0, fixture.executions().total());
        }
    }

    @Test
    void authorizationAtActualRecoveryIoAndCancellationDuringResponseNeverAllowGraphOrOutputPublication() throws Exception {
        for (String scenario : List.of("BEFORE_IO", "DURING_IO_SCOPE_REVOKED", "DURING_IO_CANCELLED")) {
            Fixture fixture = fixture();
            seedInterruptedAsync(fixture);
            AtomicBoolean allowed = new AtomicBoolean(true);
            AtomicBoolean boundaryRevocationObserved = new AtomicBoolean();
            AtomicReference<RunToken> acquired = new AtomicReference<>();
            AtomicInteger factoryCalls = new AtomicInteger();
            RecordingGateway gateway = new RecordingGateway();
            gateway.recovery = (context, request) -> {
                if (scenario.equals("DURING_IO_SCOPE_REVOKED")) allowed.set(false);
                if (scenario.equals("DURING_IO_CANCELLED")) fixture.runs().cancel(acquired.get());
                return ToolResult.success(Map.of("jobId", "job-late", "state", "SUCCEEDED"));
            };
            CampaignRecoveryCoordinator.RunAuthorizer authority = (definition, current) -> {
                if (!allowed.get()) return false;
                if (scenario.equals("BEFORE_IO") && acquired.get() != null) {
                    ChildRecord child = fixture.runs().child(acquired.get(), "child-async").orElseThrow();
                    if (child.callbackActive() && child.purpose() == DispatchPurpose.RECONCILE) {
                        boundaryRevocationObserved.set(true);
                        allowed.set(false);
                        return false;
                    }
                }
                return true;
            };
            CampaignRecoveryCoordinator coordinator = coordinator(fixture, probe(State.DEAD), gateway,
                    factory(fixture, factoryCalls, acquired, allowed::get), authority);

            CampaignRecoveryCoordinator.ResumeResult result = coordinator.resume(fixture.token(), PRINCIPAL);

            assertEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, result.outcome(), scenario);
            assertNull(result.scan());
            assertEquals(2, result.recoveredCallbacks());
            assertEquals(1, factoryCalls.get());
            assertEquals(0, fixture.executions().total(), "Constructing the runtime must not enter its native Graph");
            assertEquals(scenario.equals("BEFORE_IO") ? 0 : 1, gateway.recoveries.get());
            assertNoOrdinaryHttp(gateway);
            if (scenario.equals("BEFORE_IO")) {
                assertTrue(boundaryRevocationObserved.get(), "The scope gate must run after the durable RECONCILE dispatch begins");
                assertNull(fixture.jdbc().queryForObject("SELECT job_id FROM campaign_child_ledger WHERE child_id='child-async'", String.class));
            } else {
                assertEquals("job-late", fixture.jdbc().queryForObject(
                        "SELECT job_id FROM campaign_child_ledger WHERE child_id='child-async'", String.class));
                assertEquals("job-late", result.reconciliations().get(0).jobId());
            }
            assertEquals(ChildState.UNRESOLVED.name(), fixture.jdbc().queryForObject(
                    "SELECT child_state FROM campaign_child_ledger WHERE child_id='child-async'", String.class));
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));
            assertEquals("{}", fixture.jdbc().queryForObject(
                    "SELECT outputs_json FROM campaign_step_ledger WHERE step_id='async'", String.class));
            assertEquals(scenario.equals("DURING_IO_CANCELLED") ? RunStatus.CANCELLED : RunStatus.ACTIVE,
                    fixture.runs().loadRun(OWNER, "run-1").orElseThrow().status());
            assertCallbacksExited(fixture);
        }
    }

    private static CampaignRecoveryCoordinator coordinator(Fixture fixture, ProcessLiveness probe, RecordingGateway gateway,
            CampaignRecoveryCoordinator.RuntimeFactory factory, CampaignRecoveryCoordinator.RunAuthorizer authorizer) {
        return new CampaignRecoveryCoordinator(new JdbcCampaignRecoveryStore(fixture.jdbc(), fixture.transactions(), CLOCK,
                NEW_PROCESS, probe), fixture.runs(), new StatisticsSubmissionReconciler(fixture.runs(), gateway), factory, authorizer);
    }

    private static CampaignRecoveryCoordinator.RuntimeFactory factory(Fixture fixture, AtomicInteger calls,
            AtomicReference<RunToken> acquired, BooleanSupplier authorized) {
        MemorySaver saver = new MemorySaver();
        return (token, principal) -> {
            calls.incrementAndGet();
            acquired.set(token);
            PersistentPlanDriver driver = fixture.driver(token, authorized);
            return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(saver));
        };
    }

    private static StatisticsSubmissionReconciler.Result receipt(CampaignRecoveryCoordinator.ResumeResult result, String stepId) {
        return result.reconciliations().stream().filter(receipt -> receipt.childId().equals("child-" + stepId))
                .findFirst().orElseThrow();
    }

    private static ProcessLiveness probe(State oldState) {
        return identity -> {
            if (identity.equals(NEW_PROCESS)) return new Observation(State.ALIVE, ProcessLiveness.PROCESS_ALIVE);
            assertEquals(OLD_PROCESS, identity);
            return switch (oldState) {
                case ALIVE -> new Observation(State.ALIVE, ProcessLiveness.PROCESS_ALIVE);
                case DEAD -> new Observation(State.DEAD, ProcessLiveness.PROCESS_EXITED);
                case UNKNOWN -> new Observation(State.UNKNOWN, ProcessLiveness.PROCESS_ACCESS_DENIED);
            };
        };
    }

    private static void seedUnresolved(Fixture fixture, String stepId, ChildMode mode) {
        StepPermit step = fixture.steps().beginStep(fixture.token(), stepId);
        try {
            DispatchPermit child = beginChild(fixture, stepId, mode);
            try { fixture.runs().markUnresolved(child); }
            finally { fixture.runs().callbackExited(child); }
            fixture.steps().settle(step, StepStatus.BLOCKED, Map.of(), "STEP_RESULT_UNKNOWN", ALLOW_ARTIFACT);
        } finally { fixture.steps().callbackExited(step); }
    }

    private static void seedWaiting(Fixture fixture, String stepId, String jobId) {
        StepPermit step = fixture.steps().beginStep(fixture.token(), stepId);
        try {
            DispatchPermit child = beginChild(fixture, stepId, ChildMode.ASYNC);
            try { fixture.runs().recordWaiting(child, jobId); }
            finally { fixture.runs().callbackExited(child); }
            fixture.steps().settle(step, StepStatus.WAITING, Map.of(), null, ALLOW_ARTIFACT);
        } finally { fixture.steps().callbackExited(step); }
    }

    private static void seedInterruptedReady(Fixture fixture) {
        fixture.steps().beginStep(fixture.token(), "ready");
        DispatchPermit child = beginChild(fixture, "ready", ChildMode.SYNC);
        fixture.runs().publishReady(child, artifact("ready"));
        // Simulated process exit after durable publication and before either actual finally.
    }

    private static void seedInterruptedAsync(Fixture fixture) {
        fixture.steps().beginStep(fixture.token(), "async");
        beginChild(fixture, "async", ChildMode.ASYNC);
        // Simulated process exit with an unknown remote submission and two active callback records.
    }

    private static DispatchPermit beginChild(Fixture fixture, String stepId, ChildMode mode) {
        String frozenStep = fixture.steps().step(fixture.token(), stepId).orElseThrow().spec().definitionJson();
        fixture.runs().prepareAction(fixture.token(), new ActionSpec("action-" + stepId, stepId,
                TOOL.kind().name(), TOOL.name(), TOOL.version(), frozenStep));
        fixture.runs().prepareChild(fixture.token(), child(stepId, mode));
        return fixture.runs().beginDispatch(fixture.token(), "child-" + stepId);
    }

    private static ChildSpec child(String stepId, ChildMode mode) {
        String requestId = "request-" + stepId;
        return new ChildSpec("child-" + stepId, "action-" + stepId, mode, requestId,
                new WireRequest(mode == ChildMode.ASYNC ? "POST" : "GET", mode == ChildMode.ASYNC ? SUBMIT_PATH : "/statistics/metrics",
                        "{\"requestId\":\"" + requestId + "\",\"gid\":\"group-frozen\",\"queryKind\":\"METRICS\"}"));
    }

    private static ArtifactDraft artifact(String stepId) {
        return new ArtifactDraft("artifact-" + stepId, "CAMPAIGN_EVIDENCE", "evidence/v1", "scope-original", "periods-original",
                "{\"status\":\"COMPLETE\"}", "{\"snapshotId\":\"snapshot-original\"}", NOW.plusSeconds(3600), "{\"pv\":13}");
    }

    private static CapabilityCatalog catalog() {
        Signature signature = new Signature(Map.of(), "evidence/v1", Map.of("evidence", new Port(EVIDENCE, true)), Parameters.none());
        return new CapabilityCatalog() {
            @Override public String version() { return "catalog/v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return TOOL.equals(ref) ? Optional.of(new Capability(TOOL, signature, false)) : Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return "delivery".equals(ref) && "1".equals(version)
                        ? Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY,
                                Parameters.none(), Set.of(EVIDENCE))) : Optional.empty();
            }
        };
    }

    private static FrozenCampaignRun frozen() {
        List<PlanSpec.Step> steps = List.of("sync", "async", "known", "ready", "dependent", "independent").stream()
                .map(id -> new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED, TOOL, null,
                        id.equals("dependent") ? List.of("sync") : List.of(), Map.of(), Map.of(), "evidence/v1")).toList();
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Recover the frozen campaign analysis", true, "Deliver verified evidence")), steps);
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery-1", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())), List.of(new PlanningAssessment.CoverageBinding("delivery-1",
                        List.of(new PlanningAssessment.EvidenceOutput("independent", "evidence")))), List.of());
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of()), assessment);
    }

    private static Fixture fixture() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_recovery_coordinator_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        CampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        CampaignStepStore steps = new JdbcCampaignStepStore(jdbc, transactions, CLOCK);
        RunToken created = runs.createRun(frozen().definition(OWNER, "session-1"));
        var registered = new JdbcCampaignRecoveryStore(jdbc, transactions, CLOCK, OLD_PROCESS,
                identity -> new Observation(State.ALIVE, ProcessLiveness.PROCESS_ALIVE)).recover(created);
        assertEquals(CampaignRecoveryStore.Outcome.ACQUIRED, registered.outcome());
        Fixture fixture = new Fixture(jdbc, transactions, runs, steps, registered.token(), new Executions());
        fixture.driver(fixture.token(), () -> true); // Initialize exactly the real Driver's frozen step definitions.
        return fixture;
    }

    private static void assertNoOrdinaryHttp(RecordingGateway gateway) {
        assertEquals(0, gateway.gets.get());
        assertEquals(0, gateway.posts.get());
    }

    private static void assertCallbacksExited(Fixture fixture) {
        assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
    }

    private static final class RecordingGateway implements ShortLinkBusinessGateway {
        private final AtomicInteger gets = new AtomicInteger();
        private final AtomicInteger posts = new AtomicInteger();
        private final AtomicInteger recoveries = new AtomicInteger();
        private BiFunction<ToolContext, Map<String, Object>, ToolResult> recovery = (context, request) -> {
            throw new AssertionError("Unexpected recover-existing call");
        };
        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> request) {
            gets.incrementAndGet();
            throw new AssertionError("Recovery must not reread synchronous or known-job results through this gateway");
        }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> request) {
            posts.incrementAndGet();
            throw new AssertionError("Recovery must not submit a new query");
        }
        @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries.incrementAndGet();
            return recovery.apply(context, request);
        }
    }

    private static final class Executions {
        private final AtomicInteger ready = new AtomicInteger();
        private final AtomicInteger independent = new AtomicInteger();
        private final AtomicInteger unexpected = new AtomicInteger();
        private int total() { return ready.get() + independent.get() + unexpected.get(); }
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, CampaignRunStore runs,
                           CampaignStepStore steps, RunToken token, Executions executions) {
        PersistentPlanDriver driver(RunToken current, BooleanSupplier authorized) {
            ArtifactContractRegistry contracts = new ArtifactContractRegistry(List.of(new ArtifactContractRegistry.Contract(EVIDENCE,
                    "CAMPAIGN_EVIDENCE", "evidence/v1", payload -> payload.path("pv").isIntegralNumber(),
                    (metadata, quality) -> "COMPLETE".equals(quality.path("status").asText()))));
            StepBindings.StepPolicy policy = new StepBindings.StepPolicy() {
                @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { assertTrue(inputs.values().isEmpty()); }
                @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                    assertEquals("scope-original", outputs.get("evidence").metadata().ref().scopeRef());
                }
            };
            var executor = new PersistentPlanDriver.FixedExecutor(TOOL, policy, context -> {
                String id = context.step().stepId();
                if (id.equals("ready")) {
                    executions.ready.incrementAndGet();
                    ChildRecord receipt = context.child(child(id, ChildMode.SYNC), boundary -> {
                        throw new AssertionError("The durable READY child must never be called again");
                    });
                    return PersistentPlanDriver.Result.succeeded(Map.of("evidence", receipt.artifactId()));
                }
                if (id.equals("independent")) {
                    executions.independent.incrementAndGet();
                    ChildRecord receipt = context.child(child(id, ChildMode.SYNC), boundary -> {
                        boundary.beforeIo();
                        return CampaignStepExecution.ChildResult.ready(artifact(id));
                    });
                    return PersistentPlanDriver.Result.succeeded(Map.of("evidence", receipt.artifactId()));
                }
                executions.unexpected.incrementAndGet();
                throw new AssertionError("Unresolved, waiting or dependent step must not execute: " + id);
            });
            return new PersistentPlanDriver(current, runs, steps, catalog(), contracts, List.of(executor),
                    (caller, inputs) -> authorized.getAsBoolean(), ALLOW_ARTIFACT, (caller, type, value) -> true);
        }
    }
}
