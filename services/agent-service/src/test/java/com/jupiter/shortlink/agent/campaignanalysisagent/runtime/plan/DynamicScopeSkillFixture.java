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
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Real collection -> selection-v2 -> dimension-v2 runner; authority and statistics responses are scripted. */
final class DynamicScopeSkillFixture {
    static final AgentPrincipal PRINCIPAL = new AgentPrincipal(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), false);
    static final String RUN = "dynamic-scope-skill-run", COLLECT = "collect-scope", SELECT = "select-declines", DIMENSION = "dimension-change", PAIR = "period-pair";
    static final PlanSpec.ExecutorRef SELECT_REF = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "decline_selection", "2");
    static final PlanSpec.ExecutorRef DIMENSION_REF = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "dimension_change", "2");
    static final List<String> DIMENSIONS = List.of("province", "device");
    static final TypeRef SELECTED = new TypeRef("SelectedEntitiesArtifact", 1, Cardinality.ONE);
    static final TypeRef EVIDENCE = new TypeRef("DeclineEvidenceArtifact", 1, Cardinality.ONE);
    static final TypeRef CHANGES = new TypeRef("DimensionChangeArtifact", 1, Cardinality.ONE);
    final Fixture base = new Fixture();
    final CampaignRunStore runs = base.runs;
    final CampaignStatisticsResultStore results = base.results;
    final CampaignStepStore steps;
    final AtomicBoolean allowed = new AtomicBoolean(true);
    final Gateway gateway = new Gateway(this);
    final ProcessIdentity process = new ProcessIdentity(UUID.randomUUID().toString(), "dynamic-scope-skill-test-domain", 1, 1);
    final String mode;
    final List<Long> authorityCursors = new ArrayList<>();
    final int memberCount;
    final Path approvedRoot;
    final RunToken token;

    DynamicScopeSkillFixture(String mode, int memberCount) throws Exception {
        this.mode = mode; this.memberCount = memberCount;
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"),
                new ClassPathResource("sql/migration/V20260920_2__campaign_statistics_release.sql"),
                new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql")).execute(base.jdbc.getDataSource());
        approvedRoot = new ClassPathResource("campaign-skills").getFile().toPath();
        steps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
        token = steps.acquireRun(runs.createRun(frozen().definition(OWNER, "dynamic-scope-skill-session")));
    }

    RunToken current() { return runs.loadRun(OWNER, RUN).orElseThrow().token(); }
    List<ChildRecord> children() { return runs.children(current()); }
    CampaignStepStore.StepRecord step(String id) { return steps.step(current(), id).orElseThrow(); }
    String scopeArtifact() { return step(COLLECT).outputs().get("scopeArtifact"); }
    String scopeRef() { return runs.inspectArtifact(OWNER, scopeArtifact(), artifactAuth()).ref().scopeRef(); }
    ArtifactAuthorizer artifactAuth() {
        return (caller, artifact) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(artifact.owner())
                && RUN.equals(artifact.runId());
    }
    boolean queryAllowed(AgentPrincipal principal, String scope, String periods, Map<String, Object> request) {
        if (!allowed.get() || !PRINCIPAL.equals(principal) || !Set.of("baseline", "target").contains(periods)
                || !"group-a".equals(request.get("gid")) || !(request.get("scope") instanceof Map<?, ?> raw)) return false;
        FrozenQueryScope frozen = FrozenQueryScope.fromMap(raw);
        String scopeRef = scopeRef();
        return scope.equals(frozen.parentScopeRef()) && VERSION.equals(frozen.enumerationVersion())
                && frozen.linkIds().stream().allMatch(id -> id >= 1 && id <= memberCount)
                && ("LINK_METRICS".equals(request.get("queryKind")) && scopeRef.equals(scope)
                    || "DIMENSION_BREAKDOWN".equals(request.get("queryKind")) && !scopeRef.equals(scope)
                        && DIMENSIONS.equals(request.get("dimensions")) && List.of().equals(request.get("filters")));
    }
    DeclineSelectionSkill selection(RunToken run) {
        return new DeclineSelectionSkill(run, PRINCIPAL, approvedRoot, runs, steps,
                new JdbcCampaignScopeStore(base.jdbc, base.transactions, CLOCK, runs), results,
                new JdbcCampaignDeclineSelectionStore(base.jdbc, base.transactions, CLOCK, runs), gateway,
                this::queryAllowed, artifactAuth(), SELECT_REF);
    }
    DimensionChangeSkill dimension(RunToken run) {
        return new DimensionChangeSkill(run, PRINCIPAL, approvedRoot, runs, steps,
                new JdbcCampaignDeclineSelectionStore(base.jdbc, base.transactions, CLOCK, runs), results,
                gateway, this::queryAllowed, artifactAuth(), DIMENSION_REF);
    }
    ScopeCollectionFixedExecutor collection(RunToken run) {
        var scopes = new JdbcCampaignScopeStore(base.jdbc, base.transactions, CLOCK, runs);
        var collector = new CampaignScopeCollector(runs, scopes, (principal, gid, after, version) -> {
            authorityCursors.add(after);
            assertEquals(PRINCIPAL, principal); assertEquals("group-a", gid);
            assertEquals(after == null ? null : VERSION, version);
            var child = children().stream().filter(ChildRecord::callbackActive).findFirst().orElseThrow();
            assertEquals(ChildMode.SYNC, child.spec().mode()); assertEquals(ChildState.DISPATCHING, child.state());
            long first = after == null ? 1 : after + 1, last = Math.min(memberCount, first + 499);
            return new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), gid,
                    VERSION, after, LongStream.rangeClosed(first, last).boxed().toList(), last < memberCount ? last : null);
        }, 1);
        return new ScopeCollectionFixedExecutor(run, PRINCIPAL, runs, steps, scopes, collector,
                (principal, gid) -> allowed.get() && PRINCIPAL.equals(principal) && "group-a".equals(gid), artifactAuth());
    }
    CampaignRecoveryCoordinator.Runtime runtime(RunToken run) throws Exception {
        var collect = collection(run); var first = selection(run); var second = dimension(run);
        collect.prepareRecovery(); first.prepareRecovery(); second.prepareRecovery();
        var contracts = new ArrayList<ArtifactContractRegistry.Contract>();
        contracts.add(ScopeCollectionFixedExecutor.artifactContract());
        contracts.addAll(DeclineSelectionSkill.artifactContracts().stream().filter(contract -> !"ScopeArtifact".equals(contract.type().name())).toList());
        contracts.addAll(DimensionChangeSkill.artifactContracts());
        var driver = new PersistentPlanDriver(run, runs, steps, catalog(), new ArtifactContractRegistry(contracts),
                List.of(collect.registration(), first.registration(), second.registration()),
                (caller, inputs) -> OWNER.equals(caller) && collect.authorized() && first.authorized() && second.authorized(), artifactAuth(),
                (caller, type, value) -> OWNER.equals(caller) && allowed.get()
                        && "PeriodsRef".equals(type.name()) && PAIR.equals(value));
        Map<String, StatisticsJobResultReceiver.Target> targets = new LinkedHashMap<>(first.resultTargets());
        second.resultTargets().forEach((id, target) -> assertNull(targets.put(id, target), "Each child has exactly one receiver target"));
        return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()), targets, artifactAuth());
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
        while (step(DIMENSION).status() != CampaignStepStore.StepStatus.SUCCEEDED && scans < 12) {
            var result = coordinator().resume(current(), PRINCIPAL);
            assertNotEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, result.outcome(), result.reason());
            scans++;
        }
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, step(SELECT).status());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, step(COLLECT).status());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, step(DIMENSION).status());
        return scans;
    }
    int artifacts(String type) {
        return base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE run_id=? AND artifact_type=?", Integer.class, RUN, type);
    }
    void exited() {
        assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
        assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
    }

    private FrozenCampaignRun frozen() throws Exception {
        Map<String, Object> selection = definition("decline-selection"), dimension = definition("dimension-change");
        dimension.put("dimensions", DIMENSIONS); dimension.put("filters", List.of());
        if ("WRONG_PERIODS".equals(mode)) dimension.put("target", Map.of("periodsRef", "target", "startDate", "2026-09-03",
                "endDate", "2026-09-03", "timeZone", "Asia/Shanghai"));
        var collect = new PlanSpec.Step(COLLECT, List.of("goal"), PlanSpec.ExecutionMode.FIXED, FrozenScopeCollection.REF, null, List.of(),
                Map.of("definition", PlanBinding.input("collectionDefinition")), Map.of(), "campaign-scope/v1");
        var select = new PlanSpec.Step(SELECT, List.of("goal"), PlanSpec.ExecutionMode.FIXED, SELECT_REF, null, List.of(COLLECT),
                Map.of("scopeArtifact", PlanBinding.output(COLLECT, "scopeArtifact"),
                        "periods", PlanBinding.input("periods"), "definition", PlanBinding.input("selectionDefinition")),
                Map.of("metric", "PV"), "campaign.decline-selection/v1");
        var drill = new PlanSpec.Step(DIMENSION, List.of("goal"), PlanSpec.ExecutionMode.FIXED, DIMENSION_REF, null, List.of(SELECT),
                Map.of("periods", PlanBinding.input("periods"),
                        "definition", PlanBinding.input("dimensionDefinition"),
                        "selectedEntities", PlanBinding.output(SELECT, "selectedEntities"),
                        "selectionEvidence", PlanBinding.output(SELECT, "selectionEvidence")), Map.of(), "campaign.dimension-change/v1");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "dynamic-plan", 1, RUN, "dynamic-inputs",
                List.of(new PlanSpec.Goal("goal", "Collect scope, find declines and examine province/device changes", true, "Deliver observed changes with source quality")),
                List.of(collect, select, drill));
        var inputs = new FrozenInputSet("dynamic-inputs", RUN, Map.of(
                "collectionDefinition", new Port(new TypeRef("ScopeCollectionDefinition", 1, Cardinality.ONE), true),
                "periods", new Port(new TypeRef("PeriodsRef", 1, Cardinality.ONE), true),
                "selectionDefinition", new Port(new TypeRef("DeclineSelectionDefinition", 2, Cardinality.ONE), true),
                "dimensionDefinition", new Port(new TypeRef("DimensionChangeDefinition", 2, Cardinality.ONE), true)),
                Map.of("collectionDefinition", Map.of("schemaVersion", "scope-collection-definition/v1", "gid", "group-a",
                        "expiresAt", Instant.ofEpochMilli(EXPIRY).toString()),
                        "periods", PAIR, "selectionDefinition", selection, "dimensionDefinition", dimension));
        var assessment = new PlanningAssessment("dynamic-plan", 1, "dynamic-catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(SELECT, "selectedEntities"),
                        new PlanningAssessment.EvidenceOutput(SELECT, "selectionEvidence"), new PlanningAssessment.EvidenceOutput(DIMENSION, "dimensionChanges")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment);
    }
    private Map<String, Object> definition(String name) throws Exception {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("schemaVersion", name + "-definition/v2");
        definition.put("periodsRef", PAIR); definition.put("gid", "group-a");
        definition.put("baseline", Map.of("periodsRef", "baseline", "startDate", "2026-09-01", "endDate", "2026-09-01", "timeZone", "Asia/Shanghai"));
        definition.put("target", Map.of("periodsRef", "target", "startDate", "2026-09-02", "endDate", "2026-09-02", "timeZone", "Asia/Shanghai"));
        String digest = RunPinnedSkills.contentDigest(new SkillScanner().loadSkill(approvedRoot.resolve(name + "/2"), "backend-test"));
        definition.put("skillPin", Map.of("name", name, "version", "2", "relativeDirectory", name + "/2", "sha256", digest));
        return definition;
    }
    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "dynamic-catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                if (FrozenScopeCollection.REF.equals(ref)) return Optional.of(ScopeCollectionFixedExecutor.capability());
                if (SELECT_REF.equals(ref)) return Optional.of(DeclineSelectionSkill.capability(ref));
                return DIMENSION_REF.equals(ref) ? Optional.of(DimensionChangeSkill.capability(ref)) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) { return Optional.of(new Criterion(ref, version,
                    PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(SELECTED, EVIDENCE, CHANGES))); }
        };
    }

    static final class Gateway implements ShortLinkBusinessGateway {
        final DynamicScopeSkillFixture f;
        final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        final Map<String, Integer> polls = new HashMap<>();
        final Set<String> released = new HashSet<>();
        int submits, recoveries, statuses, releases, pageReads;
        Gateway(DynamicScopeSkillFixture f) { this.f = f; }
        int calls() { return submits + recoveries + statuses + releases + pageReads; }
        long submitted(String kind) { return accepted.values().stream().filter(request -> kind.equals(request.get("queryKind"))).count(); }
        public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy GET"); }
        public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No legacy POST"); }
        public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> query) { throw new AssertionError("No mutable current-group submission"); }
        public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits++; assertEquals(PRINCIPAL, context.principal()); assertEquals("dynamic-scope-skill-session", context.sessionId());
            assertEquals(request, context.arguments());
            var child = f.children().stream().filter(value -> value.spec().requestId().equals(request.get("requestId"))).findFirst().orElseThrow();
            assertEquals(ChildState.DISPATCHING, child.state()); assertTrue(child.callbackActive());
            assertEquals(FrozenCampaignRun.encode(request), child.spec().wire().bodyJson());
            assertEquals(StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, child.spec().wire().path());
            assertTrue(accepted.values().stream().noneMatch(previous -> previous.get("requestId").equals(request.get("requestId"))), "A known job cannot be resubmitted");
            String job = "dimension-job-" + (accepted.size() + 1); accepted.put(job, Map.copyOf(request));
            return ToolResult.success(Map.of("jobId", job, "state", "QUEUED"));
        }
        public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++;
            var known = accepted.entrySet().stream().filter(entry -> entry.getValue().equals(request)).findFirst().orElseThrow();
            return ToolResult.success(status(known.getKey()));
        }
        public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses++; assertEquals(PRINCIPAL, context.principal());
            if (polls.merge(job, 1, Integer::sum) == 1) return ToolResult.success(Map.of("jobId", job, "state", "RUNNING"));
            return ToolResult.success(status(job));
        }
        public ToolResult releaseStatisticsJobResult(ToolContext context, String job, Map<String, Object> request) {
            releases++; assertEquals(FrozenCampaignRun.encode(accepted.get(job)), FrozenCampaignRun.encode(request));
            var child = f.children().stream().filter(value -> job.equals(value.jobId())).findFirst().orElseThrow();
            assertEquals(ChildState.READY, child.state()); assertNotNull(f.runs.readArtifact(OWNER, child.artifactId(), f.artifactAuth()));
            assertTrue(released.add(job), "Confirmed result releases are not repeated");
            return ToolResult.success(status(job));
        }
        private Map<String, Object> status(String job) {
            int rows = "LINK_METRICS".equals(accepted.get(job).get("queryKind")) ? scope(job).linkIds().size() : 1;
            var value = new LinkedHashMap<String, Object>(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", rows, "pageCount", 1,
                    "expiresAt", EXPIRY, "resultState", released.contains(job) ? "RELEASED" : "AVAILABLE", "resultReady", !released.contains(job)));
            if (released.contains(job)) value.put("resultCode", "RESULT_RELEASED"); return value;
        }
        FrozenQueryScope scope(String job) { return FrozenQueryScope.fromMap((Map<?, ?>) accepted.get(job).get("scope")); }
        public ToolResult readStatisticsJobPage(ToolContext context, String job, int page, int size) {
            pageReads++; assertEquals(0, page); assertEquals(500, size); assertFalse(released.contains(job));
            var request = accepted.get(job); var scope = scope(job);
            boolean baseline = "2026-09-01".equals(request.get("startDate"));
            boolean dimension = "DIMENSION_BREAKDOWN".equals(request.get("queryKind"));
            long start = date(request.get("startDate").toString()), end = date(request.get("endDate").toString()) + 86_400_000;
            List<Map<String, Object>> rows = new ArrayList<>(); long total = scope.linkIds().size() * (baseline ? 2L : 1L);
            if (dimension) {
                rows.add(Map.of("dimensions", Map.of("province", Map.of("state", "KNOWN", "value", "浙江"),
                                "device", Map.of("state", "KNOWN", "value", "Mobile")), "pv", total, "uv", 1, "uip", 1, "pvRatio", 1.0));
            } else for (long id : scope.linkIds()) {
                var row = new LinkedHashMap<>(counts(baseline ? 2 : 1, start, end));
                row.put("linkId", id); rows.add(row);
            }
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", job); meta.put("queryKind", request.get("queryKind")); meta.put("gid", "group-a");
            meta.put("linkIds", scope.linkIds()); meta.put("scopeProof", scope.proof("b".repeat(64))); meta.put("groupScopeComplete", false);
            meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
            String hash = CampaignRunStore.sha256(job);
            meta.put("sourceCut", Map.of("manifestSelectionHash", hash)); meta.put("manifestVersion", Map.of("selectionHash", hash));
            meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
            meta.put("requestedStart", start); meta.put("requestedEnd", end); meta.put("effectiveEnd", end); meta.put("businessTimezone", "Asia/Shanghai");
            meta.put("pageIndex", 0); meta.put("nextPageIndex", null); meta.put("totalRows", rows.size());
            meta.put("aggregationLevel", dimension ? "DIMENSION_BREAKDOWN" : "LINK_WINDOW");
            meta.put("availability", "AVAILABLE"); meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"),
                    "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            Map<String, Object> summary = new LinkedHashMap<>(counts(total, start, end));
            if (dimension) {
                summary.remove("denied"); summary.put("ratioDenominator", total);
                Map<String, Object> quality = Map.of("province", quality(total, "CN_PROVINCE"), "device", quality(total, "DEVICE"));
                summary.put("dimensionQuality", quality); meta.put("dimensionQuality", quality);
                meta.put("dimensions", DIMENSIONS); meta.put("filters", List.of()); meta.put("dimensionQualityScope", "FILTERED_FULL_WINDOW");
                meta.put("resultComplete", true); meta.put("truncated", false);
            }
            return ToolResult.success(Map.of("items", rows, "metrics", Map.of("requested", summary), "meta", meta));
        }
    }
    private static Map<String, Object> quality(long total, String semantic) {
        return Map.of("status", "AVAILABLE", "knownCount", total, "unknownCount", 0, "eligibleCount", total,
                "notApplicableCount", 0, "coverage", 1.0, "semantic", semantic, "reasonCounts", Map.of());
    }
    private static Map<String, Object> counts(long pv, long start, long end) {
        return Map.of("pv", pv, "uv", pv == 0 ? 0 : 1, "uip", pv == 0 ? 0 : 1, "denied", 0,
                "window", "requested", "startInclusive", start, "endExclusive", end);
    }
    private static long date(String value) { return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
}
