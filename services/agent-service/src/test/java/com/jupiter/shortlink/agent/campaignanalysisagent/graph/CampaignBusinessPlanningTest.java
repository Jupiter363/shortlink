package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
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
import com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.jupiter.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import com.jupiter.shortlink.agent.tool.shortlink.GetGroupAccessRecordsTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

class CampaignBusinessPlanningTest {
    // UTC is still September 12; the service's business date is already September 13.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-12T18:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final AgentPrincipal ALICE = new AgentPrincipal("101", "alice", 3, false);
    private final List<String> order = new ArrayList<>();
    private final CapturingLlm llm = new CapturingLlm();

    @ParameterizedTest
    @CsvSource({
        "分析 default 分组最近 7 天的流量表现和异常变化,默认分组",
        "分析默认分组近 7 天表现,default",
        "Analyze DEFAULT group performance for the last 7 days,默认组",
        "分析默认组过去一周流量,Default Group"
    })
    void resolvesDefaultAliasesThenExecutesStatsWithActualBusinessDates(
            String message, String groupName) {
        CapturingTool groups = groups(groupName);
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        AgentRunResult result = executor(groups, stats).execute(request(message, ALICE));

        assertThat(order).containsExactly("list_groups", "get_group_stats");
        assertThat(stats.context.arguments())
                .containsEntry("gid", "AliceRealGid")
                .containsEntry("startDate", "2026-09-07")
                .containsEntry("endDate", "2026-09-13");
        assertThat(stats.context.principal()).isEqualTo(ALICE);
        assertThat(result.warnings()).isEmpty();
        assertThat(llm.request.messages().get(1).content())
                .contains("AliceRealGid", "2026-09-07", "2026-09-13");
    }

    @Test
    void trafficCompositionPresetFetchesStatsAndBoundedAccessRecordsInSameTurn() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        CapturingTool records =
                tool("get_group_access_records", ToolResult.success(Map.of("records", List.of())));
        AgentRunResult result =
                executor(groups("default"), stats, records)
                        .execute(request("汇总 default 分组最近 7 天的访问设备、地区和浏览器构成", ALICE));

