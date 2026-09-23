package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactMetadata;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** One typed ranking request through the production composition, real ledgers and native Graph. */
@Timeout(40)
class CampaignStatisticsFixedRuntimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);
    private static final String SESSION = "fixed-ranking-session";
    private static final String START = "2026-09-01", END = "2026-09-02";
    private static final long EXPIRES = CLOCK.millis() + 3_600_000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void rankingWaitsForOriginalJobThenReceivesAndProjectsItWithoutResubmissionOrRevokedAccess() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:fixed_statistics_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                migration("V20260919__campaign_run_ledger.sql"),
                migration("V20260919_2__campaign_step_ledger.sql"),
                migration("V20260919_3__campaign_run_owner.sql"),
                migration("V20260920__campaign_statistics_result.sql"),
                migration("V20260920_3__campaign_submission_deferral.sql"),
                migration("V20260920_17__campaign_run_intake.sql"),
                migration("V20260923__campaign_conversation_session_owner.sql"),
                migration("V20260923_2__campaign_advance_outcome.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var live = new AtomicBoolean(true);
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(any(AgentPrincipal.class))).thenAnswer(call -> {
            if (!live.get() || !PRINCIPAL.equals(call.getArgument(0)))
                throw new SecurityException("CURRENT_ACCOUNT_REVOKED");
            return PRINCIPAL;
        });
        when(authority.resolveGroupMembersPage(PRINCIPAL, "alpha", null, null)).thenAnswer(call -> {
            if (!live.get()) throw new SecurityException("GROUP_ACCESS_REVOKED");
            return new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "alice", 7,
                    "alpha", "a".repeat(64), null, List.of(1L, 2L), null);
        });
        var gateway = new Gateway();
        try (var runtime = new CampaignStatisticsFixedRuntime(jdbc, tx, CLOCK, authority, gateway,
                new MemorySaver(), "statistics-fixed-test-domain", new ProcessCapacityExecutor.Limits(1, 1, 1, 2))) {
            assertThat(runtime.principals().bindCurrent(PRINCIPAL, SESSION)).isEqualTo(PRINCIPAL);
            var prepared = runtime.plans().ranking(OWNER, SESSION, "ranking-request", "alpha",
                    START, END, "pv", 2);
            var reference = runtime.intake().register(PRINCIPAL, SESSION, "ranking-request",
                    CampaignStatisticsFixedRuntime.PROFILE_REF,
                    CampaignStatisticsFixedRuntime.PROFILE_VERSION, prepared.definition());
            assertThat(gateway.calls()).isZero();

            runtime.intake().submit(PRINCIPAL, reference).get(15, TimeUnit.SECONDS);
            var waiting = runtime.runs().loadRun(OWNER, reference.runId()).orElseThrow();
            var child = runtime.runs().children(waiting.token()).get(0);
            assertThat(child.state()).isEqualTo(ChildState.WAITING);
            assertThat(runtime.steps().step(waiting.token(), "collect-1").orElseThrow().status())
                    .isEqualTo(StepStatus.WAITING);
            assertThat(gateway.submissions).isEqualTo(1);
            assertThat(gateway.statusReads).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger", Integer.class)).isEqualTo(1);

            live.set(false);
            int beforeRevokedResume = gateway.calls();
            assertThatThrownBy(() -> runtime.intake().submit(PRINCIPAL, reference))
                    .isInstanceOf(SecurityException.class);
            assertThat(gateway.calls()).isEqualTo(beforeRevokedResume);
            live.set(true);

            runtime.intake().submit(PRINCIPAL, reference).get(15, TimeUnit.SECONDS);
            var completed = runtime.runs().loadRun(OWNER, reference.runId()).orElseThrow();
            var ready = runtime.runs().children(completed.token()).get(0);
            assertThat(ready.state()).isEqualTo(ChildState.READY);
            assertThat(ready.spec()).isEqualTo(child.spec());
            assertThat(runtime.steps().step(completed.token(), "collect-1").orElseThrow().status())
                    .isEqualTo(StepStatus.SUCCEEDED);
            assertThat(runtime.results().receipt(completed.token(), ready.spec().childId()).orElseThrow().published())
                    .isTrue();
            assertThat(gateway.submissions).isEqualTo(1);
            assertThat(gateway.recoveries).isZero();
            assertThat(gateway.statusReads).isEqualTo(1);
            assertThat(gateway.pageReads).isEqualTo(1);

            var ranked = runtime.projector().rank(OWNER, completed.token(), "collect-1", "alpha",
                    START, END, "pv", 2, runtime.artifactAuthorizer());
            assertThat(ranked).containsEntry("type", "ranking").containsEntry("status", "READY");
            var rows = (List<?>) ranked.get("rows");
            assertThat(rows).hasSize(2);
            assertThat(((Map<?, ?>) rows.get(0)).get("linkId")).isEqualTo(2);
            assertThat(((Map<?, ?>) rows.get(1)).get("linkId")).isEqualTo(1);
            assertThat(runtime.results().readPage(OWNER, ready.artifactId(), 0,
                    runtime.artifactAuthorizer())).contains("\"linkId\"");

            ArtifactMetadata original = runtime.runs().inspectArtifact(OWNER, ready.artifactId(),
                    runtime.artifactAuthorizer());
            ArtifactRef ref = original.ref();
            ArtifactRef wrongScope = new ArtifactRef(ref.artifactId(), ref.type(), ref.schemaVersion(),
                    ref.payloadHash(), "current-group.v1:other", ref.periodsRef(), ref.expiresAt());
            assertThat(runtime.artifactAuthorizer().mayRead(OWNER, copy(original, wrongScope, original.revision())))
                    .isFalse();
            assertThat(runtime.artifactAuthorizer().mayRead(OWNER, copy(original, ref, original.revision() + 1)))
                    .isFalse();

            int completedCalls = gateway.calls();
            runtime.intake().submit(PRINCIPAL, reference).get(15, TimeUnit.SECONDS);
            assertThat(gateway.calls()).isEqualTo(completedCalls);
            live.set(false);
            assertThat(runtime.artifactAuthorizer().mayRead(OWNER, original)).isFalse();
            assertThatThrownBy(() -> runtime.projector().rank(OWNER, completed.token(), "collect-1",
                    "alpha", START, END, "pv", 2, runtime.artifactAuthorizer()))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> runtime.intake().submit(PRINCIPAL, reference))
                    .isInstanceOf(SecurityException.class);
            assertThat(gateway.calls()).isEqualTo(completedCalls);
        }
    }

    private static ClassPathResource migration(String file) {
        return new ClassPathResource("sql/migration/" + file);
    }

    private static ArtifactMetadata copy(ArtifactMetadata original, ArtifactRef ref, int revision) {
        return new ArtifactMetadata(ref, original.owner(), original.runId(), original.planId(), revision,
                original.actionId(), original.childId(), original.executorVersion(),
                original.qualityJson(), original.provenanceJson());
    }

    private static long day(String value) {
        return LocalDate.parse(value).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private static Map<String, Object> counts(int pv, int uv, int uip) {
        return Map.of("pv", pv, "uv", uv, "uip", uip, "denied", 0,
                "window", "requested", "startInclusive", day(START),
                "endExclusive", day("2026-09-03"));
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        int submissions, recoveries, statusReads, pageReads;

        int calls() { return submissions + recoveries + statusReads + pageReads; }

        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
            throw new AssertionError("Legacy GET is forbidden");
        }

        @Override public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) {
            submissions++;
            assertThat(context.principal()).isEqualTo(PRINCIPAL);
            assertThat(context.sessionId()).isEqualTo(SESSION);
            assertThat(request).containsEntry("gid", "alpha").containsEntry("queryKind", "LINK_METRICS")
                    .containsEntry("startDate", START).containsEntry("endDate", END)
                    .containsKey("requestId");
            return ToolResult.success(Map.of("jobId", "fixed-rank-job", "state", "SUCCEEDED"));
        }

        @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries++;
            throw new AssertionError("Known job must not be resubmitted or recovered");
        }

        @Override public ToolResult readStatisticsJob(ToolContext context, String jobId) {
            statusReads++;
            assertThat(jobId).isEqualTo("fixed-rank-job");
            return ToolResult.success(Map.of("jobId", jobId, "state", "SUCCEEDED",
                    "rowCount", 2, "pageCount", 1, "expiresAt", EXPIRES));
        }

        @Override public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int page, int size) {
            pageReads++;
            assertThat(jobId).isEqualTo("fixed-rank-job");
            assertThat(page).isZero();
            assertThat(size).isEqualTo(500);
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", jobId);
            meta.put("queryKind", "LINK_METRICS");
            meta.put("gid", "alpha");
            meta.put("linkIds", List.of(1L, 2L));
            meta.put("groupScopeComplete", true);
            meta.put("metricVersion", "click-v1");
            meta.put("recoveryEpoch", "epoch-1");
            meta.put("sourceCut", Map.of("manifestSelectionHash", "selection-1"));
            meta.put("manifestVersion", Map.of("selectionHash", "selection-1"));
            meta.put("snapshotCreatedAt", CLOCK.millis());
            meta.put("snapshotExpiresAt", EXPIRES);
            meta.put("requestedStart", day(START));
            meta.put("requestedEnd", day("2026-09-03"));
            meta.put("effectiveEnd", day("2026-09-03"));
            meta.put("businessTimezone", "Asia/Shanghai");
            meta.put("pageIndex", page);
            meta.put("nextPageIndex", null);
            meta.put("totalRows", 2);
            meta.put("aggregationLevel", "LINK_WINDOW");
            meta.put("availability", "AVAILABLE");
            meta.put("completeness", "COMPLETE");
            meta.put("freshness", "FRESH");
            meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
            meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"),
                    "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            var first = new LinkedHashMap<String, Object>(counts(2, 1, 1));
            first.put("linkId", 1L);
            var second = new LinkedHashMap<String, Object>(counts(5, 1, 1));
            second.put("linkId", 2L);
            return ToolResult.success(Map.of("items", List.of(first, second),
                    "metrics", Map.of("requested", counts(7, 2, 2)), "meta", meta));
        }
    }
}
