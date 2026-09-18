package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.harness.checkpoint.AgentGraphThreadKeyFactory;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpoint;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.AgentTool;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolDescriptor;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Conversation tests execute the real graph and its native in-memory checkpoint saver. */
class CampaignConversationTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-12T18:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final AgentPrincipal ALICE = new AgentPrincipal("101", "alice", 3, false);
    private static final String STATS = "get_group_stats";
    private static final String RECORDS = "get_group_access_records";
    private static final String SUBMIT = "submit_statistics_query_job";
    private static final String STATUS = "get_statistics_query_job";
    private static final String PAGE = "get_statistics_query_job_page";
    private final List<Call> calls = new ArrayList<>();
    private com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekChatRequest llmRequest;

    @Test
    void changingPeriodInTheSameConversationRetainsTheAuthorizedObject() {
        var executor = executor(tool(STATS, Map.of()));
        assertThat(ReflectionTestUtils.getField(executor, "checkpointSaver"))
                .isInstanceOf(MemorySaver.class);
        run(executor, "分析 gid=alpha 最近7天");
        run(executor, "再看昨天");

        assertThat(named(STATS)).hasSize(2);
        assertScope(named(STATS).get(0), "alpha", "2026-09-07", "2026-09-13");
        assertScope(named(STATS).get(1), "alpha", "2026-09-12", "2026-09-12");
    }

    @Test
    void changingObjectRetainsTheLastResolvedDateRange() {
        var executor = executor(tool(STATS, Map.of()));
        run(executor, "分析 gid=alpha 最近7天");
        run(executor, "再看 gid=beta");

        assertThat(named(STATS)).hasSize(2);
        assertScope(named(STATS).get(1), "beta", "2026-09-07", "2026-09-13");
    }

    @Test
    void twoObjectsAndTwoPeriodsProduceFourIndependentStatisticalQueries() {
        var executor = executor(tool(STATS, Map.of()));
        run(executor, "分析 gid=alpha 和 gid=beta，分别查看今天和昨天的访问趋势");

        assertThat(named(STATS)).hasSize(4);
        assertThat(named(STATS).stream().map(CampaignConversationTest::scopeKey))
                .containsExactlyInAnyOrder(
                        "alpha:2026-09-13:2026-09-13", "alpha:2026-09-12:2026-09-12",
                        "beta:2026-09-13:2026-09-13", "beta:2026-09-12:2026-09-12");
        assertThat(named(STATS)).allSatisfy(call -> assertThat(call.principal()).isEqualTo(ALICE));
    }

    @ParameterizedTest
    @MethodSource("differentPrincipals")
    void usernameTenantOrAuthorizationVersionCannotInheritAnotherPrincipalsConversation(
            AgentPrincipal other) {
        var executor = executor(tool(STATS, Map.of()), tool("list_groups", List.of()));
        run(executor, "分析 gid=alpha 最近7天");
        var result = executor.execute(request("shared-session", "再看昨天", other));

        assertThat(named(STATS)).hasSize(1);
        assertThat(result.cards().toString()).doesNotContain("alpha");
    }

    static Stream<AgentPrincipal> differentPrincipals() {
        return Stream.of(new AgentPrincipal("101", "bob", 3, false),
                new AgentPrincipal("202", "alice", 3, false),
                new AgentPrincipal("101", "alice", 4, false));
    }

    @Test
    void aDifferentSessionForTheSamePrincipalDoesNotInheritScope() {
        var executor = executor(tool(STATS, Map.of()), tool("list_groups", List.of()));
        run(executor, "分析 gid=alpha 最近7天");
        executor.execute(request("new-session", "再看昨天", ALICE));
        assertThat(named(STATS)).hasSize(1);
    }

    @Test
    void visitTrendDoesNotFetchIndividualAccessRecords() {
        var executor = executor(tool(STATS, Map.of()), tool(RECORDS, Map.of()));
        run(executor, "分析 gid=alpha 最近7天的访问趋势");
        assertThat(named(STATS)).hasSize(1);
        assertThat(named(RECORDS)).isEmpty();
    }

    @Test
    void nextAccessPageUsesReturnedCursorAndFrozenSnapshotWithoutReenteringScope() {
        var executor = executor(tool(RECORDS, context -> {
            if (context.arguments().containsKey("cursor")) return ToolResult.success(accessPage(null));
            return ToolResult.success(accessPage("opaque-next-cursor"));
        }));
        run(executor, "查看 gid=alpha 最近7天访问明细 size=5");
        run(executor, "下一页");

        assertThat(named(RECORDS)).hasSize(2);
        Call next = named(RECORDS).get(1);
        assertScope(next, "alpha", "2026-09-07", "2026-09-13");
        assertThat(next.arguments()).containsEntry("snapshotId", "snapshot-alpha")
                .containsEntry("cursor", "opaque-next-cursor");
        assertThat(((Number) next.arguments().get("current")).longValue()).isEqualTo(2L);
        assertThat(((Number) next.arguments().get("size")).longValue()).isEqualTo(5L);
    }

    @Test
    void exhaustedAccessPageDoesNotRestartAtPageOne() {
        var executor = executor(tool(RECORDS, accessPage(null)));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        var result = run(executor, "下一页");

        assertThat(named(RECORDS)).hasSize(1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void cursorWithoutItsFrozenSnapshotCannotBecomeAContinuation() {
        var executor = executor(tool(RECORDS, Map.of("items", List.of(), "hasMore", true,
                "meta", Map.of("nextCursor", "orphan-cursor"))));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        var result = run(executor, "下一页");

        assertThat(named(RECORDS)).hasSize(1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void nextAccessPageCannotGuessBetweenMultiplePriorResultSets() {
        var executor = executor(tool(RECORDS, accessPage("opaque-next-cursor")));
        run(executor, "查看 gid=alpha 和 gid=beta 最近7天访问明细");
        assertThat(named(RECORDS)).hasSize(2);
        var result = run(executor, "下一页");

        assertThat(named(RECORDS)).hasSize(2);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void changingTheAccessObjectDoesNotReuseItsPredecessorsCursor() {
        var executor = executor(tool(RECORDS, accessPage("opaque-next-cursor")));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        run(executor, "再看 gid=beta 访问明细");

        assertThat(named(RECORDS)).hasSize(2);
        Call changed = named(RECORDS).get(1);
        assertScope(changed, "beta", "2026-09-07", "2026-09-13");
        assertThat(changed.arguments()).doesNotContainKeys("cursor", "snapshotId");
        assertThat(((Number) changed.arguments().getOrDefault("current", 1)).longValue()).isEqualTo(1L);
    }

    @Test
    void changingTheAccessPeriodDoesNotReuseItsPredecessorsCursor() {
        var executor = executor(tool(RECORDS, accessPage("opaque-next-cursor")));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        run(executor, "再看昨天访问明细");

        assertThat(named(RECORDS)).hasSize(2);
        Call changed = named(RECORDS).get(1);
        assertScope(changed, "alpha", "2026-09-12", "2026-09-12");
        assertThat(changed.arguments()).doesNotContainKeys("cursor", "snapshotId");
    }

    @Test
    void rejectedScopeChangeCannotResumeAnOlderCursorInAThirdTurn() {
        var executor = executor(tool(RECORDS, accessPage("opaque-next-cursor")));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        var changed = run(executor, "下一页 gid=beta");
        var next = run(executor, "下一页");
        assertThat(named(RECORDS)).hasSize(1);
        assertThat(changed.warnings()).isNotEmpty();
        assertThat(next.warnings()).isNotEmpty();
    }

    @Test
    void readyAsyncJobAutomaticallyFetchesItsFirstPageInTheContinuationTurn() {
        var executor = executor(tool(SUBMIT, job("QUEUED")), tool(STATUS, job("SUCCEEDED")),
                tool(PAGE, jobPage(3)));
        run(executor, "分析 gid=alpha 最近30天");
        run(executor, "查看分析结果");

        assertThat(calls).extracting(Call::name).containsExactly(SUBMIT, STATUS, PAGE);
        assertThat(named(STATUS).get(0).arguments()).containsEntry("jobId", "job-alpha");
        assertThat(named(PAGE).get(0).arguments()).containsEntry("jobId", "job-alpha");
        assertThat(((Number) named(PAGE).get(0).arguments().get("pageIndex")).intValue()).isZero();
    }

    @Test
    void pendingAsyncJobChecksOnceWithoutPollingOrResubmission() {
        var executor = executor(tool(SUBMIT, job("QUEUED")), tool(STATUS, job("RUNNING")),
                tool(PAGE, jobPage(null)));
        run(executor, "分析 gid=alpha 最近30天");
        var result = run(executor, "查看分析结果");

        assertThat(calls).extracting(Call::name).containsExactly(SUBMIT, STATUS);
        assertThat(result.answer()).contains("PENDING");
    }

    @Test
    void checkingSeveralPendingJobsRequiresTheUserToIdentifyTheResult() {
        var executor = executor(tool(SUBMIT, context -> ToolResult.success(Map.of(
                "jobId", "job-" + context.arguments().get("gid"), "state", "QUEUED"))),
                tool(STATUS, job("SUCCEEDED")), tool(PAGE, jobPage(null)));
        run(executor, "分析 gid=alpha 和 gid=beta 最近30天");
        var result = run(executor, "查看分析结果");

        assertThat(named(SUBMIT)).hasSize(2);
        assertThat(named(STATUS)).isEmpty();
        assertThat(named(PAGE)).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void asyncNextPageUsesReturnedIndexInsteadOfInventingTheFollowingInteger() {
        var executor = executor(tool(SUBMIT, job("QUEUED")), tool(STATUS, job("SUCCEEDED")),
                tool(PAGE, context -> ToolResult.success(
                        jobPage(((Number) context.arguments().get("pageIndex")).intValue() == 0
                                ? 3 : null))));
        run(executor, "分析 gid=alpha 最近30天");
        run(executor, "查看分析结果");
        run(executor, "下一页");

        assertThat(named(PAGE)).hasSize(2);
        assertThat(named(PAGE).get(1).arguments()).containsEntry("jobId", "job-alpha");
        assertThat(((Number) named(PAGE).get(1).arguments().get("pageIndex")).intValue()).isEqualTo(3);
        assertThat(named(SUBMIT)).hasSize(1);
    }

    @Test
    void exhaustedAsyncPageDoesNotRereadPageZero() {
        var executor = executor(tool(SUBMIT, job("QUEUED")), tool(STATUS, job("SUCCEEDED")),
                tool(PAGE, jobPage(null)));
        run(executor, "分析 gid=alpha 最近30天");
        run(executor, "查看分析结果");
        var result = run(executor, "下一页");

        assertThat(named(PAGE)).hasSize(1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void changingScopePreventsImplicitContinuationOfAnOlderJob() {
        var executor = executor(tool(SUBMIT, job("QUEUED")), tool(STATUS, job("SUCCEEDED")),
                tool(PAGE, jobPage(1)), tool(STATS, Map.of()));
        run(executor, "分析 gid=alpha 最近30天");
        run(executor, "分析 gid=beta 昨天");
        run(executor, "查看分析结果");

        assertThat(named(SUBMIT)).hasSize(1);
        assertThat(named(STATUS)).isEmpty();
        assertThat(named(PAGE)).isEmpty();
        assertThat(named(STATS)).isNotEmpty().allSatisfy(
                call -> assertScope(call, "beta", "2026-09-12", "2026-09-12"));
    }

    @Test
    void failedAccessQueryCannotCreatePaginationContext() {
        var executor = executor(tool(RECORDS, context -> ToolResult.failure("Analytics unavailable")));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        var result = run(executor, "下一页");

        assertThat(named(RECORDS)).hasSize(1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void compoundComparisonPreservesItsReferencesAndRendersStructuredEvidence() {
        var executor = executor(tool("compare_statistics", context -> {
            if (context.arguments().containsKey("jobs")) return ToolResult.success(Map.of(
                    "type", "comparison", "status", "READY", "rows", List.of(Map.of("pv", 5)),
                    "warnings", List.of("数据部分完整")));
            var continuation = new LinkedHashMap<>(context.arguments());
            continuation.put("jobs", List.of(Map.of("gid", "alpha", "jobId", "server-issued")));
            return ToolResult.success(Map.of("type", "comparison", "status", "PENDING",
                    "rows", List.of(), "continuation", continuation));
        }));
        String prefix = "分析范围：分组「默认组」；gid=alpha;\n";
        var initial = run(executor, prefix + "比较 gid=beta 今天和昨天访问统计");
        var ready = run(executor, prefix + "查看分析结果");
        assertThat(named("compare_statistics")).hasSize(2);
        assertThat(named("compare_statistics").get(1).arguments()).containsKey("jobs");
        assertThat(initial.cards().toString()).doesNotContain("server-issued", "continuation");
        assertThat(ready.cards().toString()).contains("comparison", "READY", "pv=5");
        assertThat(ready.warnings()).contains("数据部分完整");
    }

    @Test
    void changingTheUiSelectionDiscardsFrozenPagination() {
        var executor = executor(tool(RECORDS, accessPage("opaque-next-cursor")));
        run(executor, "分析范围：分组「原分组」；gid=alpha;\n查看最近7天访问明细");
        run(executor, "分析范围：分组「新分组」；gid=beta;\n下一页");
        var result = run(executor, "下一页");
        assertThat(named(RECORDS)).hasSize(1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void reanalysisSubmitsANewRequestButFollowingTheResultDoesNot() {
        var executor = executor(tool(SUBMIT, job("QUEUED")), tool(STATUS, job("RUNNING")));
        run(executor, "分析 gid=alpha 最近30天");
        run(executor, "重新分析 gid=alpha 最近30天");
        run(executor, "查看分析结果");
        assertThat(named(SUBMIT)).hasSize(2);
        assertThat(named(SUBMIT).get(0).arguments().get("requestId"))
                .isNotEqualTo(named(SUBMIT).get(1).arguments().get("requestId"));
        assertThat(named(STATUS)).hasSize(1);
    }

    @Test
    void drillFollowupCarriesFiltersWithoutReusingAFrozenQuery() {
        var executor = executor(tool("get_dimension_breakdown", Map.of("type", "dimension_breakdown", "status", "READY")));
        run(executor, "分析 gid=alpha 最近7天，按省份和设备下钻");
        run(executor, "只看浙江，再按设备和浏览器下钻");
        run(executor, "清除筛选，再看 gid=beta");
        assertThat(named("get_dimension_breakdown")).hasSize(3);
        var second = named("get_dimension_breakdown").get(1).arguments();
        assertThat(second).containsEntry("gid", "alpha").containsEntry("dimensions", List.of("device", "browser"))
                .containsEntry("filters", List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江"))));
        assertThat(named("get_dimension_breakdown").get(2).arguments()).containsEntry("gid", "beta")
                .containsEntry("filters", List.of()).doesNotContainKeys("jobId", "snapshotId", "cursor");
    }

    @Test
    void fullJointRowsReachTheUiButTheExplanationUsesALabeledSample() {
        var rows = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> Map.of("bucket", "bucket-" + index, "pv", 1)).toList();
        var executor = executor(tool("get_dimension_breakdown", Map.of("type", "dimension_breakdown",
                "status", "READY", "rows", rows, "metrics", Map.of("pv", 100))));
        var result = run(executor, "分析 gid=alpha 昨天，按省份和设备下钻");
        assertThat(result.cards().toString()).contains("bucket-99");
        assertThat(llmRequest.messages().get(1).content()).contains("HEAD_SAMPLE_ONLY", "bucket-49")
                .doesNotContain("bucket-99");
    }

    @Test
    void compoundAnalysisReferenceIsDiscardedWhenScopeChanges() {
        var executor = executor(tool("rank_short_links", context -> {
            var continuation = new LinkedHashMap<>(context.arguments());
            continuation.put("jobId", "server-issued");
            return ToolResult.success(Map.of("type", "ranking", "status", "PENDING", "continuation", continuation));
        }), tool(STATS, Map.of()));
        run(executor, "查看 gid=alpha 最近30天 PV 排名");
        run(executor, "分析 gid=beta 昨天访问统计");
        run(executor, "查看分析结果");
        assertThat(named("rank_short_links")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"继续分析，排名 metric=uv limit=5", "继续分析，metric=uv limit=5", "继续分析，按 UV 排名前 5"})
    void changedRankingOptionsPlanANewQueryInsteadOfReusingTheFrozenRanking(String message) {
        var executor = executor(pendingOperation("rank_short_links", "ranking"));
        run(executor, "查看 gid=alpha 最近30天 PV 排名前10");
        run(executor, message);
        assertThat(named("rank_short_links")).hasSize(2);
        assertThat(named("rank_short_links").get(1).arguments()).containsEntry("metric", "uv")
                .containsEntry("limit", 5).doesNotContainKey("jobId");
    }

    @ParameterizedTest
    @CsvSource({"继续分析，改成UV,uv,10", "继续分析，前5,pv,5"})
    void aNaturalChangeToOnlyTheMetricOrCountStartsAFreshRanking(String message, String metric, int limit) {
        var executor = executor(pendingOperation("rank_short_links", "ranking"));
        run(executor, "查看 gid=alpha 最近30天 PV 排名前10");
        run(executor, message);
        assertThat(named("rank_short_links")).hasSize(2);
        assertThat(named("rank_short_links").get(1).arguments()).containsEntry("metric", metric)
                .containsEntry("limit", limit).doesNotContainKey("jobId");
    }

    @Test
    void aChangedPeriodRetainsRankingIntentButDiscardsThePreviousJob() {
        var executor = executor(pendingOperation("rank_short_links", "ranking"));
        run(executor, "查看 gid=alpha 最近30天 UV 排名前5");
        run(executor, "继续分析昨天");
        assertThat(named("rank_short_links")).hasSize(2);
        assertScope(named("rank_short_links").get(1), "alpha", "2026-09-12", "2026-09-12");
        assertThat(named("rank_short_links").get(1).arguments()).containsEntry("metric", "uv")
                .containsEntry("limit", 5).doesNotContainKey("jobId");
    }

    @Test
    void aChangedPeriodRetainsCrossObjectComparisonWithoutFrozenJobs() {
        var executor = executor(tool("compare_statistics", context -> {
            var continuation = new LinkedHashMap<>(context.arguments());
            continuation.put("jobs", List.of(Map.of("jobId", "server-issued")));
            return ToolResult.success(Map.of("type", "comparison", "status", "PENDING", "continuation", continuation));
        }));
        run(executor, "对比 gid=alpha 和 gid=beta 最近30天访问统计");
        run(executor, "继续分析昨天");
        assertThat(named("compare_statistics")).hasSize(2);
        assertThat(named("compare_statistics").get(1).arguments()).containsEntry("scopes", List.of(Map.of("gid", "alpha"), Map.of("gid", "beta")))
                .containsEntry("periods", List.of(Map.of("startDate", "2026-09-12", "endDate", "2026-09-12")))
                .doesNotContainKey("jobs");
    }

    @Test
    void askingForANextPageWithADifferentDayStillCannotReuseAFrozenCursor() {
        var executor = executor(tool(RECORDS, accessPage("opaque-next-cursor")));
        run(executor, "查看 gid=alpha 最近7天访问明细");
        var result = run(executor, "下一页 昨天");
        assertThat(named(RECORDS)).hasSize(1);
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void changedExplicitDrillOptionsAreNotHiddenByTheContinuationPhrase() {
        var executor = executor(pendingOperation("get_dimension_breakdown", "dimension_breakdown"));
        run(executor, "分析 gid=alpha 最近30天，按省份和设备下钻");
        run(executor, "继续分析，dimensions=province,browser device=PC");
        assertThat(named("get_dimension_breakdown")).hasSize(2);
        assertThat(named("get_dimension_breakdown").get(1).arguments())
                .containsEntry("dimensions", List.of("province", "browser"))
                .containsEntry("filters", List.of(Map.of("dimension", "device", "operator", "IN", "values", List.of("PC"))))
                .doesNotContainKey("jobId");
    }

    @Test
    void changedNaturalDrillOptionsAreNotHiddenByTheContinuationPhrase() {
        var executor = executor(pendingOperation("get_dimension_breakdown", "dimension_breakdown"));
        run(executor, "分析 gid=alpha 最近30天，按省份和设备下钻");
        run(executor, "继续分析，只看浙江，再按设备和浏览器下钻");
        assertThat(named("get_dimension_breakdown")).hasSize(2);
        assertThat(named("get_dimension_breakdown").get(1).arguments())
                .containsEntry("dimensions", List.of("device", "browser"))
                .containsEntry("filters", List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江"))))
                .doesNotContainKey("jobId");
    }

    @Test
    void clearingFiltersStartsANewQueryWithoutItsPreviousJob() {
        var executor = executor(pendingOperation("get_dimension_breakdown", "dimension_breakdown"));
        run(executor, "分析 gid=alpha 最近30天，按省份和设备下钻 device=PC");
        run(executor, "继续分析，清除筛选");
        assertThat(named("get_dimension_breakdown")).hasSize(2);
        assertThat(named("get_dimension_breakdown").get(1).arguments())
                .containsEntry("dimensions", List.of("province", "device"))
                .containsEntry("filters", List.of()).doesNotContainKey("jobId");
    }

    @Test
    void merelyViewingTheNamedRankingResultStillUsesItsServerIssuedJob() {
        var executor = executor(pendingOperation("rank_short_links", "ranking"));
        run(executor, "查看 gid=alpha 最近30天 PV 排名前10");
        run(executor, "继续查看排名结果");
        assertThat(named("rank_short_links")).hasSize(2);
        assertThat(named("rank_short_links").get(1).arguments()).containsEntry("jobId", "server-issued")
                .containsEntry("metric", "pv").containsEntry("limit", 10);
    }

    @Test
    void canonicalFilterContinuationDoesNotDuplicateOrRetainCompletedOperations() {
        var executor = executor(tool("get_dimension_breakdown", context -> {
            if (named("get_dimension_breakdown").size() == 3)
                return ToolResult.success(Map.of("type", "dimension_breakdown", "status", "READY", "rows", List.of()));
            var continuation = new LinkedHashMap<>(context.arguments());
            continuation.put("filters", List.of(Map.of("dimension", "device", "operator", "IN", "values", List.of("MOBILE", "PC"))));
            continuation.put("jobId", "server-issued");
            return ToolResult.success(Map.of("type", "dimension_breakdown", "status", "PENDING", "continuation", continuation));
        }));
        run(executor, "分析 gid=alpha 最近30天，按省份和设备下钻 device=PC,MOBILE");
        assertThat(named("get_dimension_breakdown").get(0).arguments())
                .containsEntry("filters", List.of(Map.of("dimension", "device", "operator", "IN", "values", List.of("PC", "MOBILE"))));
        run(executor, "查看分析结果");
        run(executor, "查看分析结果");
        run(executor, "查看分析结果");
        assertThat(named("get_dimension_breakdown")).hasSize(3);
    }

    @Test
    void largeProvenanceIsBoundedBeforeNativeCheckpointCloningAcrossTwoRealGraphTurns() throws Exception {
        var rawMeta = largeProvenanceMetadata();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "ranking");
        payload.put("status", "READY");
        payload.put("meta", rawMeta);
        payload.put("metrics", Map.of("pv", 13, "uv", 1, "uip", 1));
        payload.put("rows", List.of(
                Map.of("rank", 1, "linkId", "second-link", "pv", 8, "uv", 1, "uip", 1, "quality", rawMeta),
                Map.of("rank", 2, "linkId", "first-link", "pv", 5, "uv", 1, "uip", 1, "quality", rawMeta)));
        payload.put("warnings", List.of("当前仅为部分采集结果"));
        var executor = executor(tool("rank_short_links", payload));
        int rawBytes = new ObjectMapper().writeValueAsBytes(payload).length;
        assertThat(rawBytes).isGreaterThan(1_000_000);

        var first = executor.execute(new CampaignAnalysisGraphRequest(
                "shared-session", "alice", "查看 gid=alpha 最近7天 PV 排名前10", "large-round-1", ALICE));
        var second = executor.execute(new CampaignAnalysisGraphRequest(
                "shared-session", "alice", "继续分析昨天", "large-round-2", ALICE));

        assertThat(named("rank_short_links")).hasSize(2);
        assertScope(named("rank_short_links").get(1), "alpha", "2026-09-12", "2026-09-12");
        for (var result : List.of(first, second)) {
            assertThat(result.warnings()).contains("当前仅为部分采集结果");
            var card = asMap(result.cards().get(0));
            assertThat((List<?>) card.get("rows")).hasSize(2);
            assertThat(asMap(card.get("metrics"))).containsEntry("pv", 13).containsEntry("uv", 1);
            var meta = asMap(card.get("meta"));
            assertThat(meta).containsEntry("snapshotId", "snapshot-large")
                    .containsEntry("completeness", "PARTIAL").containsEntry("freshness", "STALE")
                    .containsEntry("collectionQuality", rawMeta.get("collectionQuality"))
                    .containsEntry("futureQualityExtension", rawMeta.get("futureQualityExtension"));
            assertThat(asMap(meta.get("sourceCut"))).containsEntry("compacted", true)
                    .containsEntry("entryCount", 2016);
            assertThat(asMap(asMap(meta.get("sourceCut")).get("auditReference")))
                    .containsEntry("snapshotId", "snapshot-large");
            assertThat(result.dataSources().toString()).contains("rank_short_links", "snapshot-large", "toolCalls[0]")
                    .doesNotContain("sourceCut", "manifestVersion", "second-link");
        }
        assertThat(llmRequest.messages().get(1).content()).contains("INTERNAL_PROVENANCE_SUMMARY", "second-link")
                .doesNotContain("internal-offset-marker");
        assertThat(asMap(rawMeta.get("sourceCut"))).hasSize(2016);

        var serializer = AgentStateSerializerFactory.create();
        int maximumSerializedBytes = 0;
        var checkpoints = nativeCheckpoints(executor);
        assertThat(checkpoints).hasSizeGreaterThanOrEqualTo(10);
        for (Checkpoint checkpoint : checkpoints) {
            byte[] serialized = serializer.dataToBytes(checkpoint.getState());
            maximumSerializedBytes = Math.max(maximumSerializedBytes, serialized.length);
            assertThat(serialized.length).isLessThan(150_000);
            // This is the production serializer used by GraphRunnerContext.cloneState,
            // including Alibaba type metadata and our contextual list deserializer.
            var restored = serializer.cloneObject(checkpoint.getState());
            assertThat(restored.data().get("traceId")).isEqualTo(checkpoint.getState().get("traceId"));
            assertThat(restored.data().toString()).doesNotContain("internal-offset-marker");
        }
        var secondStart = checkpoints.stream().filter(checkpoint -> "__START__".equals(checkpoint.getNodeId())
                        && "large-round-2".equals(checkpoint.getState().get("traceId")))
                .findFirst().orElseThrow();
        for (String key : List.of("cards", "toolCalls", "dataSources", "derivedInsightCards", "toolExecutions"))
            assertThat((List<?>) secondStart.getState().get(key)).as(key).isEmpty();
        assertThat(secondStart.getState()).containsEntry("answer", "").containsEntry("llmDataSource", Map.of());
        assertThat(asMap(secondStart.getState().get("conversationContext")).get("scopes").toString()).contains("alpha");
        System.out.printf("Campaign evidence bound: raw ranking=%d bytes, maximum native checkpoint=%d bytes%n",
                rawBytes, maximumSerializedBytes);
    }

    @Test
    void normalStatisticsWithoutATypeKeepMetricsAndPartialQualityThroughTheRealGraph() {
        var meta = largeProvenanceMetadata();
        var metrics = Map.of("requested", Map.of("pv", 13, "uv", 1, "uip", 1));
        var executor = executor(tool(STATS, Map.of("metrics", metrics, "meta", meta)));
        var result = run(executor, "分析 gid=alpha 昨天访问趋势");
        var execution = asMap(result.toolCalls().get(0));
        assertThat(execution).containsEntry("success", true);
        var data = asMap(execution.get("data"));
        assertThat(data).containsEntry("metrics", metrics).doesNotContainKey("type");
        assertThat(asMap(data.get("meta"))).containsEntry("completeness", "PARTIAL")
                .containsEntry("snapshotId", "snapshot-large");
        assertThat(asMap(asMap(data.get("meta")).get("manifestVersion")))
                .containsEntry("compacted", true).containsEntry("entryCount", 2016);
        assertThat(result.cards().toString()).contains("部分采集结果");
    }

    @Test
    void missingQualityIsNotUpgradedByCompactionOrSourceReferences() {
        var envelope = Map.of("metrics", Map.of("requested", Map.of("pv", 13, "uv", 1, "uip", 1)),
                "meta", Map.of("sourceCut", largeProvenanceMetadata().get("sourceCut")));
        var result = run(executor(tool(STATS, envelope)), "分析 gid=alpha 昨天访问趋势");
        var data = asMap(asMap(result.toolCalls().get(0)).get("data"));
        assertThat(asMap(data.get("meta"))).doesNotContainKeys("availability", "completeness", "snapshotId");
        assertThat(result.cards().toString()).contains("尚无带有效快照的可用统计指标");
        assertThat(result.dataSources().toString()).doesNotContain("AVAILABLE", "COMPLETE");
    }

    @Test
    void productionSerializerRetainsAllFiveThousandDimensionRowsAndWholeWindowUv() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 5000; index++) {
            var province = new LinkedHashMap<String, Object>();
            province.put("value", index == 0 ? null : "province-" + index % 32);
            province.put("state", index == 0 ? "UNKNOWN" : "KNOWN");
            rows.add(Map.of("dimensions", Map.of("province", province,
                    "refererDomain", Map.of("value", "source-" + index + ".example", "state", "KNOWN")),
                    "pv", 1, "uv", 1, "uip", 1, "pvRatio", 1D / 5000));
        }
        var metrics = Map.of("pv", 5000, "uv", 1, "uip", 1, "ratioDenominator", 5000);
        var meta = largeProvenanceMetadata();
        meta.put("totalRows", 5000);
        meta.put("dimensionQuality", Map.of("province", Map.of("status", "PARTIAL", "unknownCount", 1)));
        var payload = Map.of("type", "dimension_breakdown", "status", "READY", "rows", rows,
                "metrics", metrics, "meta", meta);
        var restored = AgentStateSerializerFactory.create().cloneObject(
                Map.of("toolExecutions", List.of(Map.of("name", "get_dimension_breakdown",
                        "success", true, "data", CampaignEvidenceContext.compact(payload)))));
        var execution = asMap(((List<?>) restored.data().get("toolExecutions")).get(0));
        var result = asMap(execution.get("data"));
        assertThat((List<?>) result.get("rows")).hasSize(5000).isEqualTo(rows);
        assertThat(result.get("metrics")).isEqualTo(metrics);
        assertThat(asMap(result.get("meta"))).containsEntry("totalRows", 5000)
                .containsEntry("dimensionQuality", meta.get("dimensionQuality"));
    }

    private static Map<String, Object> largeProvenanceMetadata() {
        var cuts = new LinkedHashMap<String, Object>();
        var versions = new LinkedHashMap<String, Object>();
        for (int window = 0; window < 2016; window++) {
            var offsets = new LinkedHashMap<String, Object>();
            for (int partition = 0; partition < 24; partition++)
                offsets.put("partition-" + partition, Map.of("offset", 1_000_000L + window,
                        "source", "internal-offset-marker"));
            String key = Long.toString(1_700_000_000_000L + window * 300_000L);
            cuts.put(key, offsets);
            versions.put(key, Map.of("buildId", "build-2016-window-fixture-" + window, "revision", 3));
        }
        var meta = new LinkedHashMap<String, Object>();
        meta.put("sourceCut", cuts);
        meta.put("manifestVersion", versions);
        meta.put("snapshotId", "snapshot-large");
        meta.put("recoveryEpoch", "epoch-large");
        meta.put("availability", "AVAILABLE");
        meta.put("completeness", "PARTIAL");
        meta.put("freshness", "STALE");
        meta.put("collectionQuality", Map.of("knownCount", 10, "unknownCount", 3));
        meta.put("futureQualityExtension", Map.of("reason", "must-survive-unchanged"));
        return meta;
    }

    private static List<Checkpoint> nativeCheckpoints(DefaultCampaignAnalysisGraphExecutor executor) {
        var saver = (MemorySaver) ReflectionTestUtils.getField(executor, "checkpointSaver");
        var properties = new AgentProperties();
        String thread = AgentGraphThreadKeyFactory.create(properties.getGraph().getName(),
                properties.getGraph().getVersion(), "alice:101:3:shared-session");
        return new ArrayList<>(saver.list(RunnableConfig.builder().threadId(thread).build()));
    }

    private static Map<String, Object> asMap(Object value) {
        var result = new LinkedHashMap<String, Object>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private AgentTool pendingOperation(String name, String type) {
        return tool(name, context -> {
            var continuation = new LinkedHashMap<>(context.arguments());
            continuation.put("jobId", "server-issued");
            return ToolResult.success(Map.of("type", type, "status", "PENDING", "continuation", continuation));
        });
    }

    private AgentRunResult run(DefaultCampaignAnalysisGraphExecutor executor, String message) {
        return executor.execute(request("shared-session", message, ALICE));
    }

    private static CampaignAnalysisGraphRequest request(
            String session, String message, AgentPrincipal principal) {
        return new CampaignAnalysisGraphRequest(session, principal.username(), message, "trace", principal);
    }

    private List<Call> named(String name) {
        return calls.stream().filter(call -> call.name().equals(name)).toList();
    }

    private static String scopeKey(Call call) {
        return call.arguments().get("gid") + ":" + call.arguments().get("startDate")
                + ":" + call.arguments().get("endDate");
    }

    private static void assertScope(Call call, String gid, String startDate, String endDate) {
        assertThat(call.arguments()).containsEntry("gid", gid)
                .containsEntry("startDate", startDate).containsEntry("endDate", endDate);
    }

    private static Map<String, Object> accessPage(String nextCursor) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("snapshotId", "snapshot-alpha");
        meta.put("availability", "AVAILABLE");
        if (nextCursor != null) meta.put("nextCursor", nextCursor);
        return Map.of("items", List.of(Map.of("eventId", "event-1")),
                "hasMore", nextCursor != null, "meta", meta);
    }

    private static Map<String, Object> job(String state) {
        return Map.of("jobId", "job-alpha", "state", state);
    }

    private static Map<String, Object> jobPage(Integer nextPageIndex) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("availability", "AVAILABLE");
        meta.put("snapshotId", "job-snapshot");
        if (nextPageIndex != null) meta.put("nextPageIndex", nextPageIndex);
        return Map.of("items", List.of(Map.of("date", "2026-09-13", "pv", 5L)), "meta", meta);
    }

    private AgentTool tool(String name, Object data) {
        return tool(name, ignored -> ToolResult.success(data));
    }

    private AgentTool tool(String name, Function<ToolContext, ToolResult> result) {
        return new AgentTool() {
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "conversation fixture", Map.of());
            }

            public ToolResult execute(ToolContext context) {
                calls.add(new Call(name, new LinkedHashMap<>(context.arguments()), context.principal()));
                return result.apply(context);
            }
        };
    }

    private DefaultCampaignAnalysisGraphExecutor executor(AgentTool... tools) {
        GraphCheckpointStore checkpoints = new GraphCheckpointStore() {
            public void save(GraphCheckpoint checkpoint) {}

            public Optional<GraphCheckpoint> loadLatest(String thread, String graph, String version) {
                return Optional.empty();
            }
        };
        return new DefaultCampaignAnalysisGraphExecutor(
                request -> {
                    llmRequest = request;
                    return new DeepSeekChatResponse("id", "model", "基于工具结果的分析。", "stop", null);
                },
                checkpoints, new AgentProperties(), new AgentToolRegistry(List.of(tools)), CLOCK);
    }

    private record Call(String name, Map<String, Object> arguments, AgentPrincipal principal) {}
}
