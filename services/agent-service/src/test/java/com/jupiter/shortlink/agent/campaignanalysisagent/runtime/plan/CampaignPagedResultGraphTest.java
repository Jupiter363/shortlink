package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignPagedResultGraphTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final PlanSpec.ExecutorRef TOOL = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "collect", "1");
    private static final CapabilityCatalog.TypeRef PAGES = new CapabilityCatalog.TypeRef("StatisticsJobPages", 1, CapabilityCatalog.Cardinality.ONE);
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> true;

    @Test
    void stagedPagesDoNotWakeGraphUntilPublishedThenDependencyReadsOriginalAuthorizedPage() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:paged_graph_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
        var steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
        var pages = new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK);
        AtomicInteger downstream = new AtomicInteger();
        AtomicInteger collect = new AtomicInteger();
        var contracts = new ArtifactContractRegistry(List.of(new ArtifactContractRegistry.Contract(PAGES,
                CampaignStatisticsResultStore.ARTIFACT_TYPE, CampaignStatisticsResultStore.SCHEMA_VERSION,
                payload -> payload.path("resultComplete").asBoolean() && payload.path("totalRows").asLong() == 501,
                (metadata, quality) -> "PARTIAL".equals(quality.path("completeness").asText()))));
        var policy = new StepBindings.StepPolicy() {
            public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {}
            public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {}
        };
        var executor = new PersistentPlanDriver.FixedExecutor(TOOL, policy, context -> {
            if (context.step().stepId().equals("collect")) {
                collect.incrementAndGet();
                var receipt = context.child(child(), boundary -> { throw new AssertionError("Known job must not be submitted again"); });
                return PersistentPlanDriver.Result.succeeded(Map.of("pages", receipt.artifactId()));
            }
            downstream.incrementAndGet();
            var input = context.inputs().artifact("upstream");
            assertEquals(501, input.payload().path("totalRows").asInt());
            assertTrue(pages.readPage(OWNER, input.metadata().ref().artifactId(), 1, ALLOW).contains("500"));
            return PersistentPlanDriver.Result.succeeded(Map.of("pages", input.metadata().ref().artifactId()));
        });
        java.util.function.Function<RunToken, PersistentPlanDriver> drivers = token -> new PersistentPlanDriver(token,
                runs, steps, catalog(), contracts, List.of(executor), (caller, inputs) -> true, ALLOW, (caller, type, value) -> true);
        RunToken initial = runs.createRun(frozen().definition(OWNER, "session-1"));
        drivers.apply(initial);
        var activeStep = steps.beginStep(initial, "collect");
        runs.prepareAction(initial, new ActionSpec("action-collect", "collect", "TOOL", TOOL.name(), TOOL.version(),
                steps.step(initial, "collect").orElseThrow().spec().definitionJson()));
        runs.prepareChild(initial, child());
        var dispatch = runs.beginDispatch(initial, "child-collect");
        runs.recordWaiting(dispatch, "job-original");
        runs.callbackExited(dispatch);
        steps.settle(activeStep, CampaignStepStore.StepStatus.WAITING, Map.of(), null, ALLOW);
        steps.callbackExited(activeStep);

        List<Integer> fetched = new ArrayList<>();
        AtomicInteger statuses = new AtomicInteger();
        var gateway = new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
            public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No new submission"); }
            public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) { throw new AssertionError("Known identity needs no recovery"); }
            public ToolResult readStatisticsJob(ToolContext context, String job) {
                assertEquals("job-original", job); statuses.incrementAndGet();
                return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", 501,
                        "pageCount", 2, "expiresAt", CLOCK.millis() + 3_600_000));
            }
            public ToolResult readStatisticsJobPage(ToolContext context, String job, int index, int size) {
                assertEquals("job-original", job); assertEquals(500, size); fetched.add(index);
                var meta = metadata(index);
                var items = IntStream.range(index * 500, Math.min(501, (index + 1) * 500))
                        .mapToObj(sequence -> Map.of("linkId", 1L, "sequence", sequence)).toList();
                return ToolResult.success(Map.of("items", items, "metrics", Map.of(), "meta", meta));
            }
        };
        var owner = new ProcessIdentity(UUID.randomUUID().toString(), "test-domain", 1, 1);
        var takeover = new JdbcCampaignRecoveryStore(jdbc, tx, CLOCK, owner,
                identity -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE));
        var receiver = new StatisticsJobResultReceiver(runs, pages, gateway, CLOCK, 1);
        var coordinator = new CampaignRecoveryCoordinator(takeover, runs, new StatisticsSubmissionReconciler(runs, gateway),
                (token, principal) -> {
                    var driver = drivers.apply(token);
                    return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()),
                            Map.of("child-collect", new StatisticsJobResultReceiver.Target("artifact-pages", "scope-1", "periods-1")));
                }, (definition, principal) -> true, receiver);

        var first = coordinator.resume(initial, PRINCIPAL);
        assertEquals(StatisticsJobResultReceiver.Outcome.RECEIVING, first.receivedResults().get(0).outcome());
        assertEquals(0, first.scan().advancedSteps());
        assertEquals(0, collect.get()); assertEquals(0, downstream.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));

        var second = coordinator.resume(first.token(), PRINCIPAL);
        assertEquals(StatisticsJobResultReceiver.Outcome.READY, second.receivedResults().get(0).outcome());
        assertEquals(2, second.scan().advancedSteps());
        assertEquals(List.of(0, 1), fetched); assertEquals(2, statuses.get());
        assertEquals(1, collect.get()); assertEquals(1, downstream.get());
        steps.steps(second.token()).forEach(step -> assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, step.status()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
        assertThrows(SecurityException.class, () -> pages.readPage(OWNER, "artifact-pages", 0, (caller, metadata) -> false));
    }

    private static Map<String, Object> metadata(int index) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("snapshotId", "job-original"); meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "g1");
        meta.put("linkIds", List.of(1L)); meta.put("groupScopeComplete", true);
        meta.put("requestedStart", LocalDate.parse("2026-09-01").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        meta.put("requestedEnd", LocalDate.parse("2026-09-02").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        meta.put("effectiveEnd", meta.get("requestedEnd")); meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
        meta.put("sourceCut", Map.of("manifestSelectionHash", "hash-1")); meta.put("manifestVersion", Map.of("selectionHash", "hash-1"));
        meta.put("snapshotExpiresAt", CLOCK.millis() + 3_600_000); meta.put("totalRows", 501);
        meta.put("pageIndex", index); meta.put("nextPageIndex", index == 0 ? 1 : null);
        meta.put("completeness", "PARTIAL"); meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        return meta;
    }

    private static ChildSpec child() {
        return new ChildSpec("child-collect", "action-collect", ChildMode.ASYNC, "request-1", new WireRequest("POST",
                "/internal/short-link-admin/v1/agent-tools/statistics/jobs",
                "{\"requestId\":\"request-1\",\"gid\":\"g1\",\"queryKind\":\"ACCESS_RECORDS\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-01\"}"));
    }
    private static FrozenCampaignRun frozen() {
        List<PlanSpec.Step> steps = List.of(new PlanSpec.Step("collect", List.of("goal"), PlanSpec.ExecutionMode.FIXED,
                TOOL, null, List.of(), Map.of(), Map.of(), CampaignStatisticsResultStore.SCHEMA_VERSION),
                new PlanSpec.Step("consume", List.of("goal"), PlanSpec.ExecutionMode.FIXED, TOOL, null, List.of("collect"),
                        Map.of("upstream", new PlanBinding(PlanBinding.Source.STEP_OUTPUT, null, "collect", "pages", null)),
                        Map.of(), CampaignStatisticsResultStore.SCHEMA_VERSION));
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Read original evidence", true, "Deliver evidence")), steps);
        var assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())), List.of(new PlanningAssessment.CoverageBinding("delivery",
                        List.of(new PlanningAssessment.EvidenceOutput("consume", "pages")))), List.of());
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of()), assessment);
    }
    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return TOOL.equals(ref) ? Optional.of(new Capability(TOOL, new Signature(Map.of("upstream", new Port(PAGES, false)),
                        CampaignStatisticsResultStore.SCHEMA_VERSION, Map.of("pages", new Port(PAGES, true)), Parameters.none()), false)) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) {
                return Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(PAGES)));
            }
        };
    }
}
