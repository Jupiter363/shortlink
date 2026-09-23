package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Adapter behavior against actual intake, session ownership, Graph, receiver and projector. */
@Timeout(45)
class CampaignStatisticsDurableToolAdapterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final String SESSION = "durable-tool-session", REQUEST = "invocation-1";
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);
    private static final String START = "2026-09-01", END = "2026-09-02";
    private static final long EXPIRES = CLOCK.millis() + TimeUnit.HOURS.toMillis(1);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void rankingKeepsOneOriginalJobAndRejectsChangedContinuationBeforeIo() throws Exception {
        try (var f = new Fixture()) {
            var args = Map.<String, Object>of("gid", "alpha", "startDate", START,
                    "endDate", END, "metric", "pv", "limit", 2);
            assertThat(f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, args), null).success())
                    .isFalse();
            var first = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, args), REQUEST);
            assertThat(first.success()).isTrue();
            Map<?, ?> pending = (Map<?, ?>) first.data();
            assertThat(pending.get("type")).isEqualTo("ranking");
            assertThat(pending.get("status")).isEqualTo("PENDING");
            Map<?, ?> continuation = (Map<?, ?>) pending.get("continuation");
            assertThat(continuation.containsKey("jobId") || continuation.containsKey("jobs")).isFalse();
            Map<?, ?> work = (Map<?, ?>) continuation.get("workRef");
            String runId = work.get("runId").toString();
            f.await(() -> f.waiting(runId));
            assertThat(f.gateway.submits.get()).isEqualTo(1);
            assertThat(f.gateway.statuses.get()).isZero();

            Map<String, Object> resumedArgs = new LinkedHashMap<>(args);
            resumedArgs.put("workRef", continuation.get("workRef"));
            Map<String, Object> changed = new LinkedHashMap<>(resumedArgs);
            changed.put("metric", "uv");
            int beforeDenied = f.gateway.calls();
            assertThat(f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, changed), null).success())
                    .isFalse();
            assertThat(f.adapter.execute("rank_short_links", context(PRINCIPAL, "foreign-session", resumedArgs),
                    null).success()).isFalse();
            assertThat(f.adapter.execute("rank_short_links", context(new AgentPrincipal("1001", "bob", 7, false),
                    SESSION, resumedArgs), null).success()).isFalse();
            assertThat(f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION,
                    Map.of("gid", "alpha", "startDate", START, "endDate", END,
                            "metric", "pv", "limit", 2, "jobId", "legacy-job")), REQUEST).success()).isFalse();
            assertThat(f.gateway.calls()).isEqualTo(beforeDenied);

            var resumed = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, resumedArgs), null);
            assertThat(resumed.success()).isTrue();
            assertThat(((Map<?, ?>) resumed.data()).get("status")).isEqualTo("PENDING");
            f.await(() -> f.succeeded(runId, 1));
            var ready = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, resumedArgs), null);
            assertThat(ready.success()).isTrue();
            Map<?, ?> data = (Map<?, ?>) ready.data();
            assertThat(data.get("status")).isEqualTo("READY");
            assertThat(data.get("continuation")).isEqualTo(continuation);
            assertThat(((Map<?, ?>) ((List<?>) data.get("rows")).get(0)).get("linkId")).isEqualTo(2);
            assertThat(f.gateway.submits.get()).isEqualTo(1);
            assertThat(f.gateway.recoveries.get()).isZero();
            assertThat(f.gateway.pages.get()).isEqualTo(1);
            int completedCalls = f.gateway.calls();
            assertThat(f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, resumedArgs), null))
                    .isEqualTo(ready);
            assertThat(f.gateway.calls()).isEqualTo(completedCalls);
        }
    }

    @Test
    void comparisonNormalizesLegacyUrlAndProjectsOnlyAfterEveryFrozenQueryReady() throws Exception {
        try (var f = new Fixture()) {
            var scopes = List.<Map<String, Object>>of(
                    Map.of("gid", "alpha", "fullShortUrl", "http://short.test/a"),
                    Map.of("gid", "beta"));
            var periods = List.<Map<String, Object>>of(Map.of("startDate", START, "endDate", END));
            var first = f.adapter.execute("compare_statistics", context(PRINCIPAL, SESSION,
                    Map.of("scopes", scopes, "periods", periods)), REQUEST);
            assertThat(first.success()).isTrue();
            Map<?, ?> continuation = (Map<?, ?>) ((Map<?, ?>) first.data()).get("continuation");
            assertThat(((List<?>) continuation.get("scopes")).get(0))
                    .isEqualTo(Map.of("gid", "alpha", "fullShortUrl", "short.test/a"));
            String runId = ((Map<?, ?>) continuation.get("workRef")).get("runId").toString();
            f.await(() -> f.waiting(runId));
            var resume = f.adapter.execute("compare_statistics", context(PRINCIPAL, SESSION,
                    cast(continuation)), null);
            assertThat(resume.success()).isTrue();
            f.await(() -> f.succeeded(runId, 2));
            var ready = f.adapter.execute("compare_statistics", context(PRINCIPAL, SESSION,
                    cast(continuation)), null);
            assertThat(ready.success()).isTrue();
            Map<?, ?> data = (Map<?, ?>) ready.data();
            assertThat(data.get("status")).isEqualTo("READY");
            assertThat(((List<?>) data.get("rows"))).hasSize(2);
            assertThat(data.get("continuation")).isEqualTo(continuation);
            assertThat(f.gateway.submits.get()).isEqualTo(2);
            assertThat(f.gateway.recoveries.get()).isZero();
        }
    }

    @Test
    void preFreezeFailureIsVisibleAndSameWorkRefCanRetry() throws Exception {
        try (var f = new Fixture()) {
            f.failWorkerPrincipal.set(true);
            var args = Map.<String, Object>of("gid", "alpha", "startDate", START,
                    "endDate", END, "metric", "pv", "limit", 2);
            var first = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, args), REQUEST);
            assertThat(first.success()).isTrue();
            Map<?, ?> continuation = (Map<?, ?>) ((Map<?, ?>) first.data()).get("continuation");
            Map<?, ?> work = (Map<?, ?>) continuation.get("workRef");
            String runId = work.get("runId").toString();
            var receipt = f.runtime.intake().receipt(PRINCIPAL,
                    new WorkRef(runId, work.get("workId").toString()));
            f.await(() -> f.runtime.outcomes().latest(receipt)
                    .map(outcome -> "FAILED".equals(outcome.status())).orElse(false));
            assertThat(f.runtime.outcomes().latest(receipt).orElseThrow().reason())
                    .isEqualTo("ACCESS_DENIED");
            assertThat(f.gateway.calls()).isZero();

            f.failWorkerPrincipal.set(false);
            Map<String, Object> resumedArgs = cast(continuation);
            var retry = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, resumedArgs), null);
            assertThat(retry.success()).isTrue();
            Map<?, ?> retryData = (Map<?, ?>) retry.data();
            assertThat(retryData.get("status")).isEqualTo("INCOMPLETE");
            assertThat(retryData.get("continuation")).isEqualTo(continuation);
            Map<?, ?> meta = (Map<?, ?>) retryData.get("meta");
            assertThat(meta.get("lastAdvanceStatus")).isEqualTo("FAILED");
            assertThat(meta.get("advanceReason")).isEqualTo("ACCESS_DENIED");
            assertThat(meta.get("canRetryWithWorkRef")).isEqualTo(true);
            assertThat(meta.get("retryQueued")).isEqualTo(true);
            assertThat(((List<?>) retryData.get("warnings")).get(0).toString())
                    .doesNotContain("workRef");
            f.await(() -> f.waiting(runId));
            assertThat(f.gateway.submits.get()).isEqualTo(1);

            var resumed = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, resumedArgs), null);
            assertThat(resumed.success()).isTrue();
            f.await(() -> f.succeeded(runId, 1));
            var ready = f.adapter.execute("rank_short_links", context(PRINCIPAL, SESSION, resumedArgs), null);
            assertThat(ready.success()).isTrue();
            assertThat(((Map<?, ?>) ready.data()).get("status")).isEqualTo("READY");
            assertThat(((Map<?, ?>) ready.data()).get("continuation")).isEqualTo(continuation);
            assertThat(f.gateway.submits.get()).isEqualTo(1);
            assertThat(f.gateway.recoveries.get()).isZero();
        }
    }

    private static ToolContext context(AgentPrincipal principal, String session, Map<String, Object> args) {
        return new ToolContext(session, principal.username(), args, principal);
    }

    private static Map<String, Object> cast(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(key.toString(), value));
        return result;
    }

    private static long day(String text) {
        return LocalDate.parse(text).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private static Map<String, Object> counts(int pv, int uv, int uip) {
        return Map.of("pv", pv, "uv", uv, "uip", uip, "denied", 0,
                "window", "requested", "startInclusive", day(START),
                "endExclusive", day("2026-09-03"));
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        final Gateway gateway = new Gateway();
        final AtomicBoolean failWorkerPrincipal = new AtomicBoolean();
        final CampaignStatisticsFixedRuntime runtime;
        final CampaignStatisticsDurableToolAdapter adapter;

        Fixture() {
            var dataSource = new DriverManagerDataSource("jdbc:h2:mem:durable_adapter_" + UUID.randomUUID()
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
            jdbc = new JdbcTemplate(dataSource);
            when(authority.verifyCurrentPrincipal(any(AgentPrincipal.class)))
                    .thenAnswer(call -> {
                        if (failWorkerPrincipal.get()
                                && Thread.currentThread().getName().startsWith("campaign-statistics-fixed-"))
                            throw new SecurityException("simulated worker revalidation failure");
                        return call.getArgument(0);
                    });
            when(authority.resolveGroupMembersPage(any(AgentPrincipal.class), anyString(), isNull(), isNull()))
                    .thenAnswer(call -> {
                        AgentPrincipal principal = call.getArgument(0);
                        String gid = call.getArgument(1);
                        return new GroupMembersPage(GroupMembersPage.SCHEMA,
                                principal.tenantId(), principal.username(), principal.authVersion(), gid,
                                "a".repeat(64), null, List.of(1L, 2L), null);
                    });
            when(authority.resolvePage(any(AgentPrincipal.class), anyString(), anyString(), isNull(),
                    isNull(), nullable(String.class))).thenAnswer(call -> new AgentAuthorityClient.AuthorizedScope(
                    "1001", "a".repeat(64), List.of(Map.of("gid", "alpha", "linkId", 1L,
                    "fullShortUrl", "https://short.test/a")), null));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            runtime = new CampaignStatisticsFixedRuntime(jdbc, tx, CLOCK, authority, gateway,
                    new MemorySaver(), "statistics-adapter-test-domain",
                    new ProcessCapacityExecutor.Limits(1, 1, 1, 2));
            adapter = new CampaignStatisticsDurableToolAdapter(runtime, CLOCK);
        }

        boolean waiting(String runId) {
            int steps = count("campaign_step_ledger", runId, null);
            return steps > 0 && count("campaign_step_ledger", runId, "WAITING") == steps
                    && count("campaign_child_ledger", runId, "WAITING") == steps;
        }

        boolean succeeded(String runId, int expectedSteps) {
            return count("campaign_step_ledger", runId, null) == expectedSteps
                    && count("campaign_step_ledger", runId, "SUCCEEDED") == expectedSteps
                    && count("campaign_child_ledger", runId, "READY") == expectedSteps;
        }

        private int count(String table, String runId, String state) {
            String column = "campaign_step_ledger".equals(table) ? "step_status" : "child_state";
            if (state == null) return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE run_id=?", Integer.class, runId);
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=? AND "
                    + column + "=? AND callback_active=FALSE", Integer.class, runId, state);
        }

        void await(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(condition.getAsBoolean()).isTrue();
        }

        @Override public void close() { runtime.close(); }

        private static ClassPathResource migration(String file) {
            return new ClassPathResource("sql/migration/" + file);
        }
    }

    private static final class Gateway implements ShortLinkBusinessGateway {
        final AtomicInteger submits = new AtomicInteger(), recoveries = new AtomicInteger();
        final AtomicInteger statuses = new AtomicInteger(), pages = new AtomicInteger();
        final Map<String, Map<String, Object>> requests = new ConcurrentHashMap<>();

        int calls() { return submits.get() + recoveries.get() + statuses.get() + pages.get(); }

        @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
            throw new AssertionError("Legacy gateway access");
        }

        @Override public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> request) {
            submits.incrementAndGet();
            String job = "job-" + request.get("requestId");
            requests.put(job, Map.copyOf(request));
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED"));
        }

        @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) {
            recoveries.incrementAndGet();
            throw new AssertionError("A known job must never be submitted again");
        }

        @Override public ToolResult readStatisticsJob(ToolContext context, String job) {
            statuses.incrementAndGet();
            Map<String, Object> request = requests.get(job);
            assertThat(request).isNotNull();
            boolean ranking = "LINK_METRICS".equals(request.get("queryKind"));
            return ToolResult.success(Map.of("jobId", job, "state", "SUCCEEDED",
                    "rowCount", ranking ? 2 : 0, "pageCount", ranking ? 1 : 0,
                    "expiresAt", EXPIRES));
        }

        @Override public ToolResult readStatisticsJobPage(ToolContext context, String job, int index, int size) {
            pages.incrementAndGet();
            Map<String, Object> request = requests.get(job);
            assertThat(request).isNotNull();
            assertThat(index).isZero();
            assertThat(size).isEqualTo(500);
            boolean ranking = "LINK_METRICS".equals(request.get("queryKind"));
            boolean shortUrl = request.containsKey("fullShortUrl");
            var meta = new LinkedHashMap<String, Object>();
            meta.put("snapshotId", job);
            meta.put("queryKind", request.get("queryKind"));
            meta.put("gid", request.get("gid"));
            meta.put("linkIds", shortUrl ? List.of(1L) : List.of(1L, 2L));
            meta.put("groupScopeComplete", !shortUrl);
            if (shortUrl) meta.put("fullShortUrl", "https://" + request.get("fullShortUrl"));
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
            meta.put("pageIndex", index);
            meta.put("nextPageIndex", null);
            meta.put("totalRows", ranking ? 2 : 0);
            meta.put("aggregationLevel", ranking ? "LINK_WINDOW" : "GROUP_WINDOW");
            meta.put("availability", "AVAILABLE");
            meta.put("completeness", "COMPLETE");
            meta.put("freshness", "FRESH");
            meta.put("provisional", false);
            meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
            meta.put("missingMetrics", List.of());
            meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"),
                    "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"),
                    "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            List<Map<String, Object>> items;
            if (ranking) {
                var first = new LinkedHashMap<String, Object>(counts(2, 1, 1));
                first.put("linkId", 1L);
                var second = new LinkedHashMap<String, Object>(counts(5, 1, 1));
                second.put("linkId", 2L);
                items = List.of(first, second);
            } else items = List.of();
            int pv = ranking ? 7 : "alpha".equals(request.get("gid")) ? 10 : 5;
            return ToolResult.success(Map.of("items", items,
                    "metrics", Map.of("requested", counts(pv, ranking ? 2 : 1, ranking ? 2 : 1)),
                    "meta", meta));
        }
    }
}
