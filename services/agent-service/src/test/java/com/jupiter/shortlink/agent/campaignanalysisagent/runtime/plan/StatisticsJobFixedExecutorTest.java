package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real registered executor, ledgers and native Graph; only remote statistics responses are scripted. */
@Timeout(30)
class StatisticsJobFixedExecutorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final PlanSpec.ExecutorRef QUERY = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics_query_job", "1");
    private static final PlanSpec.ExecutorRef CONSUME = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "read_evidence", "1");
    private static final TypeRef PAGES = new TypeRef("StatisticsJobPages", 1, Cardinality.ONE);
    private static final String SCHEMA = CampaignStatisticsResultStore.SCHEMA_VERSION;

    @Test
    void firstSubmissionIsFrozenBeforeIoAndPublishesOnlyAfterAllPagesThenReusesReadyOutput() throws Exception {
        Fixture f = new Fixture(501);
        var initial = f.runtime(f.token);
        assertEquals(1, initial.graph().advance().advancedSteps());
        assertEquals(1, f.gateway.submits);
        assertEquals(CampaignStepStore.StepStatus.WAITING, f.steps.step(f.token, "collect").orElseThrow().status());
        assertEquals(0, f.downstream.get());
        assertEquals(0, f.gateway.statuses);
        assertEquals(0, f.count("campaign_artifact"));

        var first = f.coordinator().resume(f.token, PRINCIPAL);
        assertEquals(StatisticsJobResultReceiver.Outcome.RECEIVING, first.receivedResults().get(0).outcome());
        assertEquals(0, first.scan().advancedSteps());
        assertEquals(0, f.downstream.get());
        var second = f.coordinator().resume(first.token(), PRINCIPAL);
        assertEquals(StatisticsJobResultReceiver.Outcome.READY, second.receivedResults().get(0).outcome());
        assertEquals(2, second.scan().advancedSteps());
        assertEquals(1, f.downstream.get());
        assertEquals(List.of(0, 1), f.gateway.pages);
        assertEquals(1, f.gateway.submits);
        assertEquals(0, f.gateway.recoveries);
        var child = f.runs.children(second.token()).get(0);
        assertEquals(ChildState.READY, child.state());
        assertTrue(f.runs.readArtifact(OWNER, child.artifactId(), f.artifactAuth()).metadata().qualityJson().contains("PARTIAL"));
        int calls = f.gateway.calls();
        var third = f.coordinator().resume(second.token(), PRINCIPAL);
        assertEquals(0, third.scan().advancedSteps());
        assertEquals(calls, f.gateway.calls(), "A completed query and dependent read never redispatch");
        assertEquals(1, f.count("campaign_child_ledger"));
        f.assertExited();
    }

    @Test
    void lostSubmissionAckRecoversOnlyOriginalRequestAndUnavailableRecoveryNeverResubmits() throws Exception {
        Fixture f = new Fixture(0);
        f.gateway.loseAck = true;
        f.runtime(f.token).graph().advance();
        var child = f.runs.children(f.token).get(0);
        assertEquals(ChildState.UNRESOLVED, child.state());
        assertNull(child.jobId());
        assertEquals(1, f.gateway.submits);
        f.gateway.recoveryMissing = true;
        var missing = f.coordinator().resume(f.token, PRINCIPAL);
        assertEquals("REPLAY_UNAVAILABLE", missing.reconciliations().get(0).code());
        assertEquals(0, missing.scan().advancedSteps());
        assertEquals(1, f.gateway.submits);
        assertEquals(0, f.gateway.statuses);
        f.gateway.recoveryMissing = false;
        var recovered = f.coordinator().resume(missing.token(), PRINCIPAL);
        assertEquals(StatisticsSubmissionReconciler.Outcome.RECOVERED, recovered.reconciliations().get(0).outcome());
        assertEquals(2, recovered.scan().advancedSteps());
        assertEquals(List.of(0), f.gateway.pages, "Even zero-row results receive the original summary page");
        assertEquals(1, f.gateway.submits);
        assertEquals(2, f.gateway.recoveries);
        assertEquals(child.spec(), f.runs.children(recovered.token()).get(0).spec());
        assertEquals(1, f.downstream.get());
        f.assertExited();
    }

    @Test
    void currentPrincipalAndExactQueryAuthorizationFenceDispatchAndLateReception() throws Exception {
        Fixture f = new Fixture(1);
        assertThrows(SecurityException.class, () -> new StatisticsJobFixedExecutor(f.token.definition(),
                new AgentPrincipal("1001", "other", 7, false), f.gateway, f::queryAllowed));
        var initial = f.runtime(f.token);
        f.allowed.set(false);
        initial.graph().advance();
        assertEquals(0, f.gateway.calls());
        f.allowed.set(true);
        initial.graph().advance();
        assertEquals(1, f.gateway.submits);
        f.gateway.revokeDuringPage = true;
        var stopped = f.coordinator().resume(f.token, PRINCIPAL);
        assertEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, stopped.outcome());
        assertEquals(0, f.count("campaign_artifact"));
        assertEquals(0, f.downstream.get());
        assertNotEquals(ChildState.READY, f.runs.children(stopped.token()).get(0).state());
        f.assertExited();

        Fixture cancelled = new Fixture(1);
        cancelled.gateway.cancelDuringSubmit = true;
        cancelled.runtime(cancelled.token).graph().advance();
        assertEquals(RunStatus.CANCELLED, cancelled.runs.loadRun(OWNER, "run-1").orElseThrow().status());
        assertEquals("job-original", cancelled.jdbc.queryForObject("SELECT job_id FROM campaign_child_ledger", String.class));
        assertEquals(0, cancelled.count("campaign_artifact"));
        assertEquals(0, cancelled.downstream.get());
        assertEquals(1, cancelled.gateway.calls());
        cancelled.assertExited();
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignStatisticsResultStore results;
        final RunToken token;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicInteger downstream = new AtomicInteger();
        final Gateway gateway = new Gateway(this);
        final int rows;
        Fixture(int rows) {
            this.rows = rows;
            var ds = new DriverManagerDataSource("jdbc:h2:mem:statistics_executor_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            ds.setDriverClassName("org.h2.Driver");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql")).execute(ds);
            jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK); steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            results = new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK);
            token = steps.acquireRun(runs.createRun(frozen().definition(OWNER, "session-1")));
        }
        boolean queryAllowed(AgentPrincipal principal, String scope, String periods, Map<String, Object> request) {
            return allowed.get() && PRINCIPAL.equals(principal) && "scope-g1".equals(scope) && "period-september".equals(periods)
                    && "g1".equals(request.get("gid")) && "2026-09-01".equals(request.get("startDate"))
                    && "2026-09-01".equals(request.get("endDate")) && "ACCESS_RECORDS".equals(request.get("queryKind"));
        }
        ArtifactAuthorizer artifactAuth() {
            return (caller, metadata) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(metadata.owner())
                    && "scope-g1".equals(metadata.ref().scopeRef()) && "period-september".equals(metadata.ref().periodsRef());
        }
        CampaignRecoveryCoordinator.Runtime runtime(RunToken run) throws Exception {
            var adapter = new StatisticsJobFixedExecutor(run.definition(), PRINCIPAL, gateway, this::queryAllowed);
            var policy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { assertNotNull(inputs.artifact("upstream")); }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertEquals(inputs.artifact("upstream").metadata(), outputs.get("pages").metadata());
                }
            };
            var consume = new PersistentPlanDriver.FixedExecutor(CONSUME, policy, context -> {
                downstream.incrementAndGet();
                var artifact = context.inputs().artifact("upstream");
                assertEquals(rows, artifact.payload().path("totalRows").asInt());
                assertTrue(results.readPage(OWNER, artifact.metadata().ref().artifactId(), rows > 500 ? 1 : 0, artifactAuth()).contains("items"));
                return PersistentPlanDriver.Result.succeeded(Map.of("pages", artifact.metadata().ref().artifactId()));
            });
            var driver = new PersistentPlanDriver(run, runs, steps, catalog(),
                    new ArtifactContractRegistry(List.of(StatisticsJobFixedExecutor.artifactContract())),
                    List.of(adapter.registration(), consume), (caller, inputs) -> OWNER.equals(caller) && adapter.authorized(),
                    artifactAuth(), (caller, type, value) -> OWNER.equals(caller) && allowed.get()
                    && (type.equals(FrozenStatisticsJobQuery.SCOPE_TYPE) ? "scope-g1".equals(value) : "period-september".equals(value)));
            return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()), adapter.resultTargets());
        }
        CampaignRecoveryCoordinator coordinator() {
            var process = new ProcessIdentity(UUID.randomUUID().toString(), "test-domain", 1, 1);
            var recovery = new JdbcCampaignRecoveryStore(jdbc, tx, CLOCK, process,
                    identity -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE));
            return new CampaignRecoveryCoordinator(recovery, runs, new StatisticsSubmissionReconciler(runs, gateway),
                    (run, principal) -> runtime(run),
                    (definition, principal) -> new StatisticsJobFixedExecutor(definition, principal, gateway, this::queryAllowed).authorized(),
                    new StatisticsJobResultReceiver(runs, results, gateway, CLOCK, 1));
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        void assertExited() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final Fixture f; int submits; int recoveries; int statuses;
        final List<Integer> pages = new ArrayList<>();
        Map<String, Object> accepted;
        boolean loseAck, recoveryMissing, revokeDuringPage, cancelDuringSubmit;
        Gateway(Fixture f) { this.f = f; }
        int calls() { return submits + recoveries + statuses + pages.size(); }
        public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
        public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy POST"); }
        public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertEquals(PRINCIPAL, context.principal()); assertEquals("session-1", context.sessionId());
            assertEquals("analyst", context.username()); assertEquals(request, context.arguments());
            var child = f.runs.children(f.token).get(0);
            assertEquals(ChildState.DISPATCHING, child.state()); assertTrue(child.callbackActive());
            assertEquals(FrozenCampaignRun.encode(request), child.spec().wire().bodyJson());
            assertEquals(child.spec().requestId(), request.get("requestId"));
            assertTrue(child.spec().requestId().length() <= 96);
            accepted = Map.copyOf(request);
            if (cancelDuringSubmit) f.runs.cancel(f.token);
            if (loseAck) throw new IllegalStateException("Remote accepted but response was lost");
            return ToolResult.success(Map.of("jobId", "job-original", "state", "SUCCEEDED"));
        }
        public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++; assertEquals(accepted, request); assertEquals(PRINCIPAL, context.principal());
            return recoveryMissing ? new ToolResult(false, Map.of("code", "REPLAY_UNAVAILABLE"), "unavailable")
                    : ToolResult.success(Map.of("jobId", "job-original", "state", "SUCCEEDED"));
        }
        public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses++; assertEquals("job-original", job);
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", f.rows,
                    "pageCount", (f.rows + 499) / 500, "expiresAt", CLOCK.millis() + 3_600_000));
        }
        public ToolResult readStatisticsJobPage(ToolContext context, String job, int index, int size) {
            pages.add(index); assertEquals("job-original", job); assertEquals(500, size);
            if (revokeDuringPage) f.allowed.set(false);
            var items = IntStream.range(index * 500, Math.min(f.rows, (index + 1) * 500))
                    .mapToObj(sequence -> Map.of("linkId", 1L, "sequence", sequence)).toList();
            return ToolResult.success(Map.of("items", items, "metrics", Map.of(), "meta", metadata(index, f.rows)));
        }
    }

    private static Map<String, Object> metadata(int index, int rows) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("snapshotId", "job-original"); meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "g1");
        meta.put("linkIds", List.of(1L)); meta.put("groupScopeComplete", true);
        meta.put("requestedStart", LocalDate.parse("2026-09-01").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        meta.put("requestedEnd", LocalDate.parse("2026-09-02").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        meta.put("effectiveEnd", meta.get("requestedEnd")); meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
        meta.put("sourceCut", Map.of("manifestSelectionHash", "hash-1")); meta.put("manifestVersion", Map.of("selectionHash", "hash-1"));
        meta.put("snapshotExpiresAt", CLOCK.millis() + 3_600_000); meta.put("totalRows", rows);
        meta.put("pageIndex", index); meta.put("nextPageIndex", index == 0 && rows > 500 ? 1 : null);
        meta.put("completeness", "PARTIAL"); meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        return meta;
    }

    private static FrozenCampaignRun frozen() {
        var collect = new PlanSpec.Step("collect", List.of("goal"), PlanSpec.ExecutionMode.FIXED, QUERY, null, List.of(),
                Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods"), "query", PlanBinding.input("query")), Map.of(), SCHEMA);
        var consume = new PlanSpec.Step("consume", List.of("goal"), PlanSpec.ExecutionMode.FIXED, CONSUME, null, List.of("collect"),
                Map.of("upstream", PlanBinding.output("collect", "pages")), Map.of(), SCHEMA);
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Read original evidence", true, "Deliver evidence")), List.of(collect, consume));
        var query = Map.<String, Object>of("schemaVersion", FrozenStatisticsJobQuery.SCHEMA, "scopeRef", "scope-g1",
                "periodsRef", "period-september", "scopeKind", "CURRENT_GROUP", "gid", "g1", "queryKind", "ACCESS_RECORDS",
                "startDate", "2026-09-01", "endDate", "2026-09-01", "businessTimezone", "Asia/Shanghai");
        var inputs = new FrozenInputSet("inputs-1", "run-1", FrozenStatisticsJobQuery.INPUTS,
                Map.of("scope", "scope-g1", "periods", "period-september", "query", query));
        var assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())), List.of(new PlanningAssessment.CoverageBinding("delivery",
                        List.of(new PlanningAssessment.EvidenceOutput("consume", "pages")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment);
    }

    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                if (QUERY.equals(ref)) return Optional.of(StatisticsJobFixedExecutor.capability());
                return CONSUME.equals(ref) ? Optional.of(new Capability(CONSUME, new Signature(Map.of("upstream", new Port(PAGES, true)),
                        SCHEMA, Map.of("pages", new Port(PAGES, true)), Parameters.none()), false)) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) {
                return Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(PAGES)));
            }
        };
    }
}
