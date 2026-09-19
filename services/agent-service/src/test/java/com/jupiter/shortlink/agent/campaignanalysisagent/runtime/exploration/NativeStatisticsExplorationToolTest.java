package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.StatisticsJobFixedExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Real native/CALL/child/receiver integration. Only the external model and HTTP gateway are scripted. */
@Timeout(30)
class NativeStatisticsExplorationToolTest {
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z"), EXPIRY = NOW.plusSeconds(3600);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIG = "d".repeat(64), PROMPT = "Read the authorized September access records";
    private static final String SCOPE = "scope-g1", PERIODS = "period-september", JOB = "job-original";
    private static final String RAW_ROW = "RAW_BUSINESS_EVENT_STAYS_IN_RESULT_PAGE";
    private static final PlanSpec.ExecutorRef REF = StatisticsJobFixedExecutor.REF;
    private static final List<PlanSpec.CriterionUse> CRITERIA = List.of(new PlanSpec.CriterionUse("evidence-supported", Map.of()));
    private static final NativeExplorationAdapter.Limits LIMITS = new NativeExplorationAdapter.Limits(
            0, 4096, 4096, 32768, 8, Duration.ofSeconds(5));

    @Test
    void realStatisticsCallReceivesAllPagesAndNewWriterWithEmptyCheckpointContinuesWithoutResubmission() throws Exception {
        var f = new Fixture(Invalid.NONE);
        var model = new ScriptedExplorationChatModel(
                prompt -> statisticsCall(arguments(Invalid.NONE)),
                prompt -> {
                    var receipts = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                            .filter(response -> "statistics-call".equals(response.id()) && REF.name().equals(response.name())).toList();
                    assertEquals(1, receipts.size());
                    assertTrue(receipts.get(0).responseData().contains("PENDING"));
                    assertTrue(receipts.get(0).responseData().contains(JOB));
                    String history = prompt.getInstructions().toString();
                    assertEquals(1, occurrences(history, "trusted_action_observation"));
                    assertTrue(history.contains(f.target.artifactId()));
                    assertFalse(history.contains(RAW_ROW));
                    return ScriptedExplorationChatModel.text("The complete page collection is available; source quality remains partial.");
                });
        StepPermit resumed = null;
        boolean originalExited = false;
        try {
            var initialTool = f.tool(f.token);
            assertEquals("WAITING", f.adapter(f.token, f.step, initialTool, model).invoke(PROMPT).get("status"));
            assertEquals(1, model.callCount()); assertEquals(1, f.gateway.submits);
            ChildRecord waiting = f.asyncChild(f.token);
            assertEquals(ChildState.WAITING, waiting.state()); assertEquals(JOB, waiting.jobId());
            assertFalse(waiting.callbackActive()); assertEquals(1, waiting.attemptVersion());
            assertEquals(0, f.activeCalls()); assertEquals(0, f.count("campaign_artifact"));
            var targets = initialTool.resultTargets(f.token);
            assertEquals(Set.of(waiting.spec().childId()), targets.keySet());
            f.target = targets.get(waiting.spec().childId());
            assertEquals(SCOPE, f.target.scopeRef()); assertEquals(PERIODS, f.target.periodsRef());
            assertEquals(tree(waiting.spec().wire().bodyJson()), JSON.valueToTree(f.gateway.accepted));
            assertEquals(waiting.spec().requestId(), f.gateway.accepted.get("requestId"));
            assertEquals(FrozenStatisticsJobQuery.SUBMIT_PATH, waiting.spec().wire().path());

            var noMoreModel = new ScriptedExplorationChatModel();
            assertEquals("WAITING", f.adapter(f.token, f.step, f.tool(f.token), noMoreModel).invoke(PROMPT).get("status"));
            assertEquals(0, noMoreModel.callCount()); assertEquals(1, f.gateway.submits);
            f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-statistics", f.authorizer);
            f.steps.callbackExited(f.step); originalExited = true;

            RunToken writer = f.steps.acquireRun(f.token);
            var restoredTool = f.tool(writer);
            assertEquals(targets, restoredTool.resultTargets(writer));
            var receiver = new StatisticsJobResultReceiver(new JdbcCampaignRunStore(f.jdbc, f.tx, CLOCK),
                    new JdbcCampaignStatisticsResultStore(f.jdbc, f.tx, CLOCK), f.gateway, CLOCK, 1);
            var first = receiver.receive(writer, waiting.spec().childId(), PRINCIPAL, f.target,
                    () -> restoredTool.reauthorize(writer, waiting.spec().childId()));
            assertEquals(StatisticsJobResultReceiver.Outcome.RECEIVING, first.outcome(), first.code());
            assertEquals(1, first.nextPageIndex()); assertEquals(0, f.count("campaign_artifact"));
            var second = receiver.receive(writer, waiting.spec().childId(), PRINCIPAL, f.target,
                    () -> restoredTool.reauthorize(writer, waiting.spec().childId()));
            assertEquals(StatisticsJobResultReceiver.Outcome.READY, second.outcome(), second.code());
            assertEquals(List.of(0, 1), f.gateway.pages); assertEquals(2, f.gateway.statuses);
            ChildRecord ready = f.runs.child(writer, waiting.spec().childId()).orElseThrow();
            assertEquals(waiting.spec(), ready.spec()); assertEquals(waiting.jobId(), ready.jobId());
            assertEquals(f.target.artifactId(), ready.artifactId());
            Artifact artifact = f.runs.readArtifact(OWNER, ready.artifactId(), f.authorizer);
            assertEquals(CampaignStatisticsResultStore.ARTIFACT_TYPE, artifact.metadata().ref().type());
            assertEquals(waiting.spec().actionId(), artifact.metadata().actionId());
            assertEquals(REF.version(), artifact.metadata().executorVersion());
            JsonNode manifest = tree(artifact.payloadJson());
            assertEquals(501, manifest.path("totalRows").asInt());
            assertEquals(2, manifest.path("receivedPageCount").asInt()); assertTrue(manifest.path("resultComplete").asBoolean());
            assertEquals("PARTIAL", manifest.path("meta").path("completeness").asText());
            assertEquals("UNKNOWN", manifest.path("meta").path("collectionQuality").path("status").asText());
            assertEquals(EXPIRY, artifact.metadata().ref().expiresAt());
            assertEquals(500, tree(f.results.readPage(OWNER, ready.artifactId(), 0, f.authorizer)).path("items").size());
            assertEquals(1, tree(f.results.readPage(OWNER, ready.artifactId(), 1, f.authorizer)).path("items").size());

            assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, "explore").status());
            resumed = f.steps.beginStep(writer, "explore");
            var restored = f.adapter(writer, resumed, f.tool(writer), model); // A fresh ledger and empty native saver.
            var answer = restored.invoke(PROMPT);
            assertEquals("CANDIDATE", answer.get("status")); assertNotEquals("SUCCEEDED", answer.get("status"));
            assertEquals(List.of(ready.artifactId()), answer.get("artifactIds"));
            assertEquals(2, model.callCount()); model.assertExhausted();
            assertEquals(1, f.gateway.submits); assertEquals(0, f.gateway.recoveries);
            assertEquals(1, f.count("campaign_exploration_call")); assertEquals(2, f.count("campaign_model_response"));
            assertEquals(ready, f.runs.child(writer, waiting.spec().childId()).orElseThrow());
            assertEquals("CANDIDATE", restored.invoke(PROMPT).get("status"));
            assertEquals(2, model.callCount()); assertEquals(1, f.gateway.submits);
            assertEquals(List.of(0, 1), f.gateway.pages); assertEquals(2, f.gateway.statuses);
        } finally {
            if (resumed != null) f.steps.callbackExited(resumed);
            if (!originalExited) f.steps.callbackExited(f.step);
        }
        f.assertExited();
    }

    @Test
    void closedBindingsCurrentAuthorityAndModelArtifactVisibilityRejectBeforeAnyStatisticsIo() throws Exception {
        for (Invalid invalid : List.of(Invalid.UNKNOWN_PARAMETER, Invalid.INPUT_OUTSIDE_STEP,
                Invalid.PERIOD_MISMATCH, Invalid.REVOKED, Invalid.INVISIBLE_ARTIFACT)) {
            var f = new Fixture(invalid);
            if (invalid == Invalid.INVISIBLE_ARTIFACT) {
                f.publishHiddenQuery();
                assertEquals("query-hidden", f.contracts.validateArtifact(FrozenStatisticsJobQuery.QUERY_TYPE,
                        "query-hidden", f.runs, OWNER, f.authorizer).metadata().ref().artifactId());
                assertTrue(f.configuration.inputs().isEmpty(), "Current access does not grant visibility to this model turn");
            }
            var model = new ScriptedExplorationChatModel(prompt -> {
                if (invalid == Invalid.REVOKED) f.allowed.set(false); // Revoke after the actual model invocation was admitted.
                return statisticsCall(arguments(invalid));
            });
            try {
                var result = f.adapter(f.token, f.step, f.tool(f.token), model).invoke(PROMPT);
                assertEquals("BLOCKED", result.get("status"), invalid.name());
                assertEquals(1, model.callCount(), invalid.name()); model.assertExhausted();
                assertEquals(1, f.count("campaign_exploration_call"), "The real admitted CALL reaches the guarded tool: " + invalid);
                assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND child_mode='ASYNC'",
                        Integer.class, f.token.definition().runId()), invalid.name());
                assertEquals(0, f.gateway.submits + f.gateway.recoveries + f.gateway.statuses + f.gateway.pages.size(), invalid.name());
                assertFalse(result.toString().contains(RAW_ROW));
            } finally { f.allowed.set(true); f.steps.callbackExited(f.step); }
            f.assertExited();
        }
    }

    private enum Invalid { NONE, UNKNOWN_PARAMETER, INPUT_OUTSIDE_STEP, PERIOD_MISMATCH, REVOKED, INVISIBLE_ARTIFACT }

    private static String arguments(Invalid invalid) {
        Map<String, Object> inputs = new LinkedHashMap<>(Map.of(
                "scope", Map.of("source", "INPUT", "input", "scope-input"),
                "periods", Map.of("source", "INPUT", "input", "period-input"),
                "query", Map.of("source", "INPUT", "input", "query-input")));
        if (invalid == Invalid.INPUT_OUTSIDE_STEP) inputs.put("scope", Map.of("source", "INPUT", "input", "unexposed-scope"));
        if (invalid == Invalid.INVISIBLE_ARTIFACT) inputs.put("query", Map.of("source", "ARTIFACT", "artifactId", "query-hidden"));
        return encode(Map.of("inputBindings", inputs,
                "parameters", invalid == Invalid.UNKNOWN_PARAMETER ? Map.of("gid", "unapproved-group") : Map.of()));
    }

    private static org.springframework.ai.chat.model.ChatResponse statisticsCall(String arguments) {
        return ScriptedExplorationChatModel.toolCalls(new AssistantMessage.ToolCall("statistics-call", "function", REF.name(), arguments));
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static JsonNode tree(String value) {
        try { return JSON.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
    private static int occurrences(String value, String part) {
        int count = 0, offset = 0;
        while ((offset = value.indexOf(part, offset)) >= 0) { count++; offset += part.length(); }
        return count;
    }
    private static Map<String, Object> descriptor(Invalid invalid) {
        return Map.of("schemaVersion", FrozenStatisticsJobQuery.SCHEMA, "scopeRef", SCOPE,
                "periodsRef", invalid == Invalid.PERIOD_MISMATCH ? "different-period" : PERIODS,
                "scopeKind", "CURRENT_GROUP", "gid", "g1", "queryKind", "ACCESS_RECORDS",
                "startDate", "2026-09-01", "endDate", "2026-09-01", "businessTimezone", "Asia/Shanghai");
    }

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignExplorationCallStore calls;
        final CampaignStatisticsResultStore results;
        final RunToken token;
        final StepPermit step;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final ArtifactAuthorizer authorizer = (caller, metadata) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(metadata.owner());
        final Gateway gateway = new Gateway(this);
        final ModelInvocationRegistry models = new ModelInvocationRegistry(List.of(
                new ModelInvocationRegistry.Contract("scripted-model", "1", CONFIG, invocation -> true)));
        final ArtifactContractRegistry contracts = new ArtifactContractRegistry(List.of(StatisticsJobFixedExecutor.artifactContract(),
                new ArtifactContractRegistry.Contract(FrozenStatisticsJobQuery.QUERY_TYPE, "StatisticsJobQuery", FrozenStatisticsJobQuery.SCHEMA,
                        value -> value.isObject() && FrozenStatisticsJobQuery.SCHEMA.equals(value.path("schemaVersion").asText()),
                        (metadata, quality) -> SCOPE.equals(metadata.ref().scopeRef()) && PERIODS.equals(metadata.ref().periodsRef()))));
        final JdbcExplorationLedger.ModelConfiguration configuration;
        StatisticsJobResultReceiver.Target target;

        Fixture(Invalid invalid) {
            var source = new DriverManagerDataSource("jdbc:h2:mem:native_statistics_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_6__campaign_local_calculation.sql"),
                    new ClassPathResource("sql/migration/V20260920_8__campaign_model_invocation.sql"),
                    new ClassPathResource("sql/migration/V20260920_9__campaign_exploration_call.sql"),
                    new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql")).execute(source);
            jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = new JdbcCampaignRunStore(jdbc, tx, CLOCK); steps = new JdbcCampaignStepStore(jdbc, tx, CLOCK);
            calls = new JdbcCampaignExplorationCallStore(jdbc, tx, CLOCK); results = new JdbcCampaignStatisticsResultStore(jdbc, tx, CLOCK);
            var planned = new PlanSpec.Step("explore", List.of("goal"), PlanSpec.ExecutionMode.REACT, null,
                    new PlanSpec.ExplorationPolicy("statistics-explore", "1", List.of(REF), SCOPE, PERIODS, CRITERIA, "fixture-termination"),
                    List.of(), Map.of("scope", PlanBinding.input("scope-input"), "periods", PlanBinding.input("period-input"),
                            "query", PlanBinding.input("query-input")), Map.of(), "analysis-evidence/v1");
            var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "statistics-plan", 1, "statistics-run", "statistics-inputs",
                    List.of(new PlanSpec.Goal("goal", "Inspect access records", true, "Deliver evidence-backed analysis")), List.of(planned));
            Map<String, Port> ports = Map.of("scope-input", FrozenStatisticsJobQuery.INPUTS.get("scope"),
                    "period-input", FrozenStatisticsJobQuery.INPUTS.get("periods"), "query-input", FrozenStatisticsJobQuery.INPUTS.get("query"),
                    "unexposed-scope", new Port(FrozenStatisticsJobQuery.SCOPE_TYPE, true));
            var frozen = FrozenCampaignRun.freeze(plan, new FrozenInputSet("statistics-inputs", "statistics-run", ports,
                    // Even an otherwise authorized equal-valued input is unavailable unless this step exposes its exact ref.
                    Map.of("scope-input", SCOPE, "period-input", PERIODS, "query-input", descriptor(invalid), "unexposed-scope", SCOPE)),
                    new PlanningAssessment("statistics-plan", 1, "fixture/v1", List.of(), List.of(), List.of()));
            token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "statistics-session")));
            steps.initialize(token, List.of(new StepSpec("explore", encode(planned), List.of(), Set.of(), Set.of())));
            step = steps.beginStep(token, "explore");
            var definition = StatisticsExplorationTool.definition();
            configuration = new JdbcExplorationLedger.ModelConfiguration("scripted-model", "1", CONFIG, null,
                    List.of(new ModelInvocationRegistry.ToolDefinition(definition.name(), definition.description(), tree(definition.inputSchema()))),
                    Map.of(), EXPIRY);
        }

        StatisticsExplorationTool tool(RunToken writer) {
            return new StatisticsExplorationTool(jdbc, writer.definition(), "explore", PRINCIPAL, gateway, runs, steps, calls, catalog(),
                    contracts, models, (caller, type, value) -> OWNER.equals(caller) && allowed.get()
                            && (FrozenStatisticsJobQuery.SCOPE_TYPE.equals(type) ? SCOPE.equals(value) : PERIODS.equals(value)),
                    authorizer, (current, scope, periods, request) -> allowed.get() && PRINCIPAL.equals(current)
                            && SCOPE.equals(scope) && PERIODS.equals(periods) && "g1".equals(request.get("gid"))
                            && "ACCESS_RECORDS".equals(request.get("queryKind")) && "2026-09-01".equals(request.get("startDate"))
                            && "2026-09-01".equals(request.get("endDate")));
        }
        NativeExplorationAdapter adapter(RunToken writer, StepPermit permit, StatisticsExplorationTool tool, ScriptedExplorationChatModel model) {
            assertEquals(writer.definition(), permit.runToken().definition());
            var ledger = new JdbcExplorationLedger(jdbc, tx, CLOCK, new JdbcCampaignRunStore(jdbc, tx, CLOCK),
                    new JdbcCampaignStepStore(jdbc, tx, CLOCK), new JdbcCampaignExplorationCallStore(jdbc, tx, CLOCK), permit, models,
                    configuration, Map.of(REF.name(), REF), authorizer);
            return new NativeExplorationAdapter(ledger.identity(), ledger, model, List.of(tool.registration()),
                    new MemorySaver(), Runnable::run, LIMITS, ledger);
        }
        ChildRecord asyncChild(RunToken writer) {
            var children = runs.children(writer).stream().filter(child -> child.spec().mode() == ChildMode.ASYNC).toList();
            assertEquals(1, children.size()); return children.get(0);
        }
        int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        int activeCalls() { return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_exploration_call WHERE callback_active=TRUE", Integer.class); }
        void assertExited() {
            assertEquals(0, activeCalls());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
        void publishHiddenQuery() {
            var source = runs.createRun(new RunDefinition(OWNER, "source-session", "source-run", "source-plan", 1, "{}"));
            runs.prepareAction(source, new ActionSpec("source-action", "source-step", "TOOL", "source-query", "1", "{}"));
            runs.prepareChild(source, new ChildSpec("source-child", "source-action", ChildMode.SYNC, "source-request",
                    new WireRequest("GET", "/fixture/source", "{}")));
            var dispatch = runs.beginDispatch(source, "source-child");
            try { runs.publishReady(dispatch, new ArtifactDraft("query-hidden", "StatisticsJobQuery", FrozenStatisticsJobQuery.SCHEMA,
                    SCOPE, PERIODS, "{}", "{}", EXPIRY, encode(descriptor(Invalid.NONE)))); }
            finally { runs.callbackExited(dispatch); }
        }
    }

    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "fixture/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return REF.equals(ref) ? Optional.of(StatisticsExplorationTool.capability()) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) {
                return "statistics-explore".equals(ref) && "1".equals(version)
                        ? Optional.of(new Policy(ref, version, new Signature(FrozenStatisticsJobQuery.INPUTS,
                                "analysis-evidence/v1", Map.of(), Parameters.none()), Set.of(REF), CRITERIA, "fixture-termination")) : Optional.empty();
            }
            public Optional<Criterion> criterion(String ref, String version) { return Optional.empty(); }
        };
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final Fixture f;
        int submits, recoveries, statuses;
        final List<Integer> pages = new ArrayList<>();
        Map<String, Object> accepted;
        Gateway(Fixture f) { this.f = f; }
        public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
        public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy POST"); }
        public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertEquals(PRINCIPAL, context.principal()); assertEquals("analyst", context.username());
            assertEquals("statistics-session", context.sessionId()); assertEquals(request, context.arguments());
            var child = f.asyncChild(f.token);
            assertEquals(ChildState.DISPATCHING, child.state()); assertTrue(child.callbackActive());
            assertEquals(1, f.activeCalls());
            assertEquals(tree(child.spec().wire().bodyJson()), JSON.valueToTree(request));
            accepted = Map.copyOf(request);
            return ToolResult.success(Map.of("jobId", JOB, "state", "QUEUED"));
        }
        public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++; assertEquals(accepted, request);
            return ToolResult.success(Map.of("jobId", JOB, "state", "SUCCEEDED"));
        }
        public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses++; assertEquals(PRINCIPAL, context.principal()); assertEquals(JOB, job);
            return ToolResult.success(Map.of("jobId", JOB, "state", "SUCCEEDED", "rowCount", 501, "pageCount", 2,
                    "expiresAt", EXPIRY.toEpochMilli()));
        }
        public ToolResult readStatisticsJobPage(ToolContext context, String job, int index, int size) {
            pages.add(index); assertEquals(JOB, job); assertEquals(500, size); assertEquals(PRINCIPAL, context.principal());
            long start = LocalDate.parse("2026-09-01").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            var items = IntStream.range(index * 500, Math.min(501, (index + 1) * 500)).mapToObj(sequence ->
                    Map.of("eventId", RAW_ROW + sequence, "linkId", 1L, "occurredAt", start + sequence)).toList();
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", JOB); meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "g1");
            meta.put("linkIds", List.of(1L)); meta.put("groupScopeComplete", true);
            meta.put("requestedStart", start); meta.put("requestedEnd", start + 86_400_000L); meta.put("effectiveEnd", start + 86_400_000L);
            meta.put("businessTimezone", "Asia/Shanghai"); meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
            meta.put("sourceCut", Map.of("manifestSelectionHash", "hash-1")); meta.put("manifestVersion", Map.of("selectionHash", "hash-1"));
            meta.put("manifestSelectionHash", "hash-1"); meta.put("snapshotCreatedAt", NOW.toEpochMilli());
            meta.put("snapshotExpiresAt", EXPIRY.toEpochMilli()); meta.put("totalRows", 501);
            meta.put("pageIndex", index); meta.put("nextPageIndex", index == 0 ? 1 : null);
            meta.put("availability", "AVAILABLE"); meta.put("freshness", "FRESH"); meta.put("completeness", "PARTIAL");
            meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of("producerCollectionCompleteness"));
            return ToolResult.success(Map.of("items", items, "metrics", Map.of(), "meta", meta));
        }
    }
}
