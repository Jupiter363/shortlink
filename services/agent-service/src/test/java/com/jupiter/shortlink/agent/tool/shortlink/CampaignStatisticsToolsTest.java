package com.jupiter.shortlink.agent.tool.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class CampaignStatisticsToolsTest {
    private static final String BASE = "/internal/short-link-admin/v1/agent-tools";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T01:00:00Z"), ZONE);
    private static final AgentPrincipal ALICE = new AgentPrincipal("101", "alice", 3, false);
    private static final org.springframework.ai.chat.model.ToolContext TRUSTED =
            new org.springframework.ai.chat.model.ToolContext(Map.of("sessionId", "session", "username", "mallory", "principal", ALICE.toState()));
    private static final List<Map<String, Object>> GROUPS = List.of(Map.of("gid", "alpha"), Map.of("gid", "beta"));
    private static final Map<String, Object> WEEK = period("2026-09-07", "2026-09-13");
    private final List<Request> calls = new ArrayList<>();

    @Test
    void crossObjectComparisonUsesWholeWindowTotalsWithoutSummingDistinctVisitors() {
        var tool = tool(request -> ToolResult.success(envelope(
                "alpha".equals(request.arguments.get("gid")) ? counts(10, 4, 2) : counts(15, 4, 3),
                List.of(Map.of("pv", 999, "uv", 999, "uip", 999)), quality("snapshot"))));
        var result = data(tool.compareStatistics(GROUPS, List.of(WEEK), null, TRUSTED));

        assertThat(result).containsEntry("status", "READY");
        var rows = rows(result, "rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("pv", new BigDecimal("10")).containsEntry("uv", new BigDecimal("4"));
        assertThat(rows.get(1)).containsEntry("pv", new BigDecimal("15")).containsEntry("uv", new BigDecimal("4"));
        var pv = comparison(result, "pv");
        assertThat(pv).containsEntry("kind", "OBJECT").containsEntry("comparable", true)
                .containsEntry("delta", new BigDecimal("5")).containsEntry("rate", new BigDecimal("0.50000000"));
        assertThat(map(result.get("metrics"))).doesNotContainKeys("pv", "uv", "uip");
        assertThat(calls).allSatisfy(request -> {
            assertThat(request.context.principal()).isEqualTo(ALICE);
            assertThat(request.context.username()).isEqualTo("alice");
        });
    }

    @Test
    void periodComparisonUsesFirstPeriodAsTargetAndComputesChangesInCode() {
        var tool = tool(request -> ToolResult.success(envelope(counts(
                "2026-09-13".equals(request.arguments.get("startDate")) ? 15 : 10, 4, 2), List.of(), quality("snapshot"))));
        var result = data(tool.compareStatistics(List.of(Map.of("gid", "alpha")),
                List.of(period("2026-09-13", "2026-09-13"), period("2026-09-12", "2026-09-12")), null, TRUSTED));

        assertThat(comparison(result, "pv")).containsEntry("kind", "PERIOD").containsEntry("comparable", true)
                .containsEntry("delta", new BigDecimal("5")).containsEntry("rate", new BigDecimal("0.50000000"));
    }

    @Test
    void aZeroBaselineNeverProducesAnInfiniteOrInventedRate() {
        var tool = tool(request -> ToolResult.success(envelope(counts(
                "alpha".equals(request.arguments.get("gid")) ? 0 : 8, 0, 0), List.of(), quality("snapshot"))));
        var result = data(tool.compareStatistics(GROUPS, List.of(WEEK), null, TRUSTED));

        assertThat(comparison(result, "pv")).containsEntry("delta", new BigDecimal("8")).containsEntry("rate", null);
        assertThat(comparison(result, "pv").get("warnings").toString()).contains("基期为零");
    }

    @Test
    void partialDataPreservesObservedDifferenceAndSuppressesUnsupportedGrowth() {
        var meta = quality("partial");
        meta.put("completeness", "PARTIAL");
        var tool = tool(request -> ToolResult.success(envelope(counts(8, 4, 2), List.of(), meta)));
        var result = data(tool.compareStatistics(GROUPS, List.of(WEEK), null, TRUSTED));

        assertThat(comparison(result, "pv")).containsEntry("delta", BigDecimal.ZERO)
                .containsEntry("rate", null).containsEntry("comparable", false);
        assertThat(map(rows(result, "rows").get(0).get("quality"))).containsEntry("completeness", "PARTIAL");
        assertThat(result.get("warnings").toString()).contains("不完整");
    }

    @Test
    void unequalOrOverlappingPeriodsAreNotPresentedAsComparableGrowth() {
        var tool = tool(request -> ToolResult.success(envelope(counts(8, 4, 2), List.of(), quality("snapshot"))));
        var result = data(tool.compareStatistics(List.of(Map.of("gid", "alpha")),
                List.of(WEEK, period("2026-09-12", "2026-09-13")), null, TRUSTED));

        var pv = comparison(result, "pv");
        assertThat(pv).containsEntry("rate", null).containsEntry("comparable", false);
        assertThat(pv.get("warnings").toString()).contains("长度不同", "重叠");
    }

    @Test
    void anUnfinishedCurrentDayDoesNotBecomeACompletedPeriodGrowthRate() {
        var tool = tool(request -> ToolResult.success(envelope(counts(8, 4, 2), List.of(), quality("snapshot"))));
        var result = data(tool.compareStatistics(List.of(Map.of("gid", "alpha")),
                List.of(period("2026-09-19", "2026-09-19"), period("2026-09-18", "2026-09-18")), null, TRUSTED));
        assertThat(comparison(result, "pv")).containsEntry("rate", null).containsEntry("comparable", false);
        assertThat(comparison(result, "pv").get("warnings").toString()).contains("尚未结束");
    }

    @Test
    void absentWholeWindowMetricsStayMissingEvenWhenItemRowsContainValues() {
        var tool = tool(request -> ToolResult.success(envelope(Map.of("pv", 12),
                List.of(counts(12, 10, 1)), quality("snapshot"))));
        var result = data(tool.compareStatistics(GROUPS, List.of(WEEK), null, TRUSTED));

        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result, "rows")).allSatisfy(row -> assertThat(row).doesNotContainKeys("pv", "uv", "uip"));
        assertThat(comparison(result, "pv")).containsEntry("delta", null).containsEntry("rate", null);
    }

    @Test
    void pendingComparisonRetainsEveryJobAndChecksEachOnlyOnceOnContinuation() {
        var longPeriod = period("2026-08-15", "2026-09-13");
        var tool = tool(request -> {
            if (request.method.equals("POST")) return ToolResult.success(Map.of("jobId", "job-" + request.arguments.get("gid"), "state", "QUEUED"));
            return ToolResult.success(Map.of("jobId", request.path.substring(request.path.lastIndexOf('/') + 1), "state", "RUNNING"));
        });
        var first = data(tool.compareStatistics(GROUPS, List.of(longPeriod), null, TRUSTED));
        assertThat(first).containsEntry("status", "PENDING");
        var continuation = map(first.get("continuation"));
        var refs = rows(continuation, "jobs");
        assertThat(refs).hasSize(2);
        assertThat(refs).allSatisfy(job -> assertThat(job).containsEntry("queryKind", "METRICS"));
        var second = data(tool.compareStatistics(GROUPS, List.of(longPeriod), refs, TRUSTED));

        assertThat(second).containsEntry("status", "PENDING");
        assertThat(calls).filteredOn(request -> request.method.equals("POST")).hasSize(2);
        assertThat(calls).filteredOn(request -> request.method.equals("GET")).hasSize(2);
        assertThat(rows(map(second.get("continuation")), "jobs")).hasSize(2);
    }

    @Test
    void succeededComparisonChecksScopeProofBeforeUsingTheJobSummary() {
        var longPeriod = period("2026-08-15", "2026-09-13");
        var tool = tool(request -> {
            String gid = request.path.contains("job-alpha") ? "alpha" : "beta";
            String id = "job-" + gid;
            if (!request.path.endsWith("/page")) return ToolResult.success(Map.of("jobId", id, "state", "SUCCEEDED"));
            var meta = jobQuality(id, gid, "METRICS", longPeriod);
            return ToolResult.success(envelope(counts(20, 1, 1), List.of(counts(999, 999, 999)), meta));
        });
        var result = data(tool.compareStatistics(GROUPS, List.of(longPeriod),
                List.of(reference("alpha", longPeriod, "job-alpha"), reference("beta", longPeriod, "job-beta")), TRUSTED));

        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result, "rows")).allSatisfy(row -> assertThat(row).containsEntry("uv", BigDecimal.ONE));
        assertThat(calls).hasSize(4);
    }

    @Test
    void aJobFromAnotherGroupCannotBeRelabeledAsRequestedComparisonScope() {
        var longPeriod = period("2026-08-15", "2026-09-13");
        var tool = tool(request -> request.path.endsWith("/page")
                ? ToolResult.success(envelope(counts(20, 1, 1), List.of(), jobQuality("job-alpha", "foreign", "METRICS", longPeriod)))
                : ToolResult.success(Map.of("jobId", request.path.contains("job-alpha") ? "job-alpha" : "job-beta", "state", "SUCCEEDED")));
        var result = data(tool.compareStatistics(GROUPS, List.of(longPeriod),
                List.of(reference("alpha", longPeriod, "job-alpha"), reference("beta", longPeriod, "job-beta")), TRUSTED));

        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result, "rows")).allSatisfy(row -> assertThat(row).doesNotContainKeys("pv", "uv", "uip"));
    }

    @Test
    void mismatchedJobReferenceIsRejectedBeforeAnyGatewayCall() {
        var tool = tool(request -> ToolResult.success(Map.of()));
        var result = tool.compareStatistics(GROUPS, List.of(WEEK),
                List.of(reference("foreign", WEEK, "job-foreign")), TRUSTED);
        assertThat(result.success()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void rankingReadsEveryFrozenPageBeforeSelectingTopLinksAndDoesNotSumUv() {
        var tool = tool(request -> {
            var meta = quality("snapshot");
            if (!request.arguments.containsKey("cursor")) {
                meta.put("nextCursor", "second-page");
                return ToolResult.success(envelope(counts(100, 1, 1), List.of(link(1, 1, 1, 1)), meta));
            }
            assertThat(request.arguments).containsEntry("snapshotId", "snapshot").containsEntry("cursor", "second-page").containsEntry("pageSize", 500);
            return ToolResult.success(envelope(counts(100, 1, 1), List.of(link(2, 99, 1, 1)), meta));
        });
        var result = data(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 1, null, TRUSTED));

        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result, "rows")).hasSize(1);
        assertThat(rows(result, "rows").get(0)).containsEntry("linkId", 2L).containsEntry("pvShare", new BigDecimal("0.99000000"));
        assertThat(map(result.get("metrics"))).containsEntry("uv", 1L);
        assertThat(map(result.get("meta"))).containsEntry("totalLinks", 2).containsEntry("resultComplete", true)
                .doesNotContainKeys("nextCursor", "nextPageIndex");
        assertThat(map(rows(result, "rows").get(0).get("quality"))).doesNotContainKeys("nextCursor", "nextPageIndex");
        assertThat(calls).hasSize(2);
    }

    @Test
    void rankingSerializesSharedProvenanceOnceWithoutDroppingRowQualityOrAuditIdentity() throws Exception {
        var sourceCut = new LinkedHashMap<String, Object>();
        var manifestVersion = new LinkedHashMap<String, Object>();
        for (int window = 0; window < 128; window++) {
            sourceCut.put(Integer.toString(window), Map.of("partition", 0, "offset", window));
            manifestVersion.put(Integer.toString(window), Map.of("buildId", "build-" + window, "revision", 1));
        }
        var meta = quality("snapshot");
        meta.put("sourceCut", sourceCut);
        meta.put("manifestVersion", manifestVersion);
        meta.put("windowVersions", Map.of("window", "version"));
        meta.put("requestedStart", 123L);
        meta.put("requestedEnd", 456L);
        meta.put("completeness", "PARTIAL");
        meta.put("provisional", true);
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        meta.put("futureQuality", Map.of("status", "UNKNOWN"));
        var tool = tool(request -> ToolResult.success(envelope(counts(13, 1, 1),
                List.of(link(1, 8, 1, 1), link(2, 5, 1, 1)), meta)));

        var result = data(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 10, null, TRUSTED));

        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result, "rows")).hasSize(2).allSatisfy(row ->
                assertThat(map(row.get("quality"))).containsEntry("snapshotId", "snapshot")
                        .containsEntry("requestedStart", 123L).containsEntry("requestedEnd", 456L)
                        .containsEntry("completeness", "PARTIAL").containsEntry("provisional", true)
                        .containsEntry("collectionQuality", Map.of("status", "UNKNOWN"))
                        .containsEntry("futureQuality", Map.of("status", "UNKNOWN"))
                        .containsEntry("approximation", meta.get("approximation"))
                        .doesNotContainKeys("sourceCut", "manifestVersion", "windowVersions"));
        assertThat(map(result.get("meta"))).containsEntry("sourceCut", sourceCut)
                .containsEntry("manifestVersion", manifestVersion).containsEntry("windowVersions", meta.get("windowVersions"));
        assertThat(meta).containsKeys("sourceCut", "manifestVersion", "windowVersions");
        assertThat(new ObjectMapper().writeValueAsString(result)).containsOnlyOnce("\"sourceCut\":")
                .containsOnlyOnce("\"manifestVersion\":").containsOnlyOnce("\"windowVersions\":");
        assertThat(result.get("warnings").toString()).contains("不完整", "采集完整性未得到证明");
    }

    @ParameterizedTest
    @ValueSource(strings = {"uv", "uip", "both", "pvExact", "unknown"})
    void approximationWarningsNameOnlyVisitorMetricsExplicitlyMarkedApproximate(String contract) {
        var approximation = new LinkedHashMap<String, Object>();
        approximation.put("pv", Map.of("type", "EXACT"));
        if (contract.equals("uv") || contract.equals("both")) approximation.put("uv", Map.of("type", "APPROXIMATE"));
        if (contract.equals("uip") || contract.equals("both")) approximation.put("uip", Map.of("type", "APPROXIMATE"));
        if (contract.equals("unknown")) approximation.put("uv", Map.of("method", "unrecognized-algorithm"));
        var meta = quality("snapshot");
        meta.put("approximation", approximation);
        var tool = tool(request -> ToolResult.success(envelope(counts(13, 1, 1), List.of(link(1, 13, 1, 1)), meta)));

        var result = data(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 10, null, TRUSTED));

        var warnings = ((List<?>) result.get("warnings")).stream().map(Object::toString)
                .filter(warning -> warning.contains("近似统计口径")).toList();
        switch (contract) {
            case "uv" -> assertThat(warnings).containsExactly("ranking：UV 保留原有近似统计口径");
            case "uip" -> assertThat(warnings).containsExactly("ranking：UIP 保留原有近似统计口径");
            case "both" -> assertThat(warnings).containsExactly("ranking：UV、UIP 保留原有近似统计口径");
            default -> assertThat(warnings).isEmpty();
        }
        assertThat(map(result.get("meta"))).containsEntry("approximation", approximation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "summary", "duplicate", "missingPage", "missingCursor", "sourceCut", "manifestVersion"})
    void invalidOrIncompleteFrozenResultsNeverBecomeAPartialRanking(String error) {
        var tool = tool(request -> {
            var meta = quality("snapshot");
            meta.put("sourceCut", Map.of("window", Map.of("offset", 1)));
            meta.put("manifestVersion", Map.of("window", Map.of("buildId", "first")));
            if (!request.arguments.containsKey("cursor")) {
                if (error.equals("missingCursor")) meta.put("hasMore", true);
                else meta.put("nextCursor", "next");
                return ToolResult.success(envelope(counts(10, 1, 1), List.of(link(1, 4, 1, 1)), meta));
            }
            if (error.equals("missingPage")) return ToolResult.failure("SNAPSHOT_EXPIRED");
            if (error.equals("snapshot")) meta.put("snapshotId", "different");
            if (error.equals("sourceCut")) meta.put("sourceCut", Map.of("window", Map.of("offset", 2)));
            if (error.equals("manifestVersion")) meta.put("manifestVersion", Map.of("window", Map.of("buildId", "second")));
            return ToolResult.success(envelope(counts(error.equals("summary") ? 11 : 10, 1, 1),
                    List.of(link(error.equals("duplicate") ? 1 : 2, 6, 1, 1)), meta));
        });
        var result = data(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 5, null, TRUSTED));
        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result, "rows")).isEmpty();
        assertThat(map(result.get("meta"))).containsEntry("resultComplete", false);
    }

    @Test
    void collectionBudgetStopsPaginationWithoutPublishingThePagesReadSoFar() {
        var tool = tool(request -> {
            int page = calls.size();
            var meta = quality("snapshot");
            meta.put("nextCursor", "cursor-" + page);
            return ToolResult.success(envelope(counts(11, 1, 1), List.of(link(page, 1, 1, 1)), meta));
        });
        var result = data(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 5, null, TRUSTED));
        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result, "rows")).isEmpty();
        assertThat(calls).hasSize(10);
        assertThat(result.get("warnings").toString()).contains("10 页");
    }

    @Test
    void longRangeRankingSubmitsLinkMetricsThenConsumesExactReturnedJobPages() {
        var longPeriod = period("2026-08-15", "2026-09-13");
        var tool = tool(request -> {
            if (request.method.equals("POST")) {
                assertThat(request.arguments).containsEntry("queryKind", "LINK_METRICS");
                return ToolResult.success(Map.of("jobId", "job-rank", "state", "QUEUED"));
            }
            if (!request.path.endsWith("/page")) return ToolResult.success(Map.of("jobId", "job-rank", "state", "SUCCEEDED"));
            var meta = jobQuality("job-rank", "alpha", "LINK_METRICS", longPeriod);
            int index = ((Number) request.arguments.get("pageIndex")).intValue();
            if (index == 0) meta.put("nextPageIndex", 3);
            return ToolResult.success(envelope(counts(10, 1, 1), List.of(link(index == 0 ? 1 : 2, index == 0 ? 4 : 6, 1, 1)), meta));
        });
        var first = data(tool.rankShortLinks("alpha", "2026-08-15", "2026-09-13", "uv", 10, null, TRUSTED));
        assertThat(first).containsEntry("status", "PENDING");
        assertThat(map(first.get("continuation"))).containsEntry("jobId", "job-rank").containsEntry("metric", "uv");
        var second = data(tool.rankShortLinks("alpha", "2026-08-15", "2026-09-13", "uv", 10, "job-rank", TRUSTED));

        assertThat(second).containsEntry("status", "READY");
        assertThat(rows(second, "rows")).hasSize(2);
        assertThat(map(second.get("metrics"))).containsEntry("uv", 1L);
        assertThat(map(second.get("meta"))).doesNotContainKeys("nextCursor", "nextPageIndex");
        assertThat(rows(second, "rows")).allSatisfy(row ->
                assertThat(map(row.get("quality"))).doesNotContainKeys("nextCursor", "nextPageIndex"));
        assertThat(calls).hasSize(4);
        assertThat(calls.get(3).arguments).containsEntry("pageIndex", 3);
    }

    @Test
    void aDailyMetricsJobCannotMasqueradeAsWholeWindowLinkRanking() {
        var longPeriod = period("2026-08-15", "2026-09-13");
        var tool = tool(request -> request.path.endsWith("/page")
                ? ToolResult.success(envelope(counts(10, 1, 1), List.of(link(1, 10, 1, 1)), jobQuality("job-rank", "alpha", "METRICS", longPeriod)))
                : ToolResult.success(Map.of("jobId", "job-rank", "state", "SUCCEEDED")));
        var result = data(tool.rankShortLinks("alpha", "2026-08-15", "2026-09-13", "uv", 10, "job-rank", TRUSTED));

        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result, "rows")).isEmpty();
    }

    @Test
    void anAuthorizedEmptyGroupCanReturnAnEmptyReadyRanking() {
        var tool = tool(request -> ToolResult.success(envelope(counts(0, 0, 0), List.of(), quality("snapshot"))));
        var result = data(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 10, null, TRUSTED));
        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result, "rows")).isEmpty();
        assertThat(map(result.get("meta"))).containsEntry("totalLinks", 0).containsEntry("resultComplete", true);
    }

    @Test
    void invalidArgumentsAndMissingTrustedIdentityNeverReachTheGateway() {
        var tool = tool(request -> ToolResult.success(Map.of()));
        assertThat(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", null, 1, null, TRUSTED).success()).isFalse();
        assertThat(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "cost", 1, null, TRUSTED).success()).isFalse();
        assertThat(tool.rankShortLinks("alpha", "2026-09-07", "2026-09-13", "pv", 51, null, TRUSTED).success()).isFalse();
        assertThat(tool.compareStatistics(GROUPS, List.of(WEEK), null, null).success()).isFalse();
        assertThat(calls).isEmpty();
    }

    @Test
    void aContinuationPlanIdMustMatchBeforeAnyGatewayCall() {
        var tool = tool(request -> ToolResult.success(Map.of()));
        var result = tool.compareStatistics(GROUPS, List.of(WEEK), List.of(), "wrong-plan", TRUSTED);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("different query plan");
        assertThat(calls).isEmpty();
    }

    @Test
    void generatedCallbacksConvertNestedScopesPeriodsAndOptionalJobsThroughTheRealRegistry() {
        var tool = tool(request -> ToolResult.success(envelope(counts(10, 1, 1), List.of(link(1, 10, 1, 1)), quality("snapshot"))));
        var registry = new AgentToolRegistry(MethodToolCallbackProvider.builder().toolObjects(tool).build());
        var compare = registry.findByName("compare_statistics").orElseThrow();
        var arguments = Map.<String, Object>of("scopes", GROUPS, "periods", List.of(WEEK));
        var first = data(compare.execute(new ToolContext("session", "alice", arguments, ALICE)));
        assertThat(first).containsEntry("type", "comparison").containsEntry("status", "READY");
        var withJobs = new LinkedHashMap<>(arguments);
        withJobs.put("jobs", List.of());
        assertThat(compare.execute(new ToolContext("session", "alice", withJobs, ALICE)).success()).isTrue();
        var withPlan = new LinkedHashMap<>(arguments);
        withPlan.put("planId", map(first.get("meta")).get("planId"));
        assertThat(compare.execute(new ToolContext("session", "alice", withPlan, ALICE)).success()).isTrue();

        var rank = registry.findByName("rank_short_links").orElseThrow();
        var ranked = data(rank.execute(new ToolContext("session", "alice", Map.of(
                "gid", "alpha", "startDate", "2026-09-07", "endDate", "2026-09-13", "metric", "pv", "limit", 5), ALICE)));
        assertThat(ranked).containsEntry("type", "ranking").containsEntry("status", "READY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSingleLinkJobRepresentsTheWholeGroupOnlyWithAnExplicitFullGroupProof(boolean completeGroup) {
        var longPeriod = period("2026-08-15", "2026-09-13");
        var tool = tool(request -> {
            if (!request.path.endsWith("/page")) return ToolResult.success(Map.of("jobId", "job-rank", "state", "SUCCEEDED"));
            var meta = jobQuality("job-rank", "alpha", "LINK_METRICS", longPeriod);
            meta.put("groupScopeComplete", completeGroup);
            meta.put("fullShortUrl", "https://example.test/1");
            return ToolResult.success(envelope(counts(10, 1, 1), List.of(link(1, 10, 1, 1)), meta));
        });
        var result = data(tool.rankShortLinks("alpha", "2026-08-15", "2026-09-13", "pv", 10, "job-rank", TRUSTED));
        assertThat(result).containsEntry("status", completeGroup ? "READY" : "INCOMPLETE");
        assertThat(rows(result, "rows")).hasSize(completeGroup ? 1 : 0);
    }

    @Test
    void aFreshAnalysisSubmissionDoesNotReuseAnEarlierSnapshotRequestId() {
        var tool = tool(request -> ToolResult.success(Map.of("jobId", "job-" + calls.size(), "state", "QUEUED")));
        var longPeriod = period("2026-08-15", "2026-09-13");
        tool.compareStatistics(GROUPS, List.of(longPeriod), null, TRUSTED);
        tool.compareStatistics(GROUPS, List.of(longPeriod), null, TRUSTED);
        assertThat(calls).hasSize(4);
        assertThat(calls.stream().map(request -> request.arguments.get("requestId"))).doesNotHaveDuplicates();
    }

    private CampaignStatisticsTools tool(Function<Request, ToolResult> handler) {
        return new CampaignStatisticsTools(new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> arguments) {
                var request = new Request("GET", path, new LinkedHashMap<>(arguments), context);
                calls.add(request);
                return handler.apply(request);
            }

            public ToolResult post(String path, ToolContext context, Map<String, Object> arguments) {
                var request = new Request("POST", path, new LinkedHashMap<>(arguments), context);
                calls.add(request);
                return handler.apply(request);
            }
        }, CLOCK);
    }

    private static Map<String, Object> period(String start, String end) { return Map.of("startDate", start, "endDate", end); }
    private static Map<String, Object> reference(String gid, Map<String, Object> period, String jobId) {
        var reference = new LinkedHashMap<>(period);
        reference.put("gid", gid); reference.put("queryKind", "METRICS"); reference.put("jobId", jobId);
        return reference;
    }
    private static Map<String, Object> counts(long pv, long uv, long uip) { return Map.of("pv", pv, "uv", uv, "uip", uip); }
    private static Map<String, Object> link(long id, long pv, long uv, long uip) {
        var link = new LinkedHashMap<>(counts(pv, uv, uip));
        link.put("linkId", id); link.put("fullShortUrl", "example.test/" + id); return link;
    }
    private static Map<String, Object> quality(String snapshot) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("snapshotId", snapshot); meta.put("availability", "AVAILABLE"); meta.put("completeness", "COMPLETE");
        meta.put("freshness", "FRESH"); meta.put("metricVersion", "click-v1");
        meta.put("collectionQuality", Map.of("status", "COMPLETE"));
        meta.put("approximation", Map.of("uv", Map.of("type", "APPROXIMATE", "algorithm", "uniqCombined64")));
        return meta;
    }
    private static Map<String, Object> jobQuality(String id, String gid, String kind, Map<String, Object> period) {
        var meta = quality(id);
        meta.put("gid", gid); meta.put("queryKind", kind); meta.put("linkIds", List.of(1L, 2L));
        meta.put("groupScopeComplete", true);
        meta.put("requestedStart", LocalDate.parse(period.get("startDate").toString()).atStartOfDay(ZONE).toInstant().toEpochMilli());
        meta.put("requestedEnd", LocalDate.parse(period.get("endDate").toString()).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli());
        return meta;
    }
    private static Map<String, Object> envelope(Map<String, Object> metrics, List<Map<String, Object>> items, Map<String, Object> meta) {
        return Map.of("metrics", Map.of("requested", metrics), "items", items, "meta", meta);
    }
    private static Map<String, Object> data(ToolResult result) { assertThat(result.success()).as(result.message()).isTrue(); return map(result.data()); }
    private static Map<String, Object> comparison(Map<String, Object> data, String metric) {
        return rows(data, "comparisons").stream().filter(row -> metric.equals(row.get("metric"))).findFirst().orElseThrow();
    }
    private static List<Map<String, Object>> rows(Map<String, Object> result, String field) {
        return ((List<?>) result.get(field)).stream().map(CampaignStatisticsToolsTest::map).toList();
    }
    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private record Request(String method, String path, Map<String, Object> arguments, ToolContext context) {}
}
