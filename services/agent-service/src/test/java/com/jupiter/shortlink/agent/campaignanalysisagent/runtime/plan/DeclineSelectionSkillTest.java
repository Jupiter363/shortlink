package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.skills.RunPinnedSkills;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real native PlanGraph, Skill, JDBC ledgers and receiver/releaser; only remote jobs are scripted. */
@Timeout(45)
class DeclineSelectionSkillTest {
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), false);
    private static final String RUN = "selection-skill-run", STEP = "select-declines", PERIOD_PAIR = "period-pair";
    private static final TypeRef SELECTED = new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE);
    private static final TypeRef EVIDENCE = new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE);

    @Test
    void nativeSkillWaitsReceivesReleasesAndPublishesAll501CandidatesWithoutResubmission() throws Exception {
        SkillFixture f = new SkillFixture(null);
        var initial = f.runtime(f.token);
        assertEquals(1, initial.graph().advance().advancedSteps());
        assertEquals(CampaignStepStore.StepStatus.WAITING, f.step().status());
        assertTrue(f.gateway.submits > 0 && f.gateway.submits <= 2);
        assertEquals(0, f.gateway.statuses);
        assertEquals(0, f.finalCount());
        ChildRecord firstRequest = f.children().stream().filter(child -> child.spec().mode() == ChildMode.ASYNC).findFirst().orElseThrow();
        int resumes = f.finish();
        assertTrue(resumes >= 2, "The two frozen shards require separate durable waiting/reception passes");
        assertEquals(4, f.gateway.submits);
        assertEquals(0, f.gateway.recoveries);
        assertEquals(4, f.gateway.releases);
        assertEquals(4, f.gateway.accepted.size());
        assertEquals(4, f.gateway.pageReads);
        assertEquals(2, f.finalCount());
        assertEquals(firstRequest.spec(), f.children().stream().filter(child -> child.spec().childId().equals(firstRequest.spec().childId())).findFirst().orElseThrow().spec());
        assertTrue(f.children().stream().filter(child -> child.spec().mode() == ChildMode.LOCAL)
                .allMatch(child -> child.state() == ChildState.READY && child.attemptVersion() == 1));
        assertEquals(3, f.children().stream().filter(child -> child.spec().mode() == ChildMode.LOCAL).count());
        Map<String, String> outputs = f.step().outputs();
        assertEquals(Set.of("selectedEntities", "selectionEvidence"), outputs.keySet());
        var index = new JdbcCampaignDeclineSelectionStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs);
        var first = index.readSelectedPage(OWNER, outputs.get("selectedEntities"), null, 250, f.artifactAuth());
        assertEquals(501, first.rows().get(0).linkId());
        assertEquals(BigInteger.valueOf(-100), first.rows().get(0).delta());
        var second = index.readSelectedPage(OWNER, outputs.get("selectedEntities"), first.nextCursor(), 250, f.artifactAuth());
        assertNull(second.nextCursor());
        var selected = new ArrayList<>(first.rows()); selected.addAll(second.rows());
        assertEquals(500, selected.size());
        assertEquals(LongStream.rangeClosed(2, 500).boxed().toList(), selected.subList(1, 500).stream().map(CampaignLinkComparability.Result::linkId).toList());
        var evidence = index.readEvidencePage(OWNER, outputs.get("selectionEvidence"), null, 500, f.artifactAuth());
        assertEquals(500, evidence.rows().size());
        var evidenceLast = index.readEvidencePage(OWNER, outputs.get("selectionEvidence"), evidence.nextCursor(), 500, f.artifactAuth());
        assertEquals(1, evidenceLast.rows().size()); assertNull(evidenceLast.nextCursor());
        assertTrue(evidence.rows().stream().allMatch(row -> row.explanation() == CampaignLinkComparability.Explanation.OBSERVED_ONLY
                && row.reasonCodes().contains("BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED")
                && row.reasonCodes().contains("TARGET_COLLECTION_COMPLETENESS_UNVERIFIED")));
        Artifact original = f.runs.readArtifact(OWNER, outputs.get("selectedEntities"), f.artifactAuth());
        assertEquals(Instant.ofEpochMilli(EXPIRY), original.metadata().ref().expiresAt());
        assertTrue(original.metadata().qualityJson().contains("UNVERIFIED"));
        for (var child : f.children()) if (child.spec().mode() == ChildMode.ASYNC) {
            assertEquals(ChildState.READY, child.state());
            assertEquals("UNKNOWN", JSON.readTree(f.results.readPage(OWNER, child.artifactId(), 0, f.artifactAuth()))
                    .path("meta").path("collectionQuality").path("status").asText());
        }
        int calls = f.gateway.calls();
        var repeated = f.coordinator().resume(f.current(), PRINCIPAL);
        assertEquals(0, repeated.scan().advancedSteps());
        assertEquals(calls, f.gateway.calls());
        assertEquals(original, f.runs.readArtifact(OWNER, outputs.get("selectedEntities"), f.artifactAuth()));
        f.exited();
    }

    @Test
    void pinsScopeAndCurrentAuthorityFenceIoWhileUnknownLocalResultReplaysFromFrozenInputs() throws Exception {
        for (String fault : List.of("PIN", "SCOPE")) {
            SkillFixture f = new SkillFixture(fault);
            try { f.runtime(f.token).graph().advance(); }
            catch (IllegalArgumentException | IllegalStateException | SecurityException rejected) { /* A constructor or dispatch guard may reject. */ }
            assertEquals(0, f.gateway.calls(), fault);
            assertEquals(0, f.finalCount(), fault);
            assertTrue(f.children().isEmpty(), fault);
            f.exited();
        }
        for (String fault : List.of("CANCEL", "REVOKE")) {
            SkillFixture f = new SkillFixture(null);
            f.gateway.cancelDuringSubmit = "CANCEL".equals(fault);
            f.gateway.revokeDuringPage = "REVOKE".equals(fault);
            f.runtime(f.token).graph().advance();
            if ("REVOKE".equals(fault)) {
                var stopped = f.coordinator().resume(f.current(), PRINCIPAL);
                assertEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, stopped.outcome());
            } else assertEquals(RunStatus.CANCELLED, f.runs.loadRun(OWNER, RUN).orElseThrow().status());
            assertEquals(0, f.finalCount());
            assertTrue(f.children().stream().noneMatch(child -> child.spec().mode() == ChildMode.LOCAL));
            f.exited();
        }
        SkillFixture interrupted = new SkillFixture(null);
        interrupted.runtime(interrupted.token).graph().advance();
        interrupted.base.jdbc.execute("ALTER TABLE campaign_artifact ADD CONSTRAINT fail_local_page CHECK (artifact_id NOT LIKE 'decline-page-%')");
        interrupted.coordinator().resume(interrupted.current(), PRINCIPAL);
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, interrupted.step().status());
        ChildRecord unknown = interrupted.children().stream().filter(child -> child.spec().mode() == ChildMode.LOCAL).findFirst().orElseThrow();
        assertEquals(ChildState.UNRESOLVED, unknown.state());
        assertEquals(UnresolvedReason.LOCAL_RESULT_UNKNOWN, unknown.reason());
        assertEquals(1, unknown.attemptVersion());
        assertEquals(0, interrupted.finalCount());
        int acceptedBeforeReplay = interrupted.gateway.submits;
        interrupted.base.jdbc.execute("ALTER TABLE campaign_artifact DROP CONSTRAINT fail_local_page");
        interrupted.finish();
        assertEquals(2, acceptedBeforeReplay);
        assertEquals(4, interrupted.gateway.submits, "Only the remaining shard's two jobs may be submitted");
        assertEquals(0, interrupted.gateway.recoveries);
        var recovered = interrupted.children().stream().filter(child -> child.spec().childId().equals(unknown.spec().childId())).findFirst().orElseThrow();
        assertEquals(unknown.spec(), recovered.spec());
        assertEquals(2, recovered.attemptVersion());
        assertEquals(ChildState.READY, recovered.state());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, interrupted.step().status());
        assertEquals(2, interrupted.finalCount());
        interrupted.exited();
    }

    private static final class SkillFixture {
        // Scope enumeration is already durably complete. This test does not claim to plan or execute that upstream node.
        final Fixture base = new Fixture();
        final CampaignRunStore runs = base.runs;
        final CampaignStepStore steps;
        final CampaignStatisticsResultStore results = base.results;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final Gateway gateway = new Gateway(this);
        final String scopeArtifact, scopeRef;
        final Path approvedRoot;
        final RunToken token;
        final ProcessIdentity process = new ProcessIdentity(UUID.randomUUID().toString(), "skill-test-domain", 1, 1);

        SkillFixture(String fault) throws Exception {
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                    new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql"),
                    new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql")).execute(base.jdbc.getDataSource());
            scopeArtifact = base.publishScope(501);
            scopeRef = runs.inspectArtifact(OWNER, scopeArtifact, ALLOW).ref().scopeRef();
            approvedRoot = new ClassPathResource("campaign-skills").getFile().toPath();
            String digest = RunPinnedSkills.contentDigest(new SkillScanner().loadSkill(approvedRoot.resolve("decline-selection/1"), "backend-test"));
            steps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
            token = steps.acquireRun(runs.createRun(frozen(this, fault, digest).definition(OWNER, "skill-session")));
        }

        RunToken current() { return runs.loadRun(OWNER, RUN).orElseThrow().token(); }
        List<ChildRecord> children() { return runs.children(current()); }
        CampaignStepStore.StepRecord step() { return steps.step(current(), STEP).orElseThrow(); }
        ArtifactAuthorizer artifactAuth() {
            return (caller, artifact) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(artifact.owner())
                    && scopeRef.equals(artifact.ref().scopeRef());
        }
        boolean queryAllowed(AgentPrincipal principal, String scope, String periods, Map<String, Object> request) {
            return allowed.get() && PRINCIPAL.equals(principal) && scopeRef.equals(scope)
                    && Set.of("baseline", "target").contains(periods) && "group-a".equals(request.get("gid"))
                    && "LINK_METRICS".equals(request.get("queryKind")) && request.get("scope") instanceof Map<?, ?>;
        }
        DeclineSelectionSkill adapter(RunToken run, AgentPrincipal principal) {
            return new DeclineSelectionSkill(run, principal, approvedRoot, runs, steps,
                    new JdbcCampaignScopeStore(base.jdbc, base.transactions, CLOCK, runs), results,
                    new JdbcCampaignDeclineSelectionStore(base.jdbc, base.transactions, CLOCK, runs), gateway,
                    this::queryAllowed, artifactAuth());
        }
        CampaignRecoveryCoordinator.Runtime runtime(RunToken run) throws Exception {
            var skill = adapter(run, PRINCIPAL);
            skill.prepareRecovery();
            var driver = new PersistentPlanDriver(run, runs, steps, catalog(),
                    new ArtifactContractRegistry(DeclineSelectionSkill.artifactContracts()), List.of(skill.registration()),
                    (caller, inputs) -> OWNER.equals(caller) && skill.authorized(), artifactAuth(),
                    (caller, type, value) -> OWNER.equals(caller) && allowed.get()
                            && (type.name().equals("ScopeRef") ? scopeRef.equals(value) : PERIOD_PAIR.equals(value)));
            return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()), skill.resultTargets(), artifactAuth());
        }
        CampaignRecoveryCoordinator coordinator() {
            var recovery = new JdbcCampaignRecoveryStore(base.jdbc, base.transactions, CLOCK, process,
                    identity -> new ProcessLiveness.Observation(ProcessLiveness.State.ALIVE, ProcessLiveness.PROCESS_ALIVE));
            return new CampaignRecoveryCoordinator(recovery, runs, new StatisticsSubmissionReconciler(runs, gateway),
                    (run, principal) -> runtime(run), (definition, principal) -> allowed.get() && PRINCIPAL.equals(principal),
                    new StatisticsJobResultReceiver(runs, results, gateway, CLOCK, 1),
                    new StatisticsJobResultReleaser(runs, new JdbcCampaignStatisticsReleaseStore(base.jdbc, base.transactions, CLOCK), gateway));
        }
        int finish() throws Exception {
            int scans = 0;
            while (step().status() != CampaignStepStore.StepStatus.SUCCEEDED && scans < 8) {
                var result = coordinator().resume(current(), PRINCIPAL);
                assertNotEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, result.outcome(), result.reason());
                scans++;
            }
            assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, step().status()); return scans;
        }
        int finalCount() {
            return base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id=? AND artifact_type IN (?,?)",
                    Integer.class, RUN, DeclineSelectionPublisher.SELECTED_TYPE, DeclineSelectionPublisher.EVIDENCE_TYPE);
        }
        void exited() {
            assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final SkillFixture f;
        final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        final Set<String> released = new HashSet<>();
        int submits, recoveries, statuses, releases, pageReads;
        boolean cancelDuringSubmit, revokeDuringPage;
        Gateway(SkillFixture fixture) { f = fixture; }
        int calls() { return submits + recoveries + statuses + releases + pageReads; }
        public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
        public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy POST"); }
        public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> query) { throw new AssertionError("Frozen job cannot use current-group submission"); }
        public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertEquals(PRINCIPAL, context.principal()); assertEquals("skill-session", context.sessionId());
            assertEquals(request, context.arguments());
            var child = f.children().stream().filter(value -> value.spec().requestId().equals(request.get("requestId"))).findFirst().orElseThrow();
            assertEquals(ChildState.DISPATCHING, child.state()); assertTrue(child.callbackActive());
            assertEquals(FrozenCampaignRun.encode(request), child.spec().wire().bodyJson());
            assertEquals(StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, child.spec().wire().path());
            assertEquals("LINK_METRICS", request.get("queryKind"));
            String job = "job-" + (accepted.size() + 1);
            assertTrue(accepted.values().stream().noneMatch(previous -> previous.get("requestId").equals(request.get("requestId"))), "A known job must never be submitted twice");
            accepted.put(job, Map.copyOf(request));
            if (cancelDuringSubmit) f.runs.cancel(f.current());
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED"));
        }
        public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++;
            var known = accepted.entrySet().stream().filter(entry -> entry.getValue().equals(request)).findFirst().orElseThrow();
            return ToolResult.success(Map.of("jobId", known.getKey(), "state", "SUCCEEDED"));
        }
        public ToolResult readStatisticsJob(ToolContext context, String job) { statuses++; return ToolResult.success(status(job)); }
        public ToolResult releaseStatisticsJobResult(ToolContext context, String job, Map<String, Object> request) {
            releases++; assertEquals(FrozenCampaignRun.encode(accepted.get(job)), FrozenCampaignRun.encode(request));
            var child = f.children().stream().filter(value -> job.equals(value.jobId())).findFirst().orElseThrow();
            assertEquals(ChildState.READY, child.state());
            assertNotNull(f.runs.readArtifact(OWNER, child.artifactId(), f.artifactAuth()));
            assertTrue(released.add(job), "A confirmed release is reused locally");
            return ToolResult.success(status(job));
        }
        private Map<String, Object> status(String job) {
            var scope = scope(job);
            var value = new LinkedHashMap<String, Object>(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", scope.linkIds().size(),
                    "pageCount", 1, "expiresAt", EXPIRY, "resultState", released.contains(job) ? "RELEASED" : "AVAILABLE", "resultReady", !released.contains(job)));
            if (released.contains(job)) value.put("resultCode", "RESULT_RELEASED"); return value;
        }
        private FrozenQueryScope scope(String job) { return FrozenQueryScope.fromMap((Map<?, ?>) accepted.get(job).get("scope")); }
        public ToolResult readStatisticsJobPage(ToolContext context, String job, int page, int size) {
            pageReads++; assertEquals(0, page); assertEquals(500, size); assertFalse(released.contains(job));
            var scope = scope(job); var request = accepted.get(job);
            boolean baseline = request.get("startDate").equals("2026-09-01");
            long start = date(request.get("startDate").toString()), end = date(request.get("endDate").toString()) + 86_400_000;
            List<Map<String, Object>> rows = new ArrayList<>(); long total = 0;
            for (long id : scope.linkIds()) {
                long pv = id == 1 ? 0 : id == 501 ? baseline ? 100 : 0 : baseline ? 2 : 1; total += pv;
                var row = new LinkedHashMap<>(counts(pv, start, end)); row.put("linkId", id); rows.add(row);
            }
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", job); meta.put("queryKind", "LINK_METRICS"); meta.put("gid", "group-a");
            meta.put("linkIds", scope.linkIds()); meta.put("scopeProof", scope.proof("b".repeat(64))); meta.put("groupScopeComplete", false);
            meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
            String hash = CampaignRunStore.sha256(job);
            meta.put("sourceCut", Map.of("manifestSelectionHash", hash)); meta.put("manifestVersion", Map.of("selectionHash", hash));
            meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
            meta.put("requestedStart", start); meta.put("requestedEnd", end); meta.put("effectiveEnd", end); meta.put("businessTimezone", "Asia/Shanghai");
            meta.put("pageIndex", 0); meta.put("nextPageIndex", null); meta.put("totalRows", rows.size()); meta.put("aggregationLevel", "LINK_WINDOW");
            meta.put("availability", "AVAILABLE"); meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"), "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            if (revokeDuringPage) f.allowed.set(false);
            return ToolResult.success(Map.of("items", rows, "metrics", Map.of("requested", counts(total, start, end)), "meta", meta));
        }
    }

    private static Map<String, Object> counts(long pv, long start, long end) {
        return Map.of("pv", pv, "uv", pv == 0 ? 0 : 1, "uip", pv == 0 ? 0 : 1, "denied", 0,
                "window", "requested", "startInclusive", start, "endExclusive", end);
    }
    private static long date(String value) { return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }

    private static FrozenCampaignRun frozen(SkillFixture f, String fault, String digest) {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("schemaVersion", "decline-selection-definition/v1");
        definition.put("scopeRef", "SCOPE".equals(fault) ? "different-frozen-scope" : f.scopeRef);
        definition.put("periodsRef", PERIOD_PAIR); definition.put("gid", "group-a");
        definition.put("baseline", Map.of("periodsRef", "baseline", "startDate", "2026-09-01", "endDate", "2026-09-01", "timeZone", "Asia/Shanghai"));
        definition.put("target", Map.of("periodsRef", "target", "startDate", "2026-09-02", "endDate", "2026-09-02", "timeZone", "Asia/Shanghai"));
        definition.put("skillPin", Map.of("name", "decline-selection", "version", "1", "relativeDirectory", "decline-selection/1", "sha256", "PIN".equals(fault) ? "0".repeat(64) : digest));
        var step = new PlanSpec.Step(STEP, List.of("goal"), PlanSpec.ExecutionMode.FIXED, FrozenDeclineSelection.REF, null, List.of(),
                Map.of("scope", PlanBinding.input("scope"), "scopeArtifact", PlanBinding.artifact(f.scopeArtifact),
                        "periods", PlanBinding.input("periods"), "definition", PlanBinding.input("definition")), Map.of("metric", "PV"), "campaign.decline-selection/v1");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "selection-plan", 1, RUN, "selection-inputs",
                List.of(new PlanSpec.Goal("goal", "Find observed declines across the frozen group", true, "Deliver selected members and evidence")), List.of(step));
        var inputs = new FrozenInputSet("selection-inputs", RUN, Map.of(
                "scope", new Port(new TypeRef("ScopeRef", 1, Cardinality.ONE), true),
                "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
                "definition", new Port(new TypeRef("DeclineSelectionDefinition", 1, Cardinality.ONE), true)),
                Map.of("scope", f.scopeRef, "periods", PERIOD_PAIR, "definition", definition));
        var assessment = new PlanningAssessment("selection-plan", 1, "selection-catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(STEP, "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput(STEP, "selectionEvidence")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment);
    }
    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "selection-catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) { return FrozenDeclineSelection.REF.equals(ref) ? Optional.of(DeclineSelectionSkill.capability()) : Optional.empty(); }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) { return Optional.of(new Criterion(ref, version,
                    PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(SELECTED, EVIDENCE))); }
        };
    }
}