        assertThat(order)
                .containsExactly("list_groups", "get_group_stats", "get_group_access_records");
        assertThat(records.context.arguments()).containsEntry("gid", "AliceRealGid");
        assertThat(result.warnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "分析分组‘UAT统计维度样本’在2026-09-13的访问统计和访问记录",
        "分析分组‘UAT统计维度样本’在 2026-09-13 的访问统计和访问记录",
        "分析分组‘UAT统计维度样本’2026-09-13当天的访问统计和访问记录",
        "分析分组‘UAT统计维度样本’2026-09-13当日的访问统计和访问记录",
        "分析分组‘UAT统计维度样本’2026-09-13的访问统计和访问记录",
        "Analyze groupName=UAT统计维度样本 stats and access records on 2026-09-13"
    })
    void explicitSingleDayRunsBothToolsWithTheSameAuthorizedShanghaiCalendarDate(String message) {
        var stats = tool("get_group_stats", ToolResult.success(Map.of()));
        var records = tool("get_group_access_records", ToolResult.success(Map.of("items", List.of())));
        var result = executor(groups("UAT统计维度样本"), stats, records).execute(request(message, ALICE));
        assertThat(order).containsExactly("list_groups", "get_group_stats", "get_group_access_records");
        for (var tool : List.of(stats, records)) {
            assertThat(tool.context.arguments()).containsEntry("gid", "AliceRealGid")
                    .containsEntry("startDate", "2026-09-13").containsEntry("endDate", "2026-09-13");
            assertThat(tool.context.principal()).isEqualTo(ALICE);
        }
        assertThat(result.warnings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "分析 gid=known 截至2026-09-13当天的访问统计",
        "分析 gid=known 截止到2026-09-13的访问统计",
        "分析 gid=known 截止在2026-09-13当天的访问统计",
        "分析 gid=known 从2026-09-13的访问统计",
        "分析 gid=known 自2026-09-13开始的访问统计",
        "分析 gid=known 在2026-09-13之前的访问统计",
        "分析 gid=known 在2026-09-13之后的访问统计",
        "分析 gid=known 在2026-09-13当天之前的访问统计",
        "分析 gid=known 在2026-09-13当日之后的访问统计",
        "分析 gid=known 在2026-09-13及以前的访问统计",
        "分析 gid=known 在2026-09-13起的访问统计",
        "分析 gid=known 在2026-09-13以来的访问统计",
        "stats gid=known since 2026-09-13",
        "stats gid=known on 2026-09-13 or earlier",
        "stats gid=known from 2026-09-13 最近7天"
    })
    void singleOpenDateDoesNotBecomeAOneDayOrRelativeRange(String message) {
        var stats = tool("get_group_stats", ToolResult.success(Map.of()));
        var records = tool("get_group_access_records", ToolResult.success(Map.of()));
        var result = executor(stats, records).execute(request(message, ALICE));
        assertThat(stats.context).isNull(); assertThat(records.context).isNull();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("单边边界"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "分析 gid=known 在2026-02-30的统计",
        "分析 gid=known 在2026-09-13T12:00的统计",
        "stats gid=known on 2026-09-13 12:00",
        "分析 gid=known，参考报告编号2026-09-13"
    })
    void invalidDatesClockInstantsAndUnscopedDateMentionsDoNotInventADay(String message) {
        var stats = tool("get_group_stats", ToolResult.success(Map.of()));
        var result = executor(stats).execute(request(message, ALICE));
        assertThat(stats.context).isNull(); assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void singleDayWordsDoNotOverrideAnExplicitMultiDayRange() {
        var stats = tool("get_group_stats", ToolResult.success(Map.of()));
        executor(stats).execute(request("分析 gid=known 从2026-09-10到2026-09-13当天的统计", ALICE));
        assertThat(stats.context.arguments()).containsEntry("startDate", "2026-09-10")
                .containsEntry("endDate", "2026-09-13");
    }

    @Test
    void rollingHourPresetDisclosesCalendarDayPrecisionAndExecutesStatistics() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        AgentRunResult result =
                executor(groups("default"), stats)
                        .execute(request("诊断 default 分组最近 24 小时的流量异常，并说明判断依据", ALICE));

        assertThat(stats.context.arguments())
                .containsEntry("startDate", "2026-09-12")
                .containsEntry("endDate", "2026-09-13");
        assertThat(result.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("不能视为精确滚动小时窗口"));
        assertThat(llm.request.messages().get(1).content())
                .contains("Asia/Shanghai", "自然日", "最近 24 小时");
    }

    @Test
    void longRelativeRangeReusesAsyncJobAndDoesNotAskForAuthorizationAgain() {
        CapturingTool job =
                tool(
                        "submit_statistics_query_job",
                        ToolResult.success(Map.of("jobId", "job-30", "state", "QUEUED")));
        AgentRunResult result =
                executor(groups("default"), job).execute(request("分析默认分组最近 30 天流量", ALICE));

        assertThat(order).containsExactly("list_groups", "submit_statistics_query_job");
        assertThat(job.context.arguments())
                .containsEntry("gid", "AliceRealGid")
                .containsEntry("startDate", "2026-08-15")
                .containsEntry("endDate", "2026-09-13")
                .containsEntry("queryKind", "METRICS")
                .containsKey("requestId");
        assertThat(result.answer()).contains("jobId=job-30", "PENDING");
        assertThat(llm.request).isNull();
    }

    @Test
    void jobContinuationDoesNotAccidentallyResolveAGroupOrSubmitAnotherJob() {
        CapturingTool status =
                tool(
                        "get_statistics_query_job",
                        ToolResult.success(Map.of("jobId", "job-30", "state", "RUNNING")));
        AgentRunResult result = executor(status).execute(request("查询统计 jobId=job-30", ALICE));
        assertThat(order).containsExactly("get_statistics_query_job");
        assertThat(result.warnings()).isEmpty();
        assertThat(status.context.principal()).isEqualTo(ALICE);
    }

    @Test
    void explicitAccessContinuationPreservesFrozenSnapshotCursorInRealTool() {
        AtomicReference<Map<String, Object>> forwarded = new AtomicReference<>();
        GetGroupAccessRecordsTool records =
                new GetGroupAccessRecordsTool(
                        (path, context, query) -> {
                            assertThat(context.principal()).isEqualTo(ALICE);
                            forwarded.set(query);
                            return ToolResult.success(
                                    Map.of(
                                            "items",
                                            List.of(),
                                            "meta",
                                            Map.of("snapshotId", "snapshot-123")));
                        });
        AgentRunResult result =
                executor(records)
                        .execute(
                                request(
                                        "access records gid=known startDate=2026-09-07"
                                                + " endDate=2026-09-13 current=2 size=10"
                                                + " snapshotId=snapshot-123"
                                                + " cursor=c25hcHNob3QtMTIzOjEw",
                                        ALICE));
        assertThat(forwarded.get())
                .containsEntry("snapshotId", "snapshot-123")
                .containsEntry("cursor", "c25hcHNob3QtMTIzOjEw")
                .containsEntry("current", 2L)
                .containsEntry("size", 10L);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void accessRecordCardsPreserveActualEventResultsAndLegacyAliasesWithoutInventingMissingValues() {
        List<Map<String, Object>> rows = List.of(
                Map.of("eventId", "actual", "kind", "CLICK", "status", 307),
                Map.of("eventId", "legacy", "eventType", "REDIRECT", "httpStatus", 302),
                Map.of("eventId", "unknown", "kind", "CLICK", "status", 0),
                Map.of("eventId", "absent"));
        Map<String, Object> page = Map.of("items", rows, "hasMore", false,
                "meta", Map.of("availability", "AVAILABLE", "snapshotId", "access-snapshot"));
        var records = tool("get_group_access_records", ToolResult.success(page));
        var result = executor(records).execute(request(
                "access records gid=known startDate=2026-09-07 endDate=2026-09-13", ALICE));

        assertThat(order).containsExactly("get_group_access_records");
        assertThat(records.context.principal()).isEqualTo(ALICE);
        assertThat(result.cards()).hasSize(1);
        Map<?, ?> card = (Map<?, ?>) result.cards().get(0);
        assertThat(card.get("rows")).isEqualTo(rows);
        assertThat(card.get("rawData")).isEqualTo(page);
        assertThat(llm.request.messages().get(1).content()).contains("CLICK", "307", "REDIRECT");
    }

    @Test
    void accessContinuationWithoutSnapshotCannotFallBackToFreshPage() {
        AtomicReference<Map<String, Object>> forwarded = new AtomicReference<>();
        GetGroupAccessRecordsTool records =
                new GetGroupAccessRecordsTool(
                        (path, context, query) -> {
                            forwarded.set(query);
                            return ToolResult.success(Map.of());
                        });
        AgentRunResult result =
                executor(records)
                        .execute(
                                request(
                                        "access records gid=known startDate=2026-09-07"
                                                + " endDate=2026-09-13 current=2",
                                        ALICE));
        assertThat(forwarded.get()).isNull();
        assertThat(result.warnings())
                .anySatisfy(
                        warning ->
                                assertThat(warning)
                                        .contains("Continuation requires snapshotId and cursor"));
    }

    @Test
    void partialMetricsStayVisibleWithQualityLabelsButDoNotGenerateAnomalyCards() {
        Map<String, Object> envelope = evidence("PARTIAL", Map.of("pv", 100L, "uv", 1L, "uip", 1L));
        AgentRunResult result =
                executor(tool("get_group_stats", ToolResult.success(envelope)))
                        .execute(request("分析 gid=known 最近7天", ALICE));
        assertThat(result.cards()).hasSize(1);
        Map<?, ?> card = (Map<?, ?>) result.cards().get(0);
        assertThat(card.get("metrics")).isEqualTo(Map.of("pv", 100L, "uv", 1L, "uip", 1L));
        assertThat(card.get("meta")).isEqualTo(envelope.get("meta"));
        assertThat(card.get("rawData")).isEqualTo(envelope);
        assertThat(card.get("message").toString())
                .contains(
                        "部分采集结果",
                        "已观测值",
                        "不代表完整流量",
                        "临时快照",
                        "数据已陈旧",
                        "Asia/Shanghai",
                        "2026-09-07 00:00:00",
                        "2026-09-14 00:00:00",
                        "估算指标",
                        "UV",
                        "UIP",
                        "缺少统计维度",
                        "地区构成");
        assertThat(llm.request.messages().get(1).content())
                .contains("PARTIAL", "provisional", "missingMetrics");
    }

    @Test
    void explanationContractKeepsClickSemanticsSeparateFromWindowNamesAndSourceOffsets() {
        Map<String, Object> metrics =
                Map.of(
                        "pv", 11L,
                        "uv", 11L,
                        "uip", 1L,
                        "denied", 3L,
                        "daily", List.of(Map.of("date", "2026-09-07", "pv", 0L)));
        Map<String, Object> envelope = evidence("PARTIAL", metrics);
        Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) envelope.get("meta"));
        meta.put("sourceCut", List.of(Map.of("source_partition", 0, "end_offset", 2L)));
        envelope.put("meta", meta);

        AgentRunResult result =
                executor(tool("get_group_stats", ToolResult.success(envelope)))
                        .execute(request("分析 gid=known 最近7天流量与增长异常", ALICE));

        String system = llm.request.messages().get(0).content();
        assertThat(system)
                .contains(
                        "requested is only the requested time-window label, not a REQUEST-event"
                                + " category",
                        "PV counts valid deduplicated CLICK events",
                        "from those same CLICK events; retain their approximation metadata",
                        "denied separately counts REDIRECT/BUSINESS REQUEST results with HTTP 403"
                                + " or 429",
                        "never relabel PV/UV/UIP",
                        "It does not prove true zero traffic, growth from zero, or a traffic"
                                + " anomaly",
                        "sourceCut Kafka offsets describe source positions and snapshot provenance,"
                                + " not traffic volume");
        assertThat(llm.request.messages().get(1).content())
                .contains(
                        "\"requested\"",
                        "\"denied\":3",
                        "\"sourceCut\"",
                        "\"end_offset\":2",
                        "PARTIAL");
        assertThat(result.cards()).hasSize(1);
        Map<?, ?> summary = (Map<?, ?>) result.cards().get(0);
        assertThat(summary.get("metrics")).isEqualTo(Map.of("pv", 11L, "uv", 11L, "uip", 1L));
        assertThat(summary.get("meta")).isEqualTo(meta);
    }

    @Test
    void explanationKeepsSparseTopKAndExactDimensionsSeparateFromVisitorTotalsAndCalendarDates() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("pv", 31L);
        metrics.put("uv", 24L);
        metrics.put("uip", 7L);
        metrics.put(
                "topVisitorStats",
                List.of(
                        Map.of(
                                "visitorHash",
                                "opaque-visitor",
                                "cnt",
                                2L,
                                "error",
                                1L,
                                "approximate",
                                true)));
        metrics.put("browserStats", List.of(Map.of("browser", "Safari", "cnt", 4L)));
        metrics.put("daily", List.of(Map.of("date", "2026-09-13", "pv", 31L)));
        metrics.put(
                "hourStats",
                List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9, 22, 0, 0));
        Map<String, Object> envelope = evidence("PARTIAL", metrics);
        Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) envelope.get("meta"));
        meta.put(
                "approximation",
                Map.of(
                        "browserStats",
                        Map.of("type", "EXACT"),
                        "topVisitorStats",
                        Map.of("type", "APPROXIMATE")));
        envelope.put("meta", meta);

        AgentRunResult result =
                executor(tool("get_group_stats", ToolResult.success(envelope)))
                        .execute(request("分析 gid=known 最近7天的访客和浏览器构成", ALICE));

        String system = llm.request.messages().get(0).content();
        assertThat(system)
                .contains(
                        "truncated TopK lists, not visitor/IP censuses",
                        "Their list lengths are not UV/UIP measurements",
                        "never substitute total PV for a row's count",
                        "never contradict the supplied UV/UIP using a TopK list",
                        "Sparse categories do not establish sampling or TopK truncation",
                        "never invent a sampling/truncation mechanism",
                        "Hashes are pseudonymous analytics keys, not verified people or devices",
                        "hourStats indices are local hours 0 through 23, not dates",
                        "multiple nonzero hour buckets do not imply multiple days",
                        "lead with PV/UV/UIP and the main data-quality limitation");
        String context = llm.request.messages().get(1).content();
        assertThat(context)
                .contains(
                        "\"cnt\":2",
                        "\"cnt\":4",
                        "\"uv\":24",
                        "\"uip\":7",
                        "2026-09-13",
                        "\"error\":1",
                        "EXACT",
                        "APPROXIMATE");
        Map<?, ?> summary = (Map<?, ?>) result.cards().get(0);
        assertThat(summary.get("metrics")).isEqualTo(Map.of("pv", 31L, "uv", 24L, "uip", 7L));
        assertThat(summary.get("rawData")).isEqualTo(envelope);
    }

    @Test
    void accessExplanationKeepsRawEpochsAndPageBoundariesWithoutInventingClockTimesOrTotals() {
        Map<String, Object> page =
                Map.of(
                        "items",
                                List.of(
                                        Map.of(
                                                "eventId",
                                                "event-a",
                                                "occurredAt",
                                                Instant.parse("2026-09-11T07:16:23Z")
                                                        .toEpochMilli()),
                                        Map.of(
                                                "eventId",
                                                "event-b",
                                                "occurredAt",
                                                Instant.parse("2026-09-11T07:14:23Z")
                                                        .toEpochMilli())),
                        "meta",
                                Map.of(
                                        "availability",
                                        "AVAILABLE",
                                        "completeness",
                                        "PARTIAL",
                                        "nextCursor",
                                        "opaque-next-page"));
        AgentRunResult result =
                executor(tool("get_group_access_records", ToolResult.success(page)))
                        .execute(
                                request(
                                        "access records gid=known startDate=2026-09-07"
                                            + " endDate=2026-09-13",
                                        ALICE));

        assertThat(llm.request.messages().get(0).content())
                .contains(
                        "Never mentally convert an",
                        "quote only a tool-provided formatted time such as occurredAtDisplay",
                        "Without a formatted time, do not invent a clock time",
                        "Do not repeat its rows or calculate",
                        "observed page size, data quality and pagination",
                        "nonempty meta.nextCursor for more pages",
                        "invent a total when no total is supplied");
        assertThat(llm.request.messages().get(1).content())
                .contains("occurredAt", "opaque-next-page", "PARTIAL");
        Map<?, ?> card = (Map<?, ?>) result.cards().get(0);
        assertThat(card.get("rawData")).isEqualTo(page);
        assertThat(((Map<?, ?>) card.get("summary")).get("recordCount")).isEqualTo(2);
        assertThat(((Map<?, ?>) card.get("summary")).containsKey("total")).isFalse();
    }

    @Test
    void unavailableOrUnidentifiedStatisticsNeverExposeMetricsAsUsableObservations() {
        for (String invalid : List.of("availability", "snapshotId", "recoveryEpoch")) {
            Map<String, Object> envelope = evidence("PARTIAL", Map.of("pv", 123L));
            Map<String, Object> meta =
                    new LinkedHashMap<>((Map<String, Object>) envelope.get("meta"));
            meta.put(invalid, "");
            envelope.put("meta", meta);
            AgentRunResult result =
                    executor(tool("get_group_stats", ToolResult.success(envelope)))
                            .execute(request("分析 gid=known 最近7天", ALICE));
            Map<?, ?> card = (Map<?, ?>) result.cards().get(0);
            assertThat((Map<?, ?>) card.get("metrics")).isEmpty();
            assertThat(card.get("message").toString()).contains("不能据此推断为零流量");
        }
    }

    @Test
    void completeInsightPreservesProvenanceAndLabelsHashedIpWithoutInventingAnAddress() {
        String ipHash = "0123456789abcdef";
        Map<String, Object> stats =
                Map.of(
                        "pv",
                        100L,
                        "uv",
                        100L,
                        "uip",
                        100L,
                        "topIpStats",
                        List.of(Map.of("ipHash", ipHash, "cnt", 80L, "ratio", .8D)));
        Map<String, Object> envelope = evidence("COMPLETE", stats);
        AgentRunResult result =
                executor(tool("get_group_stats", ToolResult.success(envelope)))
                        .execute(request("分析 gid=known 最近7天", ALICE));
        Map<?, ?> card =
                result.cards().stream()
                        .map(value -> (Map<?, ?>) value)
                        .filter(value -> "traffic_anomaly".equals(value.get("type")))
                        .findFirst()
                        .orElseThrow();
        assertThat(card.get("meta")).isEqualTo(envelope.get("meta"));
        assertThat(card.get("message").toString()).contains("临时快照", "数据已陈旧", "估算指标");
        Map<?, ?> evidence = (Map<?, ?>) card.get("evidence");
        assertThat(evidence.get("maskedTopIpHash")).isEqualTo("hash: 0123…cdef");
        assertThat(evidence.containsKey("maskedTopIp")).isFalse();
        assertThat(result.toString()).doesNotContain(ipHash).contains("hash: 0123…cdef");
        assertThat(llm.request.messages().get(1).content())
                .doesNotContain(ipHash)
                .contains("hash: 0123…cdef");
    }

    private Map<String, Object> evidence(String completeness, Map<String, Object> metrics) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("availability", "AVAILABLE");
        meta.put("completeness", completeness);
        meta.put("freshness", "STALE");
        meta.put("provisional", true);
        meta.put("snapshotId", "snapshot-123");
        meta.put("recoveryEpoch", "epoch-1");
        meta.put("requestedStart", Instant.parse("2026-09-06T16:00:00Z").toEpochMilli());
        meta.put("requestedEnd", Instant.parse("2026-09-13T16:00:00Z").toEpochMilli());
        meta.put("effectiveEnd", Instant.parse("2026-09-13T16:00:00Z").toEpochMilli());
        meta.put("snapshotCreatedAt", CLOCK.millis());
        meta.put(
                "approximation",
                Map.of("uv", Map.of("type", "APPROXIMATE"), "uip", Map.of("type", "APPROXIMATE")));
        meta.put("missingMetrics", List.of("country", "localeCnStats", "networkStats"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("metrics", Map.of("requested", metrics));
        envelope.put("items", List.of());
        envelope.put("meta", meta);
        return envelope;
    }

    @Test
    void failedGroupLookupDoesNotGuessDefaultGidOrInventTrafficData() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of("pv", 123)));
        AgentRunResult result =
                executor(tool("list_groups", ToolResult.failure("Business API unavailable")), stats)
                        .execute(request("分析默认分组最近7天流量", ALICE));

        assertThat(order).containsExactly("list_groups");
        assertThat(stats.context).isNull();
        assertThat(result.cards().toString()).doesNotContain("stats_summary", "pv=123");
        assertThat(result.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("未猜测目标 gid"));
        assertThat(llm.request.messages().get(1).content())
                .contains("Business API unavailable", "Actual data gaps");
    }

    @Test
    void ambiguousDefaultGroupNamesDoNotSilentlyPickFirst() {
        CapturingTool groups =
                tool(
                        "list_groups",
                        ToolResult.success(
                                List.of(
                                        Map.of("gid", "one", "name", "default"),
                                        Map.of("gid", "two", "name", "默认分组"))));
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        AgentRunResult result = executor(groups, stats).execute(request("分析默认分组最近7天", ALICE));
        assertThat(stats.context).isNull();
        assertThat(result.warnings()).anySatisfy(warning -> assertThat(warning).contains("多个匹配项"));
    }

    @Test
    void soleUnrelatedGroupIsNotAssumedToBeDefault() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        AgentRunResult result =
                executor(groups("Marketing"), stats).execute(request("分析default分组最近7天", ALICE));
        assertThat(stats.context).isNull();
        assertThat(result.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("未能从当前用户"));
    }

    @Test
    void explicitNamedGroupResolvesOnlyFromAuthenticatedGroupList() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        executor(groups("秋季投放"), stats).execute(request("分析 groupName=秋季投放 最近7天表现", ALICE));
        assertThat(stats.context.arguments()).containsEntry("gid", "AliceRealGid");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "stats gid=known startDate=2026-02-30 endDate=2026-03-01",
                "stats gid=known startDate=2026-09-14 endDate=2026-09-13",
                "stats gid=known startDate=2026-09-07",
                "分析 gid=known 最近0天流量"
            })
    void invalidOrIncompleteDatesDoNotReachStatistics(String message) {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        AgentRunResult result = executor(stats).execute(request(message, ALICE));
        assertThat(stats.context).isNull();
        assertThat(result.warnings()).isNotEmpty();
        assertThat(llm.request.messages().get(1).content()).contains("Actual data gaps");
    }

    @Test
    void explicitDatesAreNeverOverriddenByRelativeWords() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        executor(stats)
                .execute(
                        request(
                                "分析 gid=known 最近7天 startDate=2026-08-01 endDate=2026-08-02",
                                ALICE));
        assertThat(stats.context.arguments())
                .containsEntry("startDate", "2026-08-01")
                .containsEntry("endDate", "2026-08-02");
    }

    @Test
    void statisticalFailureProducesWarningCardAndCompletedExecutionContractWithoutFakeMetrics() {
        AgentRunResult result =
                executor(
                                groups("default"),
                                tool(
                                        "get_group_stats",
                                        ToolResult.failure("Analytics unavailable")))
                        .execute(request("分析默认分组最近7天表现", ALICE));
        assertThat(result.cards().toString())
                .contains("tool_warning", "Analytics unavailable")
                .doesNotContain("stats_summary", "pv=0");
        assertThat(llm.request.messages().get(0).content())
                .contains(
                        "No tools are callable",
                        "never invent tool names",
                        "A group list is not traffic data",
                        "never turn absence into zero traffic");
        assertThat(llm.request.messages().get(1).content())
                .contains("Analytics unavailable", "No further tools will run in this turn");
    }

    @Test
    void trustedPrincipalWinsOverFreeTextIdentityAndMismatchedTransportUsername() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        executor(stats)
                .execute(
                        new CampaignAnalysisGraphRequest(
                                "same-session",
                                "mallory",
                                "stats gid=known 最近7天 username=mallory tenantId=999 authVersion=9",
                                "trace",
                                ALICE));
        assertThat(stats.context.username()).isEqualTo("alice");
        assertThat(stats.context.principal()).isEqualTo(ALICE);
        assertThat(stats.context.arguments())
                .doesNotContainKeys("username", "tenantId", "authVersion", "principal");
    }

    @Test
    void missingTrustedPrincipalCannotCallBusinessTools() {
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        AgentRunResult result =
                executor(stats)
                        .execute(
                                new CampaignAnalysisGraphRequest(
                                        "same-session",
                                        "alice",
                                        "stats gid=known 最近7天 username=alice tenantId=101",
                                        "trace"));
        assertThat(stats.context).isNull();
        assertThat(result.warnings()).contains("缺少可信用户身份，未执行业务工具。");
    }

    @Test
    void sameSessionAcrossTenantsUsesSeparateNativeCheckpointsAndFreshGroupResolution()
            throws Exception {
        CapturingTool groups =
                new CapturingTool(
                        "list_groups",
                        context ->
                                ToolResult.success(
                                        List.of(
                                                Map.of(
                                                        "gid",
                                                        context.principal().tenantId() + "-gid",
                                                        "name",
                                                        "default"))));
        CapturingTool stats = tool("get_group_stats", ToolResult.success(Map.of()));
        DefaultCampaignAnalysisGraphExecutor executor = executor(groups, stats);
        AgentPrincipal bob = new AgentPrincipal("202", "bob", 4, false);
        executor.execute(request("分析默认分组最近7天", ALICE));
        AgentRunResult second = executor.execute(request("分析默认分组最近7天", bob));

        assertThat(stats.context.principal()).isEqualTo(bob);
        assertThat(stats.context.arguments()).containsEntry("gid", "202-gid");
        assertThat(second.toString()).doesNotContain("101-gid");
        assertThat(second.traceEvents()).hasSize(6);
        BaseCheckpointSaver saver =
                (BaseCheckpointSaver) ReflectionTestUtils.getField(executor, "checkpointSaver");
        AgentProperties properties = new AgentProperties();
        for (String scope : List.of("alice:101:3:same-session", "bob:202:4:same-session")) {
            String nativeId =
                    AgentGraphThreadKeyFactory.create(
                            properties.getGraph().getName(),
                            properties.getGraph().getVersion(),
                            scope);
            assertThat(saver.get(RunnableConfig.builder().threadId(nativeId).build())).isPresent();
        }
        String unsafeId =
                AgentGraphThreadKeyFactory.create(
                        properties.getGraph().getName(),
                        properties.getGraph().getVersion(),
                        "same-session");
        assertThat(saver.get(RunnableConfig.builder().threadId(unsafeId).build())).isEmpty();
    }

    private CapturingTool groups(String name) {
        return tool(
                "list_groups",
                ToolResult.success(List.of(Map.of("gid", "AliceRealGid", "name", name))));
    }

    private CapturingTool tool(String name, ToolResult result) {
        return new CapturingTool(name, ignored -> result);
    }

    private CampaignAnalysisGraphRequest request(String message, AgentPrincipal principal) {
        return new CampaignAnalysisGraphRequest(
                "same-session", principal.username(), message, "same-trace", principal);
    }

    private DefaultCampaignAnalysisGraphExecutor executor(AgentTool... tools) {
        GraphCheckpointStore checkpoints =
                new GraphCheckpointStore() {
                    public void save(GraphCheckpoint checkpoint) {}

                    public Optional<GraphCheckpoint> loadLatest(
                            String thread, String graph, String version) {
                        return Optional.empty();
                    }
                };
        return new DefaultCampaignAnalysisGraphExecutor(
                llm,
                checkpoints,
                new AgentProperties(),
                new AgentToolRegistry(List.of(tools)),
                CLOCK);
    }

    private class CapturingTool implements AgentTool {
        private final String name;
        private final Function<ToolContext, ToolResult> result;
        private ToolContext context;

        private CapturingTool(String name, Function<ToolContext, ToolResult> result) {
            this.name = name;
            this.result = result;
        }

        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "test", Map.of());
        }

        public ToolResult execute(ToolContext context) {
            this.context = context;
            order.add(name);
            return result.apply(context);
        }
    }

    private static class CapturingLlm implements LlmChatClient {
        private DeepSeekChatRequest request;

        public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
            this.request = request;
            return new DeepSeekChatResponse("id", "model", "基于已有工具结果的说明。", "stop", null);
        }
    }
}
