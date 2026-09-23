package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignDependencyAnalysisPlanFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Artifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** The opt-in production profile, real ledgers and native Graph; only remote I/O is scripted. */
@Timeout(60)
class CampaignDependencyAnalysisRuntimeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);
    private static final String SESSION = "dependency-analysis-session";
    private static final String VERSION = "a".repeat(64);
    private static final long EXPIRY = CLOCK.millis() + 3_600_000;
    private static final List<String> DIMENSIONS = List.of("province", "device");

    @Test
    void frozenMembersFeedDeclineSelectionThenOnlySelectedLinksFeedDimensionAnalysis() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:dependency_runtime_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                migration("V20260919__campaign_run_ledger.sql"),
                migration("V20260919_2__campaign_step_ledger.sql"),
                migration("V20260919_3__campaign_run_owner.sql"),
                migration("V20260920__campaign_statistics_result.sql"),
                migration("V20260920_2__campaign_statistics_release.sql"),
                migration("V20260920_3__campaign_submission_deferral.sql"),
                migration("V20260920_5__campaign_scope_collection.sql"),
                migration("V20260920_6__campaign_local_calculation.sql"),
                migration("V20260920_7__campaign_decline_selection.sql"),
                migration("V20260920_17__campaign_run_intake.sql"),
                migration("V20260923__campaign_conversation_session_owner.sql"),
                migration("V20260923_2__campaign_advance_outcome.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var live = new AtomicBoolean(true);
        var authority = authority(live);
        var gateway = new Gateway();
        try (var runtime = new CampaignStatisticsFixedRuntime(jdbc, tx, CLOCK, authority, gateway,
                new MemorySaver(), "dependency-runtime-test-domain", new ProcessCapacityExecutor.Limits(1, 1, 1, 2),
                new ClassPathResource("campaign-skills").getFile().toPath())) {
            gateway.runtime = runtime;
            runtime.principals().bindCurrent(PRINCIPAL, SESSION);
            var request = new Request("group-a", "2026-09-01", "2026-09-01",
                    "2026-09-02", "2026-09-02", "PV", DIMENSIONS, List.of());
            var prepared = runtime.dependencyPlans().prepare(OWNER, SESSION, "dependency-request", request,
                    Instant.ofEpochMilli(EXPIRY));
            var reference = runtime.intake().register(PRINCIPAL, SESSION, "dependency-request",
                    CampaignDependencyAnalysisProfile.PROFILE_REF,
                    CampaignDependencyAnalysisProfile.PROFILE_VERSION, prepared.definition());
            gateway.runId = reference.runId();
            assertThat(gateway.calls()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class)).isZero();

            boolean complete = false;
            for (int advance = 0; advance < 12 && !complete; advance++) {
                runtime.intake().submit(PRINCIPAL, reference).get(15, TimeUnit.SECONDS);
                var token = runtime.runs().loadRun(OWNER, reference.runId()).orElseThrow().token();
                complete = runtime.steps().step(token, DIMENSION)
                        .map(step -> step.status() == StepStatus.SUCCEEDED).orElse(false);
            }
            assertThat(complete).as("The original dependency plan finishes within bounded advances").isTrue();
            var token = runtime.runs().loadRun(OWNER, reference.runId()).orElseThrow().token();
            for (String stepId : List.of(COLLECT, SELECT, DIMENSION))
                assertThat(runtime.steps().step(token, stepId).orElseThrow().status()).isEqualTo(StepStatus.SUCCEEDED);
            assertThat(gateway.accepted).hasSize(4);
            assertThat(gateway.submissions).isEqualTo(4);
            assertThat(gateway.pageReads).isEqualTo(4);
            assertThat(gateway.releases).isEqualTo(4);
            assertThat(gateway.recoveries).isZero();

            String scopeId = runtime.steps().step(token, COLLECT).orElseThrow().outputs().get("scopeArtifact");
            Artifact scope = runtime.runs().readArtifact(OWNER, scopeId, runtime.dependencyArtifactAuthorizer());
            var scopeBody = JSON.readTree(scope.payloadJson());
            assertThat(scopeBody.path("memberCount").asInt()).isEqualTo(2);
            assertThat(scopeBody.path("enumerationVersion").asText()).isEqualTo(VERSION);
            String parentScopeRef = scope.metadata().ref().scopeRef();
            for (String kind : List.of("LINK_METRICS", "DIMENSION_BREAKDOWN")) {
                var scopes = gateway.accepted.values().stream().filter(value -> kind.equals(value.get("queryKind")))
                        .map(value -> FrozenQueryScope.fromMap((Map<?, ?>) value.get("scope"))).toList();
                assertThat(scopes).hasSize(2);
                for (var frozen : scopes) {
                    assertThat(frozen.enumerationVersion()).isEqualTo(VERSION);
                    if ("LINK_METRICS".equals(kind)) {
                        assertThat(frozen.parentScopeRef()).isEqualTo(parentScopeRef);
                        assertThat(frozen.linkIds()).containsExactly(1L, 2L);
                    } else {
                        assertThat(frozen.parentScopeRef()).isNotEqualTo(parentScopeRef);
                        assertThat(frozen.linkIds()).containsExactly(1L);
                    }
                }
            }

            var selectionOutputs = runtime.steps().step(token, SELECT).orElseThrow().outputs();
            var selections = new JdbcCampaignDeclineSelectionStore(jdbc, tx, CLOCK, runtime.runs());
            var pair = selections.inspectPair(OWNER, selectionOutputs.get("selectedEntities"),
                    selectionOutputs.get("selectionEvidence"), runtime.dependencyArtifactAuthorizer());
            assertThat(pair.producerStepId()).isEqualTo(SELECT);
            assertThat(pair.scopeArtifact().ref().artifactId()).isEqualTo(scopeId);
            assertThat(pair.selectionComplete()).isTrue();
            assertThat(pair.selectedCount()).isEqualTo(1);
            var selected = selections.readSelectedPage(OWNER, selectionOutputs.get("selectedEntities"), null, 10,
                    runtime.dependencyArtifactAuthorizer());
            assertThat(selected.rows()).hasSize(1);
            assertThat(selected.rows().get(0).linkId()).isEqualTo(1);
            assertThat(selected.rows().get(0).baseline()).isEqualTo(8);
            assertThat(selected.rows().get(0).target()).isEqualTo(2);

            Artifact result = runtime.runs().readArtifact(OWNER,
                    runtime.steps().step(token, DIMENSION).orElseThrow().outputs().get("dimensionChanges"),
                    runtime.dependencyArtifactAuthorizer());
            var manifest = JSON.readTree(result.payloadJson());
            assertThat(manifest.path("evidenceDisposition").asText()).isEqualTo("OBSERVED");
            assertThat(manifest.path("memberCount").asInt()).isEqualTo(1);
            assertThat(manifest.path("coveredCohorts").asInt()).isEqualTo(1);
            assertThat(manifest.path("pageCount").asInt()).isEqualTo(2);
            assertThat(manifest.path("comparisonRows").asInt()).isEqualTo(1);
            assertThat(manifest.path("groupScopeComplete").asBoolean()).isFalse();
            int pages = 0;
            String pageId = manifest.path("headArtifactId").asText();
            String pageHash = manifest.path("headPayloadHash").asText();
            while (pageId != null) {
                Artifact page = runtime.runs().readArtifact(OWNER, pageId, runtime.dependencyArtifactAuthorizer());
                assertThat(page.metadata().ref().payloadHash()).isEqualTo(pageHash);
                var payload = JSON.readTree(page.payloadJson());
                assertThat(payload.path("cohortMemberCount").asInt()).isEqualTo(1);
                assertThat(payload.path("cohortSummary").path("baseline").path("pv").asLong()).isEqualTo(8);
                assertThat(payload.path("cohortSummary").path("target").path("pv").asLong()).isEqualTo(2);
                assertThat(payload.path("interpretation").asText()).isEqualTo("OBSERVED_ONLY");
                for (String side : List.of("baseline", "target")) {
                    Artifact original = runtime.runs().readArtifact(OWNER, payload.path(side + "ArtifactId").asText(),
                            runtime.dependencyArtifactAuthorizer());
                    assertThat(original.metadata().ref().payloadHash()).isEqualTo(payload.path(side + "PayloadHash").asText());
                    assertThat(original.metadata().ref().scopeRef()).isEqualTo(result.metadata().ref().scopeRef());
                }
                assertThat(++pages).isLessThanOrEqualTo(2);
                pageId = payload.path("previousArtifactId").isNull() ? null : payload.path("previousArtifactId").asText();
                pageHash = payload.path("previousPayloadHash").isNull() ? null : payload.path("previousPayloadHash").asText();
            }
            assertThat(pages).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class)).isZero();
            int calls = gateway.calls();
            runtime.intake().submit(PRINCIPAL, reference).get(15, TimeUnit.SECONDS);
            assertThat(gateway.calls()).isEqualTo(calls);
            live.set(false);
            assertThat(runtime.dependencyArtifactAuthorizer().mayRead(OWNER, result.metadata())).isFalse();
            assertThatThrownBy(() -> runtime.runs().readArtifact(OWNER, result.metadata().ref().artifactId(),
                    runtime.dependencyArtifactAuthorizer())).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> runtime.intake().submit(PRINCIPAL, reference)).isInstanceOf(SecurityException.class);
            assertThat(gateway.calls()).isEqualTo(calls);
        }
    }

    private static AgentAuthorityClient authority(AtomicBoolean live) {
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(any(AgentPrincipal.class))).thenAnswer(call -> {
            if (!live.get() || !PRINCIPAL.equals(call.getArgument(0))) throw new SecurityException("REVOKED");
            return PRINCIPAL;
        });
        when(authority.resolveGroupMembersPage(any(AgentPrincipal.class), anyString(), isNull(), isNull()))
                .thenAnswer(call -> {
                    assertThat(call.<AgentPrincipal>getArgument(0)).isEqualTo(PRINCIPAL);
                    assertThat(call.<String>getArgument(1)).isEqualTo("group-a");
                    if (!live.get()) throw new SecurityException("REVOKED");
                    return new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.subject(),
                            OWNER.authVersion(), "group-a", VERSION, null, List.of(1L, 2L), null);
                });
        when(authority.resolvePage(any(AgentPrincipal.class), anyString(), isNull(), any(), isNull(), anyString()))
                .thenAnswer(call -> {
                    assertThat(call.<AgentPrincipal>getArgument(0)).isEqualTo(PRINCIPAL);
                    assertThat(call.<String>getArgument(1)).isEqualTo("group-a");
                    assertThat(call.<String>getArgument(5)).isEqualTo(VERSION);
                    if (!live.get()) throw new SecurityException("REVOKED");
                    List<Long> ids = call.getArgument(3);
                    assertThat(ids).isNotEmpty().allMatch(id -> id == 1 || id == 2);
                    return new AgentAuthorityClient.AuthorizedScope(OWNER.tenantId(), VERSION,
                            ids.stream().<Map<String, Object>>map(id -> Map.of("linkId", id, "gid", "group-a",
                                    "fullShortUrl", "https://s.example/" + id)).toList(), null);
                });
        return authority;
    }

    private static ClassPathResource migration(String name) { return new ClassPathResource("sql/migration/" + name); }

    private static final class Gateway implements ShortLinkBusinessGateway {
        CampaignStatisticsFixedRuntime runtime;
        String runId;
        final Map<String, Map<String, Object>> accepted = new LinkedHashMap<>();
        final Map<String, Integer> polls = new HashMap<>();
        final Set<String> released = new HashSet<>();
        int submissions, recoveries, statuses, releases, pageReads;
        int calls() { return submissions + recoveries + statuses + releases + pageReads; }

        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
            throw new AssertionError("Legacy GET is forbidden");
        }
        @Override public ToolResult post(String path, ToolContext context, Map<String, Object> query) {
            throw new AssertionError("Legacy POST is forbidden");
        }
        @Override public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) {
            throw new AssertionError("Mutable current-group submission is forbidden");
        }
        @Override public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            submissions++;
            assertThat(context.principal()).isEqualTo(PRINCIPAL);
            assertThat(context.sessionId()).isEqualTo(SESSION);
            assertThat(context.arguments()).isEqualTo(request);
            var token = runtime.runs().loadRun(OWNER, runId).orElseThrow().token();
            var child = runtime.runs().children(token).stream()
                    .filter(value -> value.spec().requestId().equals(request.get("requestId"))).findFirst().orElseThrow();
            assertThat(child.state()).isEqualTo(ChildState.DISPATCHING);
            assertThat(child.callbackActive()).isTrue();
            assertThat(child.spec().wire().bodyJson()).isEqualTo(FrozenCampaignRun.encode(request));
            assertThat(child.spec().wire().path()).isEqualTo(StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH);
            assertThat(accepted.values()).noneMatch(previous -> previous.get("requestId").equals(request.get("requestId")));
            String job = "dependency-job-" + (accepted.size() + 1);
            accepted.put(job, Map.copyOf(request));
            return ToolResult.success(Map.of("jobId", job, "state", "QUEUED"));
        }
        @Override public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++;
            throw new AssertionError("An accepted original job must not be recovered or resubmitted");
        }
        @Override public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses++;
            assertThat(context.principal()).isEqualTo(PRINCIPAL);
            if (polls.merge(job, 1, Integer::sum) == 1)
                return ToolResult.success(Map.of("jobId", job, "state", "RUNNING"));
            return ToolResult.success(status(job));
        }
        @Override public ToolResult releaseStatisticsJobResult(ToolContext context, String job, Map<String, Object> request) {
            releases++;
            assertThat(FrozenCampaignRun.encode(request)).isEqualTo(FrozenCampaignRun.encode(accepted.get(job)));
            var token = runtime.runs().loadRun(OWNER, runId).orElseThrow().token();
            var child = runtime.runs().children(token).stream().filter(value -> job.equals(value.jobId())).findFirst().orElseThrow();
            assertThat(child.state()).isEqualTo(ChildState.READY);
            assertThat(runtime.runs().readArtifact(OWNER, child.artifactId(), runtime.dependencyArtifactAuthorizer())).isNotNull();
            assertThat(released.add(job)).as("A confirmed release happens once").isTrue();
            return ToolResult.success(status(job));
        }
        private Map<String, Object> status(String job) {
            int rows = "LINK_METRICS".equals(accepted.get(job).get("queryKind")) ? scope(job).linkIds().size() : 1;
            var status = new LinkedHashMap<String, Object>(Map.of("jobId", job, "state", "SUCCEEDED", "rowCount", rows,
                    "pageCount", 1, "expiresAt", EXPIRY, "resultState", released.contains(job) ? "RELEASED" : "AVAILABLE",
                    "resultReady", !released.contains(job)));
            if (released.contains(job)) status.put("resultCode", "RESULT_RELEASED");
            return status;
        }
        private FrozenQueryScope scope(String job) { return FrozenQueryScope.fromMap((Map<?, ?>) accepted.get(job).get("scope")); }

        @Override public ToolResult readStatisticsJobPage(ToolContext context, String job, int page, int size) {
            pageReads++;
            assertThat(page).isZero();
            assertThat(size).isEqualTo(500);
            assertThat(released).doesNotContain(job);
            var request = accepted.get(job);
            var scope = scope(job);
            boolean baseline = "2026-09-01".equals(request.get("startDate"));
            boolean dimension = "DIMENSION_BREAKDOWN".equals(request.get("queryKind"));
            long start = day(request.get("startDate").toString()), end = day(request.get("endDate").toString()) + 86_400_000;
            long total = scope.linkIds().stream().mapToLong(id -> pv(id, baseline)).sum();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (dimension) {
                assertThat(scope.linkIds()).containsExactly(1L);
                assertThat(request.get("dimensions")).isEqualTo(DIMENSIONS);
                assertThat(request.get("filters")).isEqualTo(List.of());
                rows.add(Map.of("dimensions", Map.of("province", Map.of("state", "KNOWN", "value", "浙江"),
                                "device", Map.of("state", "KNOWN", "value", "Mobile")), "pv", total, "uv", 1, "uip", 1, "pvRatio", 1.0));
            } else for (long id : scope.linkIds()) {
                var row = new LinkedHashMap<>(counts(pv(id, baseline), start, end));
                row.put("linkId", id);
                rows.add(row);
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
            var summary = new LinkedHashMap<String, Object>(counts(total, start, end));
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

    private static long pv(long id, boolean baseline) { return id == 1 ? (baseline ? 8 : 2) : (baseline ? 4 : 6); }
    private static long day(String value) { return LocalDate.parse(value).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }
    private static Map<String, Object> counts(long pv, long start, long end) {
        return Map.of("pv", pv, "uv", pv == 0 ? 0 : 1, "uip", pv == 0 ? 0 : 1, "denied", 0,
                "window", "requested", "startInclusive", start, "endExclusive", end);
    }
    private static Map<String, Object> quality(long total, String semantic) {
        return Map.of("status", "AVAILABLE", "knownCount", total, "unknownCount", 0, "eligibleCount", total,
                "notApplicableCount", 0, "coverage", 1.0, "semantic", semantic, "reasonCounts", Map.of());
    }
}
