package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CampaignAnalysisPlannerTest {
    private final CampaignAnalysisPlanner planner = new CampaignAnalysisPlanner(
            Clock.fixed(Instant.parse("2026-09-18T18:30:00Z"), ZoneId.of("Asia/Shanghai")));

    @ParameterizedTest
    @ValueSource(strings = {"继续分析，metric=uv limit=5", "metric=uv limit=5", "改成UV，limit=5", "继续分析，按 UV 排名前 5"})
    void rankingConfigurationChangesRetainTheRankingIntentAndOriginalWindow(String followup) {
        var first = plan("gid=alpha 最近30天短链排名 metric=pv limit=10");
        var next = planner.plan(followup, first.context(), null);
        assertThat(next.warnings()).isEmpty();
        assertThat(next.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("rank_short_links");
        assertThat(next.invocations().get(0).arguments()).containsEntry("gid", "alpha")
                .containsEntry("metric", "uv").containsEntry("limit", 5)
                .containsEntry("startDate", first.invocations().get(0).arguments().get("startDate"))
                .containsEntry("endDate", first.invocations().get(0).arguments().get("endDate"))
                .doesNotContainKeys("jobId", "jobs");
    }

    @Test
    void rankingParametersDoNotOverrideAnExplicitTrendRequest() {
        var first = plan("gid=alpha 最近7天短链排名 metric=pv limit=10");
        var next = planner.plan("继续分析访问趋势 metric=uv", first.context(), null);
        assertThat(next.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_group_stats");
    }

    @Test
    void onlyChangingTheNaturalRankingCountKeepsItsMetricAndWindow() {
        var first = plan("gid=alpha 最近30天短链排名 metric=uv limit=10");
        var next = planner.plan("继续分析，前5", first.context(), null);
        assertThat(next.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("rank_short_links");
            assertThat(call.arguments()).containsEntry("metric", "uv").containsEntry("limit", 5)
                    .containsEntry("startDate", first.invocations().get(0).arguments().get("startDate"));
        });
    }

    @Test
    void rankingByMetricDoesNotConsumeAGenuineJointDimensionClause() {
        var plan = plan("gid=alpha 昨天，按 UV 排名，并按省份和设备下钻");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("不能", "排名"));
    }

    @Test
    void todayAndYesterdayHaveIndependentPeriodsAndDoNotFetchRecordsForVisitTrends() {
        var plan = plan("分析 gid=alpha，比较今天和昨天的访问趋势");
        assertThat(plan.needsGroups()).isFalse();
        assertThat(plan.warnings()).isEmpty();
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        var periods = rows(plan.invocations().get(0).arguments().get("periods"));
        assertThat(periods.get(0)).containsEntry("startDate", "2026-09-19")
                .containsEntry("endDate", "2026-09-19");
        assertThat(periods.get(1)).containsEntry("startDate", "2026-09-18")
                .containsEntry("endDate", "2026-09-18");
    }

    @Test
    void allFourExplicitDatesBecomeTwoRangesInsteadOfDiscardingTheSecond() {
        var plan = plan("分析 gid=alpha，对比2026-09-01至2026-09-07和2026-09-08至2026-09-14");
        assertThat(plan.invocations()).hasSize(1);
        var periods = rows(plan.invocations().get(0).arguments().get("periods"));
        assertThat(periods.get(0)).containsEntry("startDate", "2026-09-01")
                .containsEntry("endDate", "2026-09-07");
        assertThat(periods.get(1)).containsEntry("startDate", "2026-09-08")
                .containsEntry("endDate", "2026-09-14");
    }

    @Test
    void explicitComparisonPassesAllObjectsAndPeriodsToOneCompoundTool() {
        var plan = plan("分析 gid=alpha 和 gid=beta，比较今天和昨天的访问趋势");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        assertThat(rows(plan.invocations().get(0).arguments().get("scopes")))
                .containsExactly(Map.of("gid", "alpha"), Map.of("gid", "beta"));
        assertThat(rows(plan.invocations().get(0).arguments().get("periods"))).hasSize(2);
        assertThat(plan.context().get("intent")).isEqualTo("STATS,COMPARE");
    }

    @Test
    void ordinaryMultipleObjectsAreNotAutomaticallyCompared() {
        var plan = plan("分析 gid=alpha 和 gid=beta 昨天访问趋势");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_group_stats", "get_group_stats");
        assertThat(plan.invocations()).extracting(call -> call.arguments().get("gid"))
                .containsExactly("alpha", "beta");
    }

    @Test
    void multipleUrlsWithinOneExplicitGroupKeepTheirOwnScope() {
        var plan = plan("分析 gid=alpha fullShortUrl=https://s.test/one fullShortUrl=https://s.test/two 昨天表现");
        assertThat(plan.invocations()).hasSize(2);
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsOnly("get_short_link_stats");
        assertThat(plan.invocations()).extracting(call -> call.arguments().get("fullShortUrl"))
                .containsExactly("https://s.test/one", "https://s.test/two");
    }

    @Test
    void explicitGroupUrlPairsDoNotBecomeACrossProductOfUnrelatedLinks() {
        var plan = plan("分析 gid=alpha fullShortUrl=https://s.test/one 和 gid=beta fullShortUrl=https://s.test/two 昨天表现");
        assertThat(plan.invocations()).hasSize(2);
        assertThat(plan.invocations().get(0).arguments()).containsEntry("gid", "alpha")
                .containsEntry("fullShortUrl", "https://s.test/one");
        assertThat(plan.invocations().get(1).arguments()).containsEntry("gid", "beta")
                .containsEntry("fullShortUrl", "https://s.test/two");
    }

    @Test
    void groupNamesRequireAFreshListBeforeDependentCalls() {
        String message = "分析 groupName=春季投放 和 groupName=秋季投放 昨天的数据";
        var waiting = planner.plan(message, Map.of(), null);
        assertThat(waiting.needsGroups()).isTrue();
        assertThat(waiting.invocations()).isEmpty();
        var resolved = planner.plan(message, Map.of(), groups("a", "春季投放", "b", "秋季投放"));
        assertThat(resolved.needsGroups()).isFalse();
        assertThat(resolved.invocations()).extracting(call -> call.arguments().get("gid"))
                .containsExactly("a", "b");
    }

    @Test
    void duplicateOwnedNamesAreRejectedInsteadOfSelectingTheFirst() {
        var plan = planner.plan("分析 groupName=投放 昨天的数据", Map.of(), groups("a", "投放", "b", "投放"));
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("多个匹配项"));
    }

    @Test
    void naturalMultipleOwnedNamesAreNotMistakenForAnAmbiguousSingleScope() {
        var plan = planner.plan("分析春季投放和秋季投放两个分组昨天的表现", Map.of(),
                groups("a", "春季投放", "b", "秋季投放"));
        assertThat(plan.invocations()).extracting(call -> call.arguments().get("gid"))
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void aLongerOwnedNameDoesNotAlsoMatchItsShorterPrefix() {
        var plan = planner.plan("分析广告A分组昨天表现", Map.of(), groups("a", "广告", "b", "广告A"));
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "b"));
    }

    @Test
    void defaultAliasStillResolvesOnlyFromTheCurrentOwnedList() {
        var plan = planner.plan("分析默认分组最近7天", Map.of(), groups("a", "default", "b", "其它组"));
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "a"));
    }

    @Test
    void frontEndSelectedScopeIsUsedWithoutRequiringGroupNameResolution() {
        var plan = plan("分析范围：分组「广告投放」；gid=alpha; 分析今天访问趋势");
        assertThat(plan.needsGroups()).isFalse();
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "alpha"));
    }

    @Test
    void explicitMultipleGroupsOverrideTheFrontEndSingleSelection() {
        String message = "分析范围：分组「已选」；gid=selected; 对比 groupName=春季 和 groupName=秋季 昨天表现";
        var waiting = planner.plan(message, Map.of(), null);
        assertThat(waiting.needsGroups()).isTrue();
        var plan = planner.plan(message, Map.of(), groups("a", "春季", "b", "秋季"));
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        assertThat(rows(plan.invocations().get(0).arguments().get("scopes")))
                .containsExactly(Map.of("gid", "a", "label", "春季"), Map.of("gid", "b", "label", "秋季"));
    }

    @Test
    void aFollowupDateInheritsOnlyTheKnownScopeWithoutAnotherLookup() {
        var previous = plan("分析 gid=alpha 今天访问趋势").context();
        var plan = planner.plan("再看昨天", previous, null);
        assertThat(plan.needsGroups()).isFalse();
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "alpha")
                        .containsEntry("startDate", "2026-09-18").containsEntry("endDate", "2026-09-18"));
    }

    @Test
    void changingTheObjectKeepsThePreviousPeriodAndDropsOldPageState() {
        var previous = new java.util.LinkedHashMap<>(plan("分析 gid=alpha 昨天访问趋势").context());
        previous.put("cursor", "old-cursor");
        previous.put("snapshotId", "old-snapshot");
        var plan = planner.plan("再看 gid=beta", previous, null);
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "beta")
                        .containsEntry("startDate", "2026-09-18")
                        .doesNotContainKeys("cursor", "snapshotId"));
        assertThat(plan.context()).containsOnlyKeys("scopes", "periods", "intent");
    }

    @Test
    void anExplicitUnresolvableNewGroupNeverFallsBackToThePreviousScope() {
        var previous = plan("分析 gid=alpha 昨天访问趋势").context();
        var plan = planner.plan("改成 groupName=不存在", previous, groups("a", "已有组"));
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("没有唯一匹配"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"分析 gid=alpha 从2026-09-10以来", "分析 gid=alpha 截至2026-09-10", "分析 gid=alpha 2026-09-10之后"})
    void anOpenBoundaryDoesNotBecomeASingleDayOrAnInheritedRange(String message) {
        var previous = plan("分析 gid=alpha 昨天访问趋势").context();
        var plan = planner.plan(message, previous, null);
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("单边边界"));
    }

    @Test
    void anExplicitSingleBusinessDayRetainsTheExistingSafeRule() {
        var plan = plan("分析 gid=alpha 在2026-09-10当天的访问趋势");
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("startDate", "2026-09-10")
                        .containsEntry("endDate", "2026-09-10"));
    }

    @Test
    void currentAndPreviousWeekUseShanghaiCalendarWeeks() {
        var plan = plan("分析 gid=alpha 本周与上周流量");
        assertThat(plan.invocations()).hasSize(2);
        assertThat(plan.invocations().get(0).arguments()).containsEntry("startDate", "2026-09-14")
                .containsEntry("endDate", "2026-09-19");
        assertThat(plan.invocations().get(1).arguments()).containsEntry("startDate", "2026-09-07")
                .containsEntry("endDate", "2026-09-13");
    }

    @Test
    void recentDaysPeriodComparisonBuildsAnEqualLengthPreviousPeriod() {
        var plan = plan("分析 gid=alpha 最近7天环比");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        var periods = rows(plan.invocations().get(0).arguments().get("periods"));
        assertThat(periods.get(0)).containsEntry("startDate", "2026-09-13")
                .containsEntry("endDate", "2026-09-19");
        assertThat(periods.get(1)).containsEntry("startDate", "2026-09-06")
                .containsEntry("endDate", "2026-09-12");
    }

    @Test
    void eachLongRangeScopeUsesTheExistingAsynchronousPlanner() {
        var plan = plan("分析 gid=alpha 和 gid=beta 最近30天流量");
        assertThat(plan.invocations()).hasSize(2);
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsOnly("submit_statistics_query_job");
        assertThat(plan.invocations()).allSatisfy(call ->
                assertThat(call.arguments()).containsEntry("queryKind", "METRICS").containsKey("requestId"));
        assertThat(plan.invocations().get(0).arguments().get("requestId"))
                .isNotEqualTo(plan.invocations().get(1).arguments().get("requestId"));
    }

    @Test
    void explicitRecordsAreSeparateFromStatisticsButBothCanBeRequested() {
        assertThat(plan("查看 gid=alpha 昨天访问明细").invocations())
                .extracting(CampaignAnalysisPlanner.Invocation::name).containsExactly("get_group_access_records");
        assertThat(plan("分析 gid=alpha 昨天访问趋势并查看访问记录").invocations())
                .extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_group_stats", "get_group_access_records");
    }

    @Test
    void explicitFrozenPageArgumentsArePreservedOnlyOnTheirSingleScope() {
        var plan = plan("access records gid=alpha startDate=2026-09-18 endDate=2026-09-18 current=2 size=10 snapshotId=s1 cursor=c1");
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("snapshotId", "s1").containsEntry("cursor", "c1")
                        .containsEntry("current", 2L).containsEntry("size", 10L));
        var ambiguous = plan("分析 gid=alpha 和 gid=beta 昨天数据 snapshotId=s1 cursor=c1");
        assertThat(ambiguous.invocations()).isEmpty();
        assertThat(ambiguous.warnings()).anySatisfy(warning -> assertThat(warning).contains("不能共用"));
    }

    @Test
    void explicitJobStatusAndPageContinuationKeepTheExistingContract() {
        assertThat(plan("查询统计 jobId=job-1").invocations()).singleElement().satisfies(call ->
                assertThat(call.name()).isEqualTo("get_statistics_query_job"));
        assertThat(plan("jobId=job-1 pageIndex=2").invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_statistics_query_job_page");
            assertThat(call.arguments()).containsEntry("jobId", "job-1").containsEntry("pageIndex", 2);
        });
    }

    @Test
    void scopePeriodExpansionIsRejectedAsAWholeAboveTheDocumentedBound() {
        String scopes = String.join(" ", IntStream.range(0, 17).mapToObj(i -> "gid=g" + i).toList());
        var plan = plan("分析 " + scopes + " 昨天数据");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("17", "16", "未截断"));
    }

    @Test
    void plainConversationDoesNotExecuteThePreviousAnalysisAgain() {
        var previous = plan("分析 gid=alpha 昨天访问趋势").context();
        var plan = planner.plan("谢谢", previous, null);
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.needsGroups()).isFalse();
    }

    @Test
    void singularShowGroupStillRequestsTheCurrentOwnedGroupList() {
        var plan = plan("show group");
        assertThat(plan.needsGroups()).isTrue();
        assertThat(plan.invocations()).isEmpty();
    }

    @Test
    void aGroupNameContainingTodayIsNotAnAdditionalRequestedPeriod() {
        var plan = planner.plan("分析 groupName=今天计划 昨天数据", Map.of(), groups("a", "今天计划"));
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "a")
                        .containsEntry("startDate", "2026-09-18").containsEntry("endDate", "2026-09-18"));
    }

    @Test
    void aCommaSeparatedGidListDoesNotSilentlyDiscardTheSecondObject() {
        var plan = plan("比较 gid=alpha,beta 昨天访问数据");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        assertThat(rows(plan.invocations().get(0).arguments().get("scopes")))
                .containsExactly(Map.of("gid", "alpha"), Map.of("gid", "beta"));
    }

    @Test
    void comparisonCanAlsoExplicitlyRequestTheAccessRecords() {
        var plan = plan("比较 gid=alpha 今天和昨天访问趋势，并查看访问记录");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics", "get_group_access_records", "get_group_access_records");
        assertThat(plan.invocations().get(1).arguments()).containsEntry("startDate", "2026-09-19");
        assertThat(plan.invocations().get(2).arguments()).containsEntry("startDate", "2026-09-18");
    }

    @Test
    void compoundComparisonOwnsItsLongRangeQueries() {
        var plan = plan("comparison gid=alpha gid=beta last 30 days");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        assertThat(rows(plan.invocations().get(0).arguments().get("periods")))
                .containsExactly(Map.of("startDate", "2026-08-21", "endDate", "2026-09-19"));
    }

    @Test
    void comparisonFollowupKeepsItsIntentAndAllKnownObjects() {
        var previous = plan("比较 gid=alpha gid=beta 今天访问趋势").context();
        var plan = planner.plan("再看昨天", previous, null);
        assertThat(plan.needsGroups()).isFalse();
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        assertThat(rows(plan.invocations().get(0).arguments().get("scopes")))
                .containsExactly(Map.of("gid", "alpha"), Map.of("gid", "beta"));
        assertThat(rows(plan.invocations().get(0).arguments().get("periods")))
                .containsExactly(Map.of("startDate", "2026-09-18", "endDate", "2026-09-18"));
    }

    @Test
    void aComparisonAboveTheCombinationBoundIsRejectedBeforeTheCompoundCall() {
        String scopes = String.join(" ", IntStream.range(0, 9).mapToObj(i -> "gid=g" + i).toList());
        var plan = plan("比较 " + scopes + " 今天和昨天数据");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("18", "16"));
    }

    @Test
    void rankingDefaultsToTenLinksByPvAndDoesNotReadTheManagementList() {
        var plan = plan("查看短链排名 gid=alpha 昨天");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("rank_short_links");
        assertThat(plan.invocations().get(0).arguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "gid", "alpha", "startDate", "2026-09-18", "endDate", "2026-09-18", "metric", "pv", "limit", 10));
        assertThat(plan.context()).containsEntry("intent", "STATS,RANK")
                .containsEntry("ranking", Map.of("metric", "pv", "limit", 10));
    }

    @Test
    void rankingSupportsExplicitMetricAndTopCount() {
        var plan = plan("gid=alpha 昨天 UV Top 5");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("rank_short_links");
        assertThat(plan.invocations().get(0).arguments()).containsEntry("metric", "uv").containsEntry("limit", 5);
        var explicit = plan("gid=alpha 昨天排名 metric=UIP limit=50");
        assertThat(explicit.invocations().get(0).arguments()).containsEntry("metric", "uip").containsEntry("limit", 50);
    }

    @Test
    void eachGroupAndPeriodGetsItsOwnRankingWithoutOrdinaryStatsCalls() {
        var plan = plan("分析 gid=alpha gid=beta 今天和昨天短链排行前3");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("rank_short_links", "rank_short_links", "rank_short_links", "rank_short_links");
        assertThat(plan.invocations()).extracting(call -> call.arguments().get("gid"))
                .containsExactly("alpha", "alpha", "beta", "beta");
        assertThat(plan.invocations()).allSatisfy(call -> assertThat(call.arguments()).containsEntry("limit", 3));
    }

    @Test
    void rankingOwnsItsLongRangeQueryInsteadOfPlanningASynchronousStatsJob() {
        var plan = plan("gid=alpha 最近30天 UV Top 5");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("rank_short_links");
        assertThat(plan.invocations().get(0).arguments()).containsEntry("startDate", "2026-08-21")
                .containsEntry("endDate", "2026-09-19").containsEntry("metric", "uv").containsEntry("limit", 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=51", "limit=-1", "limit=5.5", "limit=2 limit=3", "Top 0", "Top 51"})
    void invalidOrAmbiguousRankingSizesAreNotSilentlyTruncated(String size) {
        var plan = plan("gid=alpha 昨天短链排名 " + size);
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("1 至 50"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"metric=clicks", "metric=pv metric=uv", "PV 与 UV"})
    void invalidOrAmbiguousRankingMetricsAreNotGuessed(String metric) {
        var plan = plan("gid=alpha 昨天短链排名 " + metric);
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("一个排序指标"));
    }

    @Test
    void rankingDoesNotSilentlyExpandASingleLinkToItsWholeGroup() {
        var plan = plan("gid=alpha fullShortUrl=https://s.test/one 昨天短链排名");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("不要同时指定单个"));
    }

    @Test
    void rankingFollowupRetainsUvTopFiveAndAllowsIndependentOverrides() {
        var previous = plan("gid=alpha 今天 UV Top 5").context();
        var yesterday = planner.plan("再看昨天", previous, null);
        assertThat(yesterday.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("rank_short_links");
            assertThat(call.arguments()).containsEntry("gid", "alpha").containsEntry("startDate", "2026-09-18")
                    .containsEntry("metric", "uv").containsEntry("limit", 5);
        });
        var newLimit = planner.plan("再看 Top 3", yesterday.context(), null);
        assertThat(newLimit.invocations().get(0).arguments()).containsEntry("metric", "uv").containsEntry("limit", 3);
        var newMetric = planner.plan("排名改成 metric=uip", newLimit.context(), null);
        assertThat(newMetric.invocations().get(0).arguments()).containsEntry("metric", "uip").containsEntry("limit", 3);
    }

    @Test
    void switchingFromRankingToOrdinaryStatsClearsRankingContext() {
        var previous = plan("gid=alpha 今天 UV Top 5").context();
        var ordinary = planner.plan("再看昨天访问趋势", previous, null);
        assertThat(ordinary.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_group_stats");
        assertThat(ordinary.context()).doesNotContainKey("ranking");
        var newRanking = planner.plan("再看排名", ordinary.context(), null);
        assertThat(newRanking.invocations().get(0).arguments()).containsEntry("metric", "pv").containsEntry("limit", 10);
    }

    @Test
    void plainConversationKeepsRankingContextWithoutExecutingIt() {
        var previous = plan("gid=alpha 今天 UV Top 5").context();
        var thanks = planner.plan("谢谢", previous, null);
        assertThat(thanks.invocations()).isEmpty();
        assertThat(thanks.context()).containsEntry("ranking", Map.of("metric", "uv", "limit", 5));
    }

    @Test
    void selectedScopeLabelDoesNotOverrideTheRequestedRankingOptions() {
        var plan = plan("分析范围：分组「PV Top 20」；gid=alpha; 昨天 UV Top 5");
        assertThat(plan.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("metric", "uv").containsEntry("limit", 5));
    }

    @Test
    void sameUiSelectionDoesNotOverrideTheActualObjectsInAFollowup() {
        var previous = plan("分析范围：分组「已选」；gid=selected; 比较 gid=alpha gid=beta 今天访问趋势").context();
        var followup = planner.plan("分析范围：分组「已选」；gid=selected; 再看昨天", previous, null);
        assertThat(followup.needsGroups()).isFalse();
        assertThat(followup.context()).containsEntry("selectedGid", "selected");
        assertThat(followup.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("compare_statistics");
        assertThat(rows(followup.invocations().get(0).arguments().get("scopes")))
                .containsExactly(Map.of("gid", "alpha"), Map.of("gid", "beta"));
    }

    @Test
    void changingUiSelectionUsesTheNewGroupInsteadOfPreviousActualScopes() {
        var previous = plan("分析范围：分组「已选」；gid=selected; gid=alpha 今天 UV Top 5").context();
        var followup = planner.plan("分析范围：分组「新组」；gid=new-selection; 再看昨天", previous, null);
        assertThat(followup.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "new-selection")
                        .containsEntry("metric", "uv").containsEntry("limit", 5));
        assertThat(followup.context()).containsEntry("selectedGid", "new-selection");
    }

    @Test
    void plainConversationPreservesUiSelectionForTheNextFollowup() {
        var previous = plan("分析范围：分组「已选」；gid=selected; gid=alpha 今天 UV Top 5").context();
        var thanks = planner.plan("谢谢", previous, null);
        assertThat(thanks.invocations()).isEmpty();
        assertThat(thanks.context()).containsEntry("selectedGid", "selected")
                .containsEntry("ranking", Map.of("metric", "uv", "limit", 5));
    }

    @Test
    void groupNamesContainingRankingWordsDoNotCreateARankingIntent() {
        var plan = planner.plan("分析 groupName=昨日排名活动 今天访问趋势", Map.of(), groups("a", "昨日排名活动"));
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_group_stats");
    }

    @Test
    void provinceAndDeviceUseOneJointBreakdownInsteadOfIndependentMarginals() {
        var plan = plan("分析 gid=alpha 昨天，按省份和设备下钻");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_dimension_breakdown");
        assertThat(plan.invocations().get(0).arguments()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "gid", "alpha", "startDate", "2026-09-18", "endDate", "2026-09-18",
                "dimensions", List.of("province", "device"), "filters", List.of()));
        assertThat(plan.context()).containsEntry("intent", "STATS,DRILL")
                .containsEntry("drill", Map.of("dimensions", List.of("province", "device"), "filters", List.of()));
    }

    @Test
    void regionAndBrowserNaturalDimensionsPreserveTheRequestedOrder() {
        var plan = plan("gid=alpha 昨天按地区、浏览器分析");
        assertThat(plan.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_dimension_breakdown");
            assertThat(call.arguments()).containsEntry("dimensions", List.of("province", "browser"));
        });
    }

    @Test
    void explicitDimensionsAndFiltersUseExactBackendValues() {
        var plan = plan("gid=alpha 昨天 dimensions=province,device province=浙江,江苏 device=Mobile country=China");
        assertThat(plan.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_dimension_breakdown");
            assertThat(call.arguments()).containsEntry("dimensions", List.of("province", "device"));
            assertThat(rows(call.arguments().get("filters"))).containsExactly(
                    Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江", "江苏")),
                    Map.of("dimension", "device", "operator", "IN", "values", List.of("Mobile")),
                    Map.of("dimension", "country", "operator", "IN", "values", List.of("China")));
        });
    }

    @Test
    void anExplicitChineseDeviceValueIsPreservedRatherThanTranslated() {
        var plan = plan("gid=alpha 昨天 dimensions=province,device province=浙江省 device=手机");
        assertThat(rows(plan.invocations().get(0).arguments().get("filters"))).containsExactly(
                Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江省")),
                Map.of("dimension", "device", "operator", "IN", "values", List.of("手机")));
    }

    @Test
    void unknownFilterUsesIsUnknownWithoutValues() {
        var plan = plan("gid=alpha 昨天 dimensions=province,device province=IS_UNKNOWN");
        assertThat(rows(plan.invocations().get(0).arguments().get("filters")))
                .containsExactly(Map.of("dimension", "province", "operator", "IS_UNKNOWN"));
        var natural = plan("gid=alpha 昨天只看省份未知，按设备下钻");
        assertThat(rows(natural.invocations().get(0).arguments().get("filters")))
                .containsExactly(Map.of("dimension", "province", "operator", "IS_UNKNOWN"));
    }

    @Test
    void aProvinceFollowupChangesDimensionsAndRetainsOtherExplicitFilters() {
        var previous = plan("gid=alpha 昨天 dimensions=province,device device=Mobile").context();
        var followup = planner.plan("只看浙江，再按设备和浏览器下钻", previous, null);
        assertThat(followup.needsGroups()).isFalse();
        assertThat(followup.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_dimension_breakdown");
            assertThat(call.arguments()).containsEntry("gid", "alpha").containsEntry("startDate", "2026-09-18")
                    .containsEntry("dimensions", List.of("device", "browser"));
            assertThat(rows(call.arguments().get("filters"))).containsExactly(
                    Map.of("dimension", "device", "operator", "IN", "values", List.of("Mobile")),
                    Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江")));
        });
    }

    @Test
    void changingOneFilterKeepsTheOtherDimensionsAndFilters() {
        var previous = plan("gid=alpha 昨天 dimensions=province,device province=浙江 device=Mobile").context();
        var followup = planner.plan("province=江苏", previous, null);
        assertThat(followup.needsGroups()).isFalse();
        assertThat(followup.invocations().get(0).arguments()).containsEntry("dimensions", List.of("province", "device"));
        assertThat(rows(followup.invocations().get(0).arguments().get("filters"))).containsExactly(
                Map.of("dimension", "province", "operator", "IN", "values", List.of("江苏")),
                Map.of("dimension", "device", "operator", "IN", "values", List.of("Mobile")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"清除筛选", "去掉筛选", "清空筛选", "去掉过滤"})
    void clearingFiltersPreservesTheJointDimensionsAndScope(String message) {
        var previous = plan("gid=alpha 昨天 dimensions=province,device province=浙江").context();
        var followup = planner.plan(message, previous, null);
        assertThat(followup.needsGroups()).isFalse();
        assertThat(followup.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "alpha")
                        .containsEntry("dimensions", List.of("province", "device")).containsEntry("filters", List.of()));
        assertThat(followup.context()).containsEntry("drill", Map.of("dimensions", List.of("province", "device"), "filters", List.of()));
    }

    @Test
    void clearingFiltersCanBeCombinedWithAnExplicitReplacement() {
        var previous = plan("gid=alpha 昨天 dimensions=device,os province=浙江 device=Mobile").context();
        var followup = planner.plan("清除筛选 province=江苏", previous, null);
        assertThat(rows(followup.invocations().get(0).arguments().get("filters")))
                .containsExactly(Map.of("dimension", "province", "operator", "IN", "values", List.of("江苏")));
    }

    @Test
    void aDrillDateFollowupKeepsDimensionsAndFiltersButNotTheOldPage() {
        var previous = new java.util.LinkedHashMap<>(plan("gid=alpha 今天 dimensions=province,device province=浙江").context());
        previous.put("cursor", "old-cursor");
        previous.put("snapshotId", "old-snapshot");
        previous.put("jobId", "old-job");
        var followup = planner.plan("再看昨天", previous, null);
        assertThat(followup.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_dimension_breakdown");
            assertThat(call.arguments()).containsEntry("startDate", "2026-09-18")
                    .containsEntry("dimensions", List.of("province", "device"))
                    .doesNotContainKeys("cursor", "snapshotId", "jobId");
            assertThat(rows(call.arguments().get("filters")))
                    .containsExactly(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江")));
        });
        assertThat(followup.context()).doesNotContainKeys("cursor", "snapshotId", "jobId");
    }

    @Test
    void aDrillObjectFollowupUsesTheNewObjectWithTheSameQueryConfiguration() {
        var previous = plan("gid=alpha 昨天 dimensions=device,browser province=浙江").context();
        var followup = planner.plan("再看 gid=beta", previous, null);
        assertThat(followup.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "beta").containsEntry("startDate", "2026-09-18")
                        .containsEntry("dimensions", List.of("device", "browser")));
    }

    @Test
    void eachScopeAndPeriodGetsARealJointQueryEvenWhenComparisonIsMentioned() {
        var plan = plan("比较 gid=alpha gid=beta 今天和昨天，按省份和设备下钻 province=浙江");
        assertThat(plan.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name).containsExactly(
                "get_dimension_breakdown", "get_dimension_breakdown", "get_dimension_breakdown", "get_dimension_breakdown");
        assertThat(plan.invocations()).extracting(call -> call.arguments().get("gid"))
                .containsExactly("alpha", "alpha", "beta", "beta");
        assertThat(plan.invocations()).allSatisfy(call -> assertThat(call.arguments())
                .containsEntry("dimensions", List.of("province", "device")));
    }

    @Test
    void aShortLinkLongRangeDrillIsHandledByTheCompoundTool() {
        var plan = plan("gid=alpha fullShortUrl=https://s.test/one 最近30天 dimensions=province,device province=浙江");
        assertThat(plan.invocations()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("get_dimension_breakdown");
            assertThat(call.arguments()).containsEntry("gid", "alpha").containsEntry("fullShortUrl", "https://s.test/one")
                    .containsEntry("startDate", "2026-08-21").containsEntry("endDate", "2026-09-19");
        });
    }

    @Test
    void filterDatesDoNotReplaceTheRequestedAnalysisPeriod() {
        var plan = plan("gid=alpha 最近7天 dimensions=day,device day=2026-09-16");
        assertThat(plan.invocations().get(0).arguments()).containsEntry("startDate", "2026-09-13")
                .containsEntry("endDate", "2026-09-19");
        assertThat(rows(plan.invocations().get(0).arguments().get("filters")))
                .containsExactly(Map.of("dimension", "day", "operator", "IN", "values", List.of("2026-09-16")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dimensions=province,device,os,browser", "dimensions=province,province", "dimensions=age", "dimensions=", "按来源和设备下钻"})
    void invalidOrAmbiguousDimensionsAreRejectedInsteadOfFallingBackToMarginals(String request) {
        var plan = plan("gid=alpha 昨天 " + request);
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"只看手机", "只看Safari", "province!=浙江", "province IN (浙江)", "age=18", "province=浙江 province=江苏", "province=IS_UNKNOWN,浙江", "hour=24", "weekday=0", "day=2026-02-30"})
    void ambiguousOrUnsupportedFiltersNeverProduceAnUnfilteredQuery(String request) {
        var plan = plan("gid=alpha 昨天 dimensions=province,device " + request);
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).isNotEmpty();
    }

    @Test
    void aFilterWithoutExplicitOrInheritedDimensionsRequestsClarification() {
        var plan = plan("gid=alpha 昨天 province=浙江");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("1 至 3"));
    }

    @Test
    void ordinaryStatisticsExplicitlyClearThePreviousDrillConfiguration() {
        var previous = plan("gid=alpha 昨天 dimensions=province,device province=浙江").context();
        var ordinary = planner.plan("再看今天访问趋势", previous, null);
        assertThat(ordinary.invocations()).extracting(CampaignAnalysisPlanner.Invocation::name)
                .containsExactly("get_group_stats");
        assertThat(ordinary.context()).doesNotContainKey("drill");
    }

    @Test
    void plainConversationPreservesDrillConfigurationWithoutExecutingIt() {
        var previous = plan("分析范围：分组「已选」；gid=selected; gid=alpha 昨天 dimensions=province,device province=浙江").context();
        var thanks = planner.plan("谢谢", previous, null);
        assertThat(thanks.invocations()).isEmpty();
        assertThat(thanks.context()).containsEntry("selectedGid", "selected").containsEntry("drill", previous.get("drill"));
    }

    @Test
    void drillContinuationWithTheSameUiSelectionKeepsTheActualScope() {
        var previous = plan("分析范围：分组「已选」；gid=selected; gid=alpha 今天 dimensions=province,device province=浙江").context();
        var followup = planner.plan("分析范围：分组「已选」；gid=selected; 只看江苏，再按设备和浏览器下钻", previous, null);
        assertThat(followup.invocations()).singleElement().satisfies(call ->
                assertThat(call.arguments()).containsEntry("gid", "alpha").containsEntry("dimensions", List.of("device", "browser")));
    }

    @Test
    void mixedFilteredRecordsAreNotExecutedWithoutTheirRequestedFilters() {
        var plan = plan("gid=alpha 昨天 dimensions=province,device province=浙江 并查看访问记录");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("未忽略筛选"));
    }

    @Test
    void drillScopeExpansionStillHonorsTheWholePlanBound() {
        String scopes = String.join(" ", IntStream.range(0, 17).mapToObj(i -> "gid=g" + i).toList());
        var plan = plan(scopes + " 昨天 dimensions=province,device");
        assertThat(plan.invocations()).isEmpty();
        assertThat(plan.warnings()).anySatisfy(warning -> assertThat(warning).contains("17", "16", "未截断"));
    }

    private CampaignAnalysisPlanner.Plan plan(String message) {
        return planner.plan(message, Map.of(), null);
    }

    private List<Object> groups(String... pairs) {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) result.add(Map.of("gid", pairs[i], "name", pairs[i + 1]));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
