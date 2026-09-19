package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Nine real fixed steps and native scans; the remote retained-result limit and time are controlled. */
@Timeout(60)
class StatisticsCapacityContinuationTest {
    private static final Instant START = Instant.parse("2026-09-20T00:00:00Z");
    private static final long EXPIRES = START.plusSeconds(3600).toEpochMilli();
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final String SCHEMA = CampaignStatisticsResultStore.SCHEMA_VERSION;
    private static final String MEMBER_HASH = FrozenQueryScope.memberHash(List.of(1L, 2L));
    private static final FrozenQueryScope SCOPE = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-g1",
            MEMBER_HASH, 2, "a".repeat(64), FrozenQueryScope.shardIdFor("scope-g1", 0, MEMBER_HASH),
            0, 1, MEMBER_HASH, List.of(1L, 2L));

    @Test
    void nineFrozenJobsReleaseTheFirstEightAndResumeOnlyTheOriginalDeferredRequestAfterItsDurableDeadline() throws Exception {
        Fixture f = Fixture.create();
        var initial = f.runtime(f.token).graph().advance();
        assertTrue(initial.scanCompleted());
        assertEquals(9, initial.advancedSteps());
        assertEquals(9, f.gateway.submits);
        assertEquals(8, f.gateway.jobs.size());
        assertEquals(8, f.gateway.retained());
        assertEquals(8, f.gateway.maximumRetained);
        assertEquals(0, f.gateway.pages); assertEquals(0, f.gateway.releases);
        var deferred = f.runs.children(f.token).stream().filter(child -> child.jobId() == null).findFirst().orElseThrow();
        assertEquals(ChildState.PREPARED, deferred.state());
        assertEquals(UnresolvedReason.QUERY_CAPACITY_EXHAUSTED, deferred.reason());
        assertFalse(deferred.callbackActive());
        assertFalse(f.runs.submissionDue(f.token, deferred.spec().childId()));
        var originalDeferral = f.runs.submissionDeferral(f.token, deferred.spec().childId()).orElseThrow();
        assertEquals(CapacityKind.RESULT_STORAGE, originalDeferral.kind());
        assertEquals(1, originalDeferral.rejectedAttempts());
        assertEquals(START.toEpochMilli() + 1000, originalDeferral.retryNotBeforeMillis());
        assertEquals(f.gateway.rejectedRequestId, deferred.spec().requestId());
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, f.steps.step(f.token, "collect-9").orElseThrow().status());
        assertEquals("REMOTE_CAPACITY", f.steps.step(f.token, "collect-9").orElseThrow().reason());
        Map<String, ChildSpec> frozenChildren = f.childSpecs();
        f.assertExited();

        var firstReception = f.resume();
        assertEquals(8, firstReception.receivedResults().size());
        assertTrue(firstReception.receivedResults().stream().allMatch(result -> result.outcome() == StatisticsJobResultReceiver.Outcome.READY));
        assertEquals(8, firstReception.releasedResults().stream()
                .filter(result -> result.outcome() == StatisticsJobResultReleaser.Outcome.CONFIRMED).count());
        assertEquals(8, firstReception.scan().advancedSteps());
        assertEquals(8, f.gateway.pages); assertEquals(8, f.gateway.releases);
        assertEquals(0, f.gateway.retained());
        assertEquals(9, f.gateway.submits); assertEquals(0, f.gateway.recoveries);
        assertEquals(8, f.steps.steps(f.token).stream().filter(step -> step.status() == CampaignStepStore.StepStatus.SUCCEEDED).count());
        Map<String, Evidence> firstEight = f.evidence();
        assertEquals(8, firstEight.size());
        int callsAfterRelease = f.gateway.calls();

        // Repeated callers and reconstructed local objects cannot erase the durable backoff.
        f = f.reopen();
        assertEquals(0, f.resume().scan().advancedSteps());
        assertEquals(0, f.resume().scan().advancedSteps());
        f.clock.advanceMillis(999);
        f = f.reopen();
        assertFalse(f.runs.submissionDue(f.token, deferred.spec().childId()));
        assertEquals(0, f.resume().scan().advancedSteps());
        assertEquals(callsAfterRelease, f.gateway.calls(), "Before the deadline there is no submit, recover, status, page or release retry");
        assertEquals(originalDeferral, f.runs.submissionDeferral(f.token, deferred.spec().childId()).orElseThrow());
        assertEquals(frozenChildren, f.childSpecs());
        assertEquals(firstEight, f.evidence());
        f.assertExited();

        f.clock.advanceMillis(1);
        f = f.reopen();
        assertTrue(f.runs.submissionDue(f.token, deferred.spec().childId()));
        var retried = f.resume();
        assertEquals(1, retried.scan().advancedSteps());
        assertEquals(10, f.gateway.submits);
        assertEquals(9, f.gateway.jobs.size());
        assertEquals(2, f.gateway.attempts.get(deferred.spec().requestId()).intValue());
        assertEquals(CampaignStepStore.StepStatus.WAITING, f.steps.step(f.token, "collect-9").orElseThrow().status());
        var originalChild = f.runs.child(f.token, deferred.spec().childId()).orElseThrow();
        assertEquals(deferred.spec(), originalChild.spec(), "Retry must retain child/action/request identity and byte-exact wire hash/body");
        assertEquals(f.gateway.requestBodies.get(deferred.spec().requestId()), originalChild.spec().wire().bodyJson());
        assertEquals(8, f.gateway.pages, "The newly acknowledged ninth job is received in the next coordinator pass");
        assertEquals(8, f.gateway.releases);

        f = f.reopen();
        var ninthReception = f.resume();
        assertEquals(1, ninthReception.receivedResults().size());
        assertEquals(StatisticsJobResultReceiver.Outcome.READY, ninthReception.receivedResults().get(0).outcome());
        assertEquals(1, ninthReception.scan().advancedSteps());
        assertEquals(9, f.gateway.pages); assertEquals(9, f.gateway.releases);
        assertEquals(0, f.gateway.retained()); assertEquals(8, f.gateway.maximumRetained);
        assertEquals(0, f.gateway.recoveries);
        assertEquals(9, f.runs.children(f.token).size());
        assertEquals(frozenChildren, f.childSpecs());
        assertTrue(f.runs.children(f.token).stream().allMatch(child -> child.state() == ChildState.READY));
        assertTrue(f.steps.steps(f.token).stream().allMatch(step -> step.status() == CampaignStepStore.StepStatus.SUCCEEDED));
        for (var job : f.gateway.jobs.values()) {
            assertEquals(job.requestId.equals(deferred.spec().requestId()) ? 2 : 1, f.gateway.attempts.get(job.requestId).intValue());
            assertEquals(1, job.pages); assertEquals(1, job.releases);
        }
        Map<String, Evidence> allEvidence = f.evidence();
        assertEquals(9, allEvidence.size());
        firstEight.forEach((id, evidence) -> assertEquals(evidence, allEvidence.get(id)));
        assertEquals(9, f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_release WHERE release_state='CONFIRMED'", Integer.class));
        int completedCalls = f.gateway.calls();
        f = f.reopen();
        assertEquals(0, f.resume().scan().advancedSteps());
        assertEquals(completedCalls, f.gateway.calls());
        assertEquals(allEvidence, f.evidence());
        assertEquals(RunStatus.ACTIVE, f.runs.loadRun(OWNER, "run-1").orElseThrow().status(), "Execution is not goal assessment");
        f.assertExited();
    }

    private record Evidence(Artifact artifact, String page) {}

    private static final class Fixture {
        final JdbcTemplate jdbc;
        final TransactionTemplate tx;
        final MutableClock clock;
        final Gateway gateway;
        final CampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignStatisticsResultStore results;
        RunToken token;
        private Fixture(JdbcTemplate jdbc, TransactionTemplate tx, MutableClock clock, Gateway gateway, RunToken token) {
            this.jdbc = jdbc; this.tx = tx; this.clock = clock; this.gateway = gateway;
            runs = new JdbcCampaignRunStore(jdbc, tx, clock);
            steps = new JdbcCampaignStepStore(jdbc, tx, clock);
            results = new JdbcCampaignStatisticsResultStore(jdbc, tx, clock);
            this.token = token == null ? steps.acquireRun(runs.createRun(frozen().definition(OWNER, "session-1"))) : token;
        }
        static Fixture create() {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:capacity_continuation_" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920__campaign_statistics_result.sql"),
                    new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql"),
                    new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql")).execute(ds);
            var jdbc = new JdbcTemplate(ds);
            return new Fixture(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), new MutableClock(), new Gateway(jdbc), null);
        }
        Fixture reopen() { return new Fixture(jdbc, tx, clock, gateway, token); }
        CampaignRecoveryCoordinator.Runtime runtime(RunToken run) throws Exception {
            var adapter = new StatisticsJobFixedExecutor(run.definition(), PRINCIPAL, gateway, StatisticsCapacityContinuationTest::queryAllowed);
            var driver = new PersistentPlanDriver(run, runs, steps, catalog(),
                    new ArtifactContractRegistry(List.of(StatisticsJobFixedExecutor.artifactContract())), List.of(adapter.registration()),
                    (caller, inputs) -> OWNER.equals(caller) && adapter.authorized(), artifactAuth(),
                    (caller, type, value) -> OWNER.equals(caller) && (type.equals(FrozenStatisticsJobQuery.SCOPE_TYPE)
                            ? SCOPE.parentScopeRef().equals(value) : type.equals(FrozenStatisticsJobQuery.PERIODS_TYPE) && validPeriods(value)));
            return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()), adapter.resultTargets(), artifactAuth());
        }
        CampaignRecoveryCoordinator.ResumeResult resume() throws Exception {
            var recovery = new JdbcCampaignRecoveryStore(jdbc, tx, clock,
                    new ProcessIdentity(UUID.randomUUID().toString(), "test-domain", 1, 1),
                    ignored -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE));
            var coordinator = new CampaignRecoveryCoordinator(recovery, runs, new StatisticsSubmissionReconciler(runs, gateway),
                    (run, principal) -> runtime(run),
                    (definition, principal) -> new StatisticsJobFixedExecutor(definition, principal, gateway, StatisticsCapacityContinuationTest::queryAllowed).authorized(),
                    new StatisticsJobResultReceiver(runs, results, gateway, clock, 1),
                    new StatisticsJobResultReleaser(runs, new JdbcCampaignStatisticsReleaseStore(jdbc, tx, clock), gateway));
            var result = coordinator.resume(token, PRINCIPAL);
            assertEquals(CampaignRecoveryCoordinator.Outcome.SCANNED, result.outcome(), result.reason());
            token = result.token();
            return result;
        }
        Map<String, ChildSpec> childSpecs() {
            Map<String, ChildSpec> specs = new LinkedHashMap<>();
            runs.children(token).forEach(child -> specs.put(child.spec().childId(), child.spec()));
            return specs;
        }
        Map<String, Evidence> evidence() {
            Map<String, Evidence> evidence = new LinkedHashMap<>();
            for (var child : runs.children(token)) if (child.state() == ChildState.READY) {
                Artifact artifact = runs.readArtifact(OWNER, child.artifactId(), artifactAuth());
                assertEquals(EXPIRES, artifact.metadata().ref().expiresAt().toEpochMilli());
                assertEquals(SCOPE.parentScopeRef(), artifact.metadata().ref().scopeRef());
                assertTrue(artifact.metadata().qualityJson().contains("PARTIAL"));
                assertTrue(artifact.metadata().qualityJson().contains("UNKNOWN"));
                evidence.put(child.artifactId(), new Evidence(artifact, results.readPage(OWNER, child.artifactId(), 0, artifactAuth())));
            }
            return evidence;
        }
        void assertExited() {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static ArtifactAuthorizer artifactAuth() {
        return (caller, metadata) -> OWNER.equals(caller) && OWNER.equals(metadata.owner())
                && "run-1".equals(metadata.runId()) && SCOPE.parentScopeRef().equals(metadata.ref().scopeRef())
                && validPeriods(metadata.ref().periodsRef());
    }
    private static boolean validPeriods(Object value) { return value instanceof String text && text.matches("period-[1-9]"); }
    private static boolean queryAllowed(AgentPrincipal principal, String scope, String periods, Map<String, Object> request) {
        if (!PRINCIPAL.equals(principal) || !SCOPE.parentScopeRef().equals(scope) || !validPeriods(periods)) return false;
        String day = date(Integer.parseInt(periods.substring("period-".length())));
        return "g1".equals(request.get("gid")) && "ACCESS_RECORDS".equals(request.get("queryKind"))
                && day.equals(request.get("startDate")) && day.equals(request.get("endDate"))
                && FrozenCampaignRun.encode(SCOPE.asMap()).equals(FrozenCampaignRun.encode(request.get("scope")));
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final JdbcTemplate jdbc;
        final Map<String, Job> jobs = new LinkedHashMap<>();
        final Map<String, Integer> attempts = new LinkedHashMap<>();
        final Map<String, String> requestBodies = new LinkedHashMap<>();
        int submits, statuses, pages, releases, recoveries, maximumRetained;
        String rejectedRequestId;
        Gateway(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        int retained() { return (int) jobs.values().stream().filter(job -> !job.released).count(); }
        int calls() { return submits + statuses + pages + releases + recoveries; }
        @Override public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertPrincipal(context);
            String requestId = (String) request.get("requestId");
            String body = FrozenCampaignRun.encode(request);
            String previous = requestBodies.putIfAbsent(requestId, body);
            if (previous != null) assertEquals(previous, body, "Capacity retry preserves the original serialized request");
            attempts.merge(requestId, 1, Integer::sum);
            var row = jdbc.queryForMap("SELECT child_state,callback_active,wire_path,wire_body,wire_hash FROM campaign_child_ledger WHERE request_id=?", requestId);
            assertEquals("DISPATCHING", row.get("child_state")); assertEquals(Boolean.TRUE, row.get("callback_active"));
            assertEquals(FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH, row.get("wire_path"));
            assertEquals(body, jdbc.queryForObject("SELECT wire_body FROM campaign_child_ledger WHERE request_id=?", String.class, requestId));
            assertEquals(request, context.arguments());
            assertEquals(new WireRequest("POST", FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH, body).hash(), row.get("wire_hash"));
            assertTrue(jobs.values().stream().noneMatch(job -> requestId.equals(job.requestId)), "An admitted request must never submit again");
            if (retained() == 8) {
                assertNull(rejectedRequestId, "Only the ninth request should initially exhaust retained-result capacity");
                rejectedRequestId = requestId;
                return new ToolResult(false, Map.of("code", "QUERY_CAPACITY_EXHAUSTED", "admitted", false, "capacityKind", "RESULT_STORAGE"), null);
            }
            Job job = new Job("job-" + (jobs.size() + 1), requestId, Map.copyOf(request));
            jobs.put(job.id, job); maximumRetained = Math.max(maximumRetained, retained());
            assertTrue(retained() <= 8);
            return ToolResult.success(status(job));
        }
        @Override public ToolResult readStatisticsJob(ToolContext context, String jobId) {
            assertPrincipal(context); statuses++;
            return ToolResult.success(status(requireJob(jobId)));
        }
        @Override public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int index, int size) {
            assertPrincipal(context); Job job = requireJob(jobId);
            assertFalse(job.released); assertEquals(0, index, "Zero-row jobs still require page zero"); assertEquals(500, size);
            pages++; job.pages++;
            var meta = new LinkedHashMap<String, Object>();
            meta.put("queryKind", "ACCESS_RECORDS"); meta.put("gid", "g1"); meta.put("linkIds", SCOPE.linkIds());
            meta.put("snapshotId", job.id); meta.put("recoveryEpoch", "epoch-1"); meta.put("metricVersion", "click-v1");
            meta.put("sourceCut", Map.of("manifestSelectionHash", "manifest-" + job.id));
            meta.put("manifestVersion", Map.of("selectionHash", "manifest-" + job.id));
            LocalDate start = LocalDate.parse((String) job.request.get("startDate"));
            meta.put("requestedStart", midnight(start)); meta.put("requestedEnd", midnight(start.plusDays(1)));
            meta.put("effectiveEnd", meta.get("requestedEnd")); meta.put("businessTimezone", "Asia/Shanghai");
            meta.put("snapshotExpiresAt", EXPIRES); meta.put("totalRows", 0L);
            meta.put("pageIndex", 0); meta.put("nextPageIndex", null); meta.put("groupScopeComplete", false);
            meta.put("scopeProof", SCOPE.proof("b".repeat(64))); meta.put("completeness", "PARTIAL");
            meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
            return ToolResult.success(Map.of("items", List.of(), "metrics", Map.of(), "meta", meta));
        }
        @Override public ToolResult releaseStatisticsJobResult(ToolContext context, String jobId, Map<String, Object> original) {
            assertPrincipal(context); Job job = requireJob(jobId);
            assertFalse(job.released); assertEquals(requestBodies.get(job.requestId), FrozenCampaignRun.encode(original));
            assertEquals("READY", jdbc.queryForObject("SELECT child_state FROM campaign_child_ledger WHERE request_id=?", String.class, job.requestId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM campaign_statistics_release WHERE job_id=? AND release_state='REQUESTED'", Integer.class, jobId));
            assertEquals(1, job.pages, "Remote storage is released only after its page is durably received");
            releases++; job.releases++; job.released = true;
            return ToolResult.success(status(job));
        }
        @Override public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++; throw new AssertionError("A trusted admitted=false result must not reconcile an unknown submission");
        }
        @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++; throw new AssertionError("No current-group recovery fallback");
        }
        @Override public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) { throw new AssertionError("No current-group submission fallback"); }
        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No generic GET"); }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> body) { throw new AssertionError("No generic POST"); }
        private Job requireJob(String id) { return Objects.requireNonNull(jobs.get(id), "Original admitted job is required"); }
        private static void assertPrincipal(ToolContext context) { assertEquals(PRINCIPAL, context.principal()); assertEquals("session-1", context.sessionId()); }
        private static Map<String, Object> status(Job job) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("jobId", job.id); status.put("state", "SUCCEEDED"); status.put("rowCount", 0L);
            status.put("pageCount", 0); status.put("expiresAt", EXPIRES);
            status.put("resultState", job.released ? "RELEASED" : "AVAILABLE"); status.put("resultReady", !job.released);
            status.put("resultCode", job.released ? "RESULT_RELEASED" : null);
            return status;
        }
    }

    private static final class Job {
        final String id, requestId;
        final Map<String, Object> request;
        boolean released;
        int pages, releases;
        Job(String id, String requestId, Map<String, Object> request) { this.id = id; this.requestId = requestId; this.request = request; }
    }
    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(START.toEpochMilli());
        void advanceMillis(long delta) { millis.addAndGet(delta); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC fixture"); return this; }
        @Override public long millis() { return millis.get(); }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    }
    private static long midnight(LocalDate day) { return day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    private static String date(int index) { return LocalDate.of(2026, 9, index).toString(); }

    private static FrozenCampaignRun frozen() {
        List<PlanSpec.Step> steps = new ArrayList<>();
        Map<String, Port> contracts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        List<PlanningAssessment.EvidenceOutput> outputs = new ArrayList<>();
        contracts.put("scope", FrozenStatisticsJobQuery.INPUTS.get("scope")); values.put("scope", SCOPE.parentScopeRef());
        for (int index = 1; index <= 9; index++) {
            String id = "collect-" + index, periods = "period-" + index, query = "query-" + index;
            steps.add(new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED, StatisticsJobFixedExecutor.REF,
                    null, List.of(), Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input(periods),
                    "query", PlanBinding.input(query)), Map.of(), SCHEMA));
            contracts.put(periods, FrozenStatisticsJobQuery.INPUTS.get("periods")); values.put(periods, periods);
            contracts.put(query, FrozenStatisticsJobQuery.INPUTS.get("query"));
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("schemaVersion", FrozenStatisticsJobQuery.SCHEMA); descriptor.put("scopeRef", SCOPE.parentScopeRef());
            descriptor.put("periodsRef", periods); descriptor.put("scopeKind", "FROZEN_SET"); descriptor.put("scope", SCOPE.asMap());
            descriptor.put("gid", "g1"); descriptor.put("queryKind", "ACCESS_RECORDS");
            descriptor.put("startDate", date(index)); descriptor.put("endDate", date(index)); descriptor.put("businessTimezone", "Asia/Shanghai");
            values.put(query, descriptor); outputs.add(new PlanningAssessment.EvidenceOutput(id, "pages"));
        }
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Read nine independent frozen daily windows", true, "Deliver all nine evidence sets")), steps);
        var assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", outputs)), List.of());
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs-1", "run-1", contracts, values), assessment);
    }
    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return StatisticsJobFixedExecutor.REF.equals(ref) ? Optional.of(StatisticsJobFixedExecutor.capability()) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) {
                return Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY,
                        Parameters.none(), Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE)));
            }
        };
    }
}
