package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real JDBC business ledgers and native MemorySaver; only registered tool callbacks are fixtures. */
@Timeout(30)
class PersistentPlanDriverTest {
    private static final Instant NOW = Instant.parse("2026-09-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final TypeRef EVIDENCE = new TypeRef("CampaignEvidence", 1, Cardinality.ONE);
    private static final TypeRef SCOPE = new TypeRef("ScopeRef", 1, Cardinality.ONE);
    private static final TypeRef PERIODS = new TypeRef("PeriodsRef", 1, Cardinality.ONE);
    private static final PlanSpec.ExecutorRef QUERY = executor("query");
    private static final PlanSpec.ExecutorRef CONSUME = executor("consume-evidence");
    private static final PlanSpec.ExecutorRef INDEPENDENT = executor("independent-query");
    private static final ArtifactAuthorizer ALLOW_ARTIFACT = (caller, metadata) -> true;
    private static final String SCOPE_REF = "scope-frozen-7";
    private static final String PERIODS_REF = "periods-frozen-7";
    private static final String QUALITY = "{\"status\":\"COMPLETE\",\"uvApproximate\":true}";
    private static final String PROVENANCE = "{\"snapshotId\":\"snapshot-original\",\"evidenceRefs\":[\"source-original\"]}";

    @Test
    void waitingJobSurvivesReopenAndNamedOutputReusesEvidenceWithoutSubmittingOrRepeatingCompletedSteps() throws Exception {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        RunToken token = acquire(runs, steps, frozen());
        AtomicInteger aExecutions = new AtomicInteger();
        AtomicInteger aSubmissions = new AtomicInteger();
        AtomicInteger bExecutions = new AtomicInteger();
        AtomicInteger cCalls = new AtomicInteger();
        AtomicReference<ArtifactMetadata> consumed = new AtomicReference<>();
        List<PersistentPlanDriver.FixedExecutor> executors = List.of(
                fixed(QUERY, context -> {
                    aExecutions.incrementAndGet();
                    ChildRecord receipt = context.child(child("a", ChildMode.ASYNC), boundary -> {
                        boundary.beforeIo();
                        aSubmissions.incrementAndGet();
                        return CampaignStepExecution.ChildResult.waiting("job-original");
                    });
                    return receipt.state() == ChildState.READY
                            ? PersistentPlanDriver.Result.succeeded(Map.of("evidence", receipt.artifactId()))
                            : PersistentPlanDriver.Result.waiting();
                }),
                fixed(CONSUME, context -> {
                    bExecutions.incrementAndGet();
                    assertEquals(1, context.goalSpecs().size());
                    assertEquals("Analyze frozen campaign evidence", context.goalSpecs().get(0).question());
                    assertEquals("Deliver verified evidence", context.goalSpecs().get(0).acceptance());
                    BoundArtifact evidence = context.inputs().artifact("upstream");
                    assertEquals(13, evidence.payload().path("pv").intValue());
                    consumed.set(evidence.metadata());
                    return PersistentPlanDriver.Result.succeeded(Map.of("evidence", evidence.metadata().ref().artifactId()));
                }),
                readyExecutor(INDEPENDENT, "c", cCalls, "evidence/v1"));
        MemorySaver saver = new MemorySaver();
        PersistentPlanDriver first = driver(token, runs, steps, executors, ALLOW_ARTIFACT);
        NativePlanGraph originalGraph = first.compile(saver);

        assertTrue(originalGraph.advance().scanCompleted());
        assertStates(steps, token, WAITING, PENDING, SUCCEEDED);
        assertEquals("job-original", runs.child(token, "child-a").orElseThrow().jobId());
        assertCallbacksExited(fixture);
        assertEquals(1, aSubmissions.get());
        assertEquals(0, bExecutions.get());
        assertEquals(1, cCalls.get());

        CampaignRunStore reopenedRuns = fixture.runs();
        CampaignStepStore reopenedSteps = fixture.steps();
        RunToken acquired = reopenedSteps.acquireRun(token);
        DispatchPermit reconciliation = reopenedRuns.beginReconciliation(acquired, "child-a");
        try {
            assertEquals(DispatchPurpose.RECONCILE, reconciliation.purpose());
            reopenedRuns.publishReady(reconciliation, artifact("artifact-a", "evidence/v1"));
        } finally {
            reopenedRuns.callbackExited(reconciliation);
        }
        PersistentPlanDriver resumed = driver(acquired, reopenedRuns, reopenedSteps, executors, ALLOW_ARTIFACT);
        resumed.refreshWaiting();
        assertStates(reopenedSteps, acquired, READY, PENDING, SUCCEEDED);
        NativePlanGraph resumedGraph = resumed.compile(saver);
        assertEquals(originalGraph.threadId(), resumedGraph.threadId());
        assertTrue(resumedGraph.advance().scanCompleted());
        assertStates(reopenedSteps, acquired, SUCCEEDED, SUCCEEDED, SUCCEEDED);
        assertEquals(2, aExecutions.get(), "The resumed adapter reads its READY child once");
        assertEquals(1, aSubmissions.get(), "Reading a READY child must never invoke the submission callback");
        assertEquals(1, bExecutions.get());
        assertEquals(1, cCalls.get());
        assertEquals(Map.of("evidence", "artifact-a"), reopenedSteps.step(acquired, "a").orElseThrow().outputs());
        assertEquals(Map.of("evidence", "artifact-a"), reopenedSteps.step(acquired, "b").orElseThrow().outputs());
        Artifact originalEvidence = reopenedRuns.readArtifact(OWNER, "artifact-a", ALLOW_ARTIFACT);
        assertEquals(originalEvidence.metadata(), consumed.get());
        assertEquals(QUALITY, consumed.get().qualityJson());
        assertEquals(PROVENANCE, consumed.get().provenanceJson());
        assertEquals("job-original", reopenedRuns.child(acquired, "child-a").orElseThrow().jobId());
        resumed.refreshWaiting();
        resumedGraph.advance();
        assertEquals(2, aExecutions.get());
        assertEquals(1, bExecutions.get());
        assertEquals(1, cCalls.get());
        assertCallbacksExited(fixture);
    }

    @Test
    void wrongOutputSchemaBlocksItsConsumerButIndependentWorkFinishesAndRefreshCannotReviveIt() throws Exception {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        RunToken token = acquire(runs, steps, frozen());
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        AtomicInteger cCalls = new AtomicInteger();
        PersistentPlanDriver driver = driver(token, runs, steps, List.of(
                readyExecutor(QUERY, "a", aCalls, "evidence/v999"),
                fixed(CONSUME, context -> {
                    bCalls.incrementAndGet();
                    return PersistentPlanDriver.Result.blocked("SHOULD_NOT_EXECUTE");
                }),
                readyExecutor(INDEPENDENT, "c", cCalls, "evidence/v1")), ALLOW_ARTIFACT);
        NativePlanGraph graph = driver.compile(new MemorySaver());

        assertTrue(graph.advance().scanCompleted());
        assertStates(steps, token, BLOCKED, PENDING, SUCCEEDED);
        assertEquals("OUTPUT_CONTRACT_INVALID", steps.step(token, "a").orElseThrow().reason());
        assertTrue(steps.step(token, "a").orElseThrow().outputs().isEmpty());
        assertEquals(ChildState.READY, runs.child(token, "child-a").orElseThrow().state(),
                "A stored payload is not proof that it satisfies the named output contract");
        driver.refreshWaiting();
        graph.advance();
        assertStates(steps, token, BLOCKED, PENDING, SUCCEEDED);
        assertEquals(1, aCalls.get());
        assertEquals(0, bCalls.get());
        assertEquals(1, cCalls.get());
        assertCallbacksExited(fixture);
    }

    @Test
    void cancellationInsideChildRejectsFurtherIoRetainsLateJobAndPublishesNoOutput() throws Exception {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        RunToken token = acquire(runs, steps, frozen());
        AtomicInteger childCalls = new AtomicInteger();
        AtomicInteger followingCalls = new AtomicInteger();
        AtomicBoolean postCancelIoRejected = new AtomicBoolean();
        PersistentPlanDriver.Executor following = context -> {
            followingCalls.incrementAndGet();
            return PersistentPlanDriver.Result.blocked("SHOULD_NOT_EXECUTE");
        };
        PersistentPlanDriver driver = driver(token, runs, steps, List.of(
                fixed(QUERY, context -> {
                    context.child(child("a", ChildMode.ASYNC), boundary -> {
                        boundary.beforeIo();
                        childCalls.incrementAndGet();
                        runs.cancel(token);
                        assertThrows(SecurityException.class, boundary::beforeIo);
                        postCancelIoRejected.set(true);
                        return CampaignStepExecution.ChildResult.waiting("job-returned-after-cancel");
                    });
                    return PersistentPlanDriver.Result.waiting();
                }), fixed(CONSUME, following), fixed(INDEPENDENT, following)), ALLOW_ARTIFACT);
        NativePlanGraph graph = driver.compile(new MemorySaver());

        assertFalse(graph.advance().scanCompleted());
        assertEquals(1, childCalls.get());
        assertTrue(postCancelIoRejected.get());
        assertEquals(0, followingCalls.get());
        assertEquals(RunStatus.CANCELLED, runs.loadRun(OWNER, token.definition().runId()).orElseThrow().status());
        assertEquals("job-returned-after-cancel", fixture.jdbc().queryForObject(
                "SELECT job_id FROM campaign_child_ledger WHERE child_id='child-a'", String.class));
        assertEquals(ChildState.UNRESOLVED.name(), fixture.jdbc().queryForObject(
                "SELECT child_state FROM campaign_child_ledger WHERE child_id='child-a'", String.class));
        assertEquals("{}", fixture.jdbc().queryForObject(
                "SELECT outputs_json FROM campaign_step_ledger WHERE step_id='a'", String.class));
        assertEquals(PENDING.name(), fixture.jdbc().queryForObject(
                "SELECT step_status FROM campaign_step_ledger WHERE step_id='c'", String.class));
        assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));
        assertCallbacksExited(fixture);
        driver.refreshWaiting();
        assertFalse(graph.advance().scanCompleted());
        assertEquals(1, childCalls.get());
        assertEquals(0, followingCalls.get());
    }

    @Test
    void unknownFrozenFieldsVersionsAndMissingLaterExecutorFailBeforeAnyDispatch() {
        for (String invalid : List.of("unknown-field", "version", "missing-later-executor")) {
            Fixture fixture = fixture();
            CampaignRunStore runs = fixture.runs();
            CampaignStepStore steps = fixture.steps();
            RunDefinition valid = frozen().definition(OWNER, "session-1");
            String json = valid.definitionJson();
            if (invalid.equals("unknown-field")) json = json.substring(0, json.length() - 1) + ",\"unexpected\":true}";
            if (invalid.equals("version")) json = json.replace("\"campaign-run/v1\"", "\"campaign-run/v999\"");
            RunToken token = steps.acquireRun(runs.createRun(new RunDefinition(OWNER, valid.sessionId(), valid.runId(),
                    valid.planId(), valid.revision(), json)));
            AtomicInteger calls = new AtomicInteger();
            PersistentPlanDriver.Executor callback = context -> {
                calls.incrementAndGet();
                return PersistentPlanDriver.Result.blocked("SHOULD_NOT_EXECUTE");
            };
            List<PersistentPlanDriver.FixedExecutor> callbacks = invalid.equals("missing-later-executor")
                    ? List.of(fixed(QUERY, callback), fixed(CONSUME, callback))
                    : List.of(fixed(QUERY, callback), fixed(CONSUME, callback), fixed(INDEPENDENT, callback));
            assertThrows(IllegalArgumentException.class, () -> driver(token, runs, steps, callbacks, ALLOW_ARTIFACT), invalid);
            assertEquals(0, calls.get(), invalid);
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_action_ledger", Integer.class), invalid);
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_child_ledger", Integer.class), invalid);
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class), invalid);
        }
    }

    @Test
    void artifactPermissionRevokedBeforeOutputPublicationBlocksReuseWithoutBlockingIndependentEvidence() throws Exception {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        RunToken token = acquire(runs, steps, frozen());
        AtomicBoolean artifactAllowed = new AtomicBoolean(true);
        AtomicInteger aCalls = new AtomicInteger();
        AtomicInteger bCalls = new AtomicInteger();
        AtomicInteger cCalls = new AtomicInteger();
        ArtifactAuthorizer authorization = (caller, metadata) -> !metadata.ref().artifactId().equals("artifact-a")
                || artifactAllowed.get();
        PersistentPlanDriver driver = driver(token, runs, steps, List.of(
                fixed(QUERY, context -> {
                    ChildRecord receipt = context.child(child("a", ChildMode.SYNC), boundary -> {
                        boundary.beforeIo();
                        aCalls.incrementAndGet();
                        return CampaignStepExecution.ChildResult.ready(artifact("artifact-a", "evidence/v1"));
                    });
                    artifactAllowed.set(false);
                    return PersistentPlanDriver.Result.succeeded(Map.of("evidence", receipt.artifactId()));
                }),
                fixed(CONSUME, context -> {
                    bCalls.incrementAndGet();
                    return PersistentPlanDriver.Result.blocked("SHOULD_NOT_EXECUTE");
                }), readyExecutor(INDEPENDENT, "c", cCalls, "evidence/v1")), authorization);
        NativePlanGraph graph = driver.compile(new MemorySaver());

        assertTrue(graph.advance().scanCompleted());
        assertStates(steps, token, BLOCKED, PENDING, SUCCEEDED);
        assertEquals("EXECUTION_ACCESS_DENIED", steps.step(token, "a").orElseThrow().reason());
        assertTrue(steps.step(token, "a").orElseThrow().outputs().isEmpty());
        assertEquals(ChildState.READY, runs.child(token, "child-a").orElseThrow().state());
        assertThrows(SecurityException.class, () -> runs.readArtifact(OWNER, "artifact-a", authorization));
        driver.refreshWaiting();
        graph.advance();
        assertEquals(1, aCalls.get());
        assertEquals(0, bCalls.get());
        assertEquals(1, cCalls.get());
        assertCallbacksExited(fixture);
    }

    private static PersistentPlanDriver driver(RunToken token, CampaignRunStore runs, CampaignStepStore steps,
            List<PersistentPlanDriver.FixedExecutor> executors, ArtifactAuthorizer authorizer) {
        return new PersistentPlanDriver(token, runs, steps, catalog(), contracts(), executors,
                (caller, inputs) -> true, authorizer, (caller, type, ref) -> true);
    }

    private static RunToken acquire(CampaignRunStore runs, CampaignStepStore steps, FrozenCampaignRun frozen) {
        return steps.acquireRun(runs.createRun(frozen.definition(OWNER, "session-1")));
    }

    private static PersistentPlanDriver.FixedExecutor readyExecutor(PlanSpec.ExecutorRef ref, String stepId,
            AtomicInteger calls, String schema) {
        return fixed(ref, context -> {
            calls.incrementAndGet();
            ChildRecord receipt = context.child(child(stepId, ChildMode.SYNC), boundary -> {
                boundary.beforeIo();
                return CampaignStepExecution.ChildResult.ready(artifact("artifact-" + stepId, schema));
            });
            return PersistentPlanDriver.Result.succeeded(Map.of("evidence", receipt.artifactId()));
        });
    }

    private static PersistentPlanDriver.FixedExecutor fixed(PlanSpec.ExecutorRef ref, PersistentPlanDriver.Executor callback) {
        return new PersistentPlanDriver.FixedExecutor(ref, new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                assertEquals(SCOPE_REF, inputs.value("scope"));
                assertEquals(PERIODS_REF, inputs.value("periods"));
            }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                outputs.values().forEach(output -> {
                    assertEquals(SCOPE_REF, output.metadata().ref().scopeRef());
                    assertEquals(PERIODS_REF, output.metadata().ref().periodsRef());
                });
            }
        }, callback);
    }

    private static ArtifactContractRegistry contracts() {
        return new ArtifactContractRegistry(List.of(new ArtifactContractRegistry.Contract(EVIDENCE,
                "CAMPAIGN_EVIDENCE", "evidence/v1", payload -> payload.isObject() && payload.path("pv").isIntegralNumber(),
                (metadata, quality) -> "COMPLETE".equals(quality.path("status").asText()))));
    }

    private static CapabilityCatalog catalog() {
        Map<String, Port> scopeInputs = Map.of("scope", new Port(SCOPE, true), "periods", new Port(PERIODS, true));
        Signature query = new Signature(scopeInputs, "evidence/v1", Map.of("evidence", new Port(EVIDENCE, true)), Parameters.none());
        Signature consume = new Signature(Map.of("scope", new Port(SCOPE, true), "periods", new Port(PERIODS, true),
                "upstream", new Port(EVIDENCE, true)), "evidence/v1", Map.of("evidence", new Port(EVIDENCE, true)), Parameters.none());
        Map<PlanSpec.ExecutorRef, Capability> capabilities = Map.of(QUERY, new Capability(QUERY, query, false),
                CONSUME, new Capability(CONSUME, consume, false), INDEPENDENT, new Capability(INDEPENDENT, query, false));
        return new CapabilityCatalog() {
            @Override public String version() { return "catalog/v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef ref) { return Optional.ofNullable(capabilities.get(ref)); }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return "delivery".equals(ref) && "1".equals(version)
                        ? Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY,
                                Parameters.none(), Set.of(EVIDENCE))) : Optional.empty();
            }
        };
    }

    private static FrozenCampaignRun frozen() {
        Map<String, PlanBinding> scope = Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods"));
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Analyze frozen campaign evidence", true, "Deliver verified evidence")),
                List.of(step("a", QUERY, List.of(), scope), step("b", CONSUME, List.of("a"),
                                Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods"),
                                        "upstream", PlanBinding.output("a", "evidence"))),
                        step("c", INDEPENDENT, List.of(), scope)));
        FrozenInputSet inputs = new FrozenInputSet("inputs-1", "run-1",
                Map.of("scope", new Port(SCOPE, true), "periods", new Port(PERIODS, true)),
                Map.of("scope", SCOPE_REF, "periods", PERIODS_REF));
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery-1", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery-1",
                        List.of(new PlanningAssessment.EvidenceOutput("b", "evidence")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment);
    }

    private static PlanSpec.Step step(String id, PlanSpec.ExecutorRef executor, List<String> dependsOn,
            Map<String, PlanBinding> inputs) {
        return new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED, executor, null,
                dependsOn, inputs, Map.of(), "evidence/v1");
    }

    private static PlanSpec.ExecutorRef executor(String name) {
        return new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, name, "1");
    }

    private static ChildSpec child(String stepId, ChildMode mode) {
        return new ChildSpec("child-" + stepId, "action-" + stepId, mode, "request-" + stepId,
                new WireRequest("POST", "/statistics/query", "{\"scopeRef\":\"scope-frozen-7\"}"));
    }

    private static ArtifactDraft artifact(String id, String schema) {
        return new ArtifactDraft(id, "CAMPAIGN_EVIDENCE", schema, SCOPE_REF, PERIODS_REF, QUALITY,
                PROVENANCE, NOW.plusSeconds(3600), "{\"pv\":13}");
    }

    private static void assertStates(CampaignStepStore steps, RunToken token, StepStatus a, StepStatus b, StepStatus c) {
        assertEquals(3, steps.steps(token).size());
        for (Map.Entry<String, StepStatus> expected : Map.of("a", a, "b", b, "c", c).entrySet()) {
            assertEquals(expected.getValue(), steps.step(token, expected.getKey()).orElseThrow().status(), expected.getKey());
        }
    }

    private static void assertCallbacksExited(Fixture fixture) {
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
    }

    private static Fixture fixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:persistent_plan_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql")).execute(dataSource);
        return new Fixture(new JdbcTemplate(dataSource), new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions) {
        CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        CampaignStepStore steps() { return new JdbcCampaignStepStore(jdbc, transactions, CLOCK); }
    }
}
