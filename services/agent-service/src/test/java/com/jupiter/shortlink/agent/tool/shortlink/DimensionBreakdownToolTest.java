package com.jupiter.shortlink.agent.tool.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class DimensionBreakdownToolTest {
    private static final String BASE = "/internal/short-link-admin/v1/agent-tools/statistics";
    private static final AgentPrincipal ALICE = new AgentPrincipal("101", "alice", 3, false);
    private static final org.springframework.ai.chat.model.ToolContext TRUSTED =
            new org.springframework.ai.chat.model.ToolContext(Map.of("sessionId", "session", "username", "mallory", "principal", ALICE.toState()));
    private static final List<String> DIMENSIONS = List.of("province", "device");
    private final List<Request> calls = new ArrayList<>();

    @Test
    void jointRowsPreserveUnknownAndNotApplicableAndNeverSumBucketUv() {
        var tool = tool(request -> ToolResult.success(envelope(
                List.of(bucket("广东省", "KNOWN", "PC", 2, 0.5), bucket(null, "UNKNOWN", "MOBILE", 1, 0.25),
                        bucket(null, "NOT_APPLICABLE", "PC", 1, 0.25)), 4, meta("snapshot", DIMENSIONS, List.of()))));
        var result = run(tool, DIMENSIONS, List.of());

        assertThat(result).containsEntry("type", "dimension_breakdown").containsEntry("status", "READY")
                .containsEntry("gid", "alpha").containsEntry("startDate", "2026-09-07").containsEntry("endDate", "2026-09-13");
        assertThat(rows(result)).hasSize(3);
        assertThat(cell(rows(result).get(1), "province")).containsEntry("state", "UNKNOWN").containsEntry("value", null);
        assertThat(cell(rows(result).get(2), "province")).containsEntry("state", "NOT_APPLICABLE").containsEntry("value", null);
        assertThat(map(result.get("metrics"))).containsEntry("uv", 1L).containsEntry("ratioDenominator", 4L);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).method).isEqualTo("POST");
        assertThat(calls.get(0).path).isEqualTo(BASE + "/dimensions");
        assertThat(calls.get(0).arguments).containsEntry("dimensions", DIMENSIONS).containsEntry("pageSize", 500);
        assertThat(calls.get(0).context.principal()).isEqualTo(ALICE);
        assertThat(calls.get(0).context.username()).isEqualTo("alice");
    }

    @Test
    void filtersAreCanonicalExactValuesWithoutInventingProvinceOrDeviceMappings() {
        var filters = List.<Map<String, Object>>of(filter("refererDomain", "z.test", "a.test", "z.test"),
                filter("province", "广东"), filter("device", "mobile"));
        var canonical = DimensionQuery.filters(filters);
        var tool = tool(request -> {
            assertThat(request.arguments.get("filters")).isEqualTo(canonical);
            return ToolResult.success(envelope(List.of(bucket("广东", "KNOWN", "mobile", 1, 1.0)), 1, meta("snapshot", DIMENSIONS, canonical)));
        });
        var result = run(tool, DIMENSIONS, filters);

        assertThat(result).containsEntry("status", "READY").containsEntry("filters", canonical);
        assertThat(canonical.get(0)).containsEntry("dimension", "device").containsEntry("values", List.of("mobile"));
        assertThat(canonical.get(1)).containsEntry("values", List.of("广东"));
        assertThat(canonical.get(2)).containsEntry("values", List.of("a.test", "z.test"));
        assertThat(result.get("warnings").toString()).contains("筛选后的数据", "不能直接等同广告渠道");
    }

    @Test
    void unknownFiltersHaveNoConcreteValuesAndEmptyValuesCanonicalizeToOmitted() {
        var filters = List.<Map<String, Object>>of(Map.of("dimension", "province", "operator", "IS_UNKNOWN", "values", List.of()));
        var canonical = List.<Map<String, Object>>of(Map.of("dimension", "province", "operator", "IS_UNKNOWN"));
        var tool = tool(request -> ToolResult.success(envelope(List.of(bucket(null, "UNKNOWN", "PC", 1, 1.0)), 1, meta("snapshot", DIMENSIONS, canonical))));
        var result = run(tool, DIMENSIONS, filters);

        assertThat(result).containsEntry("status", "READY").containsEntry("filters", canonical);
        assertThat(calls.get(0).arguments).containsEntry("filters", canonical);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidDimensionsAndFiltersDoNotReachTheGateway(InvalidRequest invalid) {
        var tool = tool(request -> ToolResult.success(Map.of()));
        var result = tool.getDimensionBreakdown("alpha", "2026-09-07", "2026-09-13", invalid.dimensions,
                invalid.filters, null, null, TRUSTED);
        assertThat(result.success()).isFalse();
        assertThat(calls).isEmpty();
    }

    static Stream<InvalidRequest> invalidRequests() {
        return Stream.of(
                new InvalidRequest(List.of(), List.of()),
                new InvalidRequest(List.of("province", "province"), List.of()),
                new InvalidRequest(List.of("province", "device", "browser", "os"), List.of()),
                new InvalidRequest(List.of("campaignCost"), List.of()),
                new InvalidRequest(DIMENSIONS, List.of(Map.of("dimension", "province", "operator", "LIKE", "values", List.of("广东%")))),
                new InvalidRequest(DIMENSIONS, List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of()))),
                new InvalidRequest(DIMENSIONS, List.of(Map.of("dimension", "hour", "operator", "IN", "values", List.of(1)))),
                new InvalidRequest(DIMENSIONS, List.of(filter("hour", "01"))),
                new InvalidRequest(DIMENSIONS, List.of(filter("hour", "24"))),
                new InvalidRequest(DIMENSIONS, List.of(filter("weekday", "0"))),
                new InvalidRequest(DIMENSIONS, List.of(filter("day", "2026-02-30"))),
                new InvalidRequest(DIMENSIONS, List.of(filter("browser", "one\ntwo"))),
                new InvalidRequest(DIMENSIONS, List.of(filter("province", "广东省"), filter("province", "北京市"))),
                new InvalidRequest(DIMENSIONS, List.of(Map.of("dimension", "province", "operator", "IS_UNKNOWN", "values", List.of("广东省")))));
    }

    @Test
    void synchronousPaginationUsesPostAndTheSameFiltersSnapshotAndFullWindowDenominator() {
        var filters = List.<Map<String, Object>>of(filter("device", "PC"));
        var tool = tool(request -> {
            var metadata = meta("snapshot", DIMENSIONS, filters);
            if (!request.arguments.containsKey("cursor")) metadata.put("nextCursor", "next-bucket-page");
            else assertThat(request.arguments).containsEntry("snapshotId", "snapshot").containsEntry("cursor", "next-bucket-page")
                    .containsEntry("filters", filters).containsEntry("dimensions", DIMENSIONS);
            return ToolResult.success(envelope(List.of(bucket(request.arguments.containsKey("cursor") ? "北京市" : "广东省", "KNOWN", "PC", 1, 0.5)), 2, metadata));
        });
        var result = run(tool, DIMENSIONS, filters);

        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result)).hasSize(2);
        assertThat(map(result.get("metrics"))).containsEntry("uv", 1L).containsEntry("ratioDenominator", 2L);
        assertThat(map(result.get("meta"))).containsEntry("totalRows", 2).containsEntry("resultComplete", true).doesNotContainKeys("nextCursor", "nextPageIndex");
        assertThat(calls).hasSize(2).allSatisfy(request -> assertThat(request.method).isEqualTo("POST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"filters", "dimensions", "snapshot", "denominator", "missingPage"})
    void changedOrUnavailablePagesCannotBePresentedAsTheRequestedJointResult(String failure) {
        var tool = tool(request -> {
            var metadata = meta("snapshot", DIMENSIONS, List.of());
            if (!request.arguments.containsKey("cursor")) {
                metadata.put("nextCursor", "next");
                return ToolResult.success(envelope(List.of(bucket("广东省", "KNOWN", "PC", 1, 0.5)), 2, metadata));
            }
            if (failure.equals("missingPage")) return ToolResult.failure("SNAPSHOT_EXPIRED");
            if (failure.equals("filters")) metadata.put("filters", List.of(filter("province", "北京市")));
            if (failure.equals("dimensions")) metadata.put("dimensions", List.of("province", "browser"));
            if (failure.equals("snapshot")) metadata.put("snapshotId", "other-snapshot");
            return ToolResult.success(envelope(List.of(bucket("北京市", "KNOWN", "PC", 1, 0.5)), failure.equals("denominator") ? 3 : 2, metadata));
        });
        var result = run(tool, DIMENSIONS, List.of());
        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ratio", "duplicate", "summary", "truncated", "qualityScope", "unknownValue"})
    void invalidBucketsOrIncompleteMaterializationAreNotReplacedWithMarginalDistributions(String failure) {
        var tool = tool(request -> {
            var metadata = meta("snapshot", DIMENSIONS, List.of());
            if (failure.equals("truncated")) metadata.put("truncated", true);
            if (failure.equals("qualityScope")) metadata.put("dimensionQualityScope", "RETURNED_PAGE");
            var first = bucket(failure.equals("unknownValue") ? "伪造省份" : "广东省", failure.equals("unknownValue") ? "UNKNOWN" : "KNOWN", "PC", 1, failure.equals("ratio") ? 0.2 : 0.5);
            var second = bucket(failure.equals("duplicate") ? "广东省" : "北京市", "KNOWN", "PC", 1, 0.5);
            return ToolResult.success(envelope(List.of(first, second), failure.equals("summary") ? 3 : 2, metadata));
        });
        var result = run(tool, DIMENSIONS, List.of());
        assertThat(result).containsEntry("status", "INCOMPLETE");
        assertThat(rows(result)).isEmpty();
        assertThat(map(result.get("meta"))).containsEntry("resultComplete", false);
    }

    @Test
    void aZeroResultKeepsZeroWholeWindowCountsAndNoInventedZeroBuckets() {
        var tool = tool(request -> ToolResult.success(envelope(List.of(), 0, meta("snapshot", DIMENSIONS, List.of()))));
        var result = run(tool, DIMENSIONS, List.of());
        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result)).isEmpty();
        assertThat(map(result.get("metrics"))).containsEntry("pv", 0L).containsEntry("ratioDenominator", 0L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void visitorApproximationWarningFollowsTheBackendContractWithoutChangingEvidence(boolean approximate) {
        var approximation = Map.of("pv", Map.of("type", "EXACT"),
                "uv", Map.of("type", approximate ? "APPROXIMATE" : "EXACT", "algorithm", approximate ? "uniqCombined64" : "exact"),
                "uip", Map.of("type", "EXACT"));
        var metadata = meta("snapshot", DIMENSIONS, List.of());
        metadata.put("approximation", approximation);
        var tool = tool(request -> ToolResult.success(envelope(
                List.of(bucket("广东省", "KNOWN", "PC", 1, 1.0)), 1, metadata)));

        var result = run(tool, DIMENSIONS, List.of());

        assertThat(result).containsEntry("status", "READY");
        assertThat(map(result.get("meta"))).containsEntry("approximation", approximation);
        assertThat(map(result.get("metrics"))).containsEntry("pv", 1L).containsEntry("uv", 1L);
        var warnings = ((List<?>) result.get("warnings")).stream().map(Object::toString)
                .filter(warning -> warning.contains("近似统计口径")).toList();
        if (approximate) assertThat(warnings).containsExactly("UV 保留原有近似统计口径");
        else assertThat(warnings).isEmpty();
    }

    @Test
    void completeFiveThousandBucketResultIsReturnedForLocalUiPaginationWithoutTopTruncation() {
        var tool = tool(request -> {
            int page = calls.size() - 1;
            var metadata = meta("large-snapshot", List.of("browser"), List.of());
            metadata.put("totalRows", 5000);
            if (page < 9) metadata.put("nextCursor", "page-" + (page + 1));
            List<Map<String, Object>> items = new ArrayList<>();
            for (int row = 0; row < 500; row++) items.add(Map.of("dimensions", Map.of("browser", Map.of("value", "browser-" + (page * 500 + row), "state", "KNOWN")),
                    "pv", 1, "uv", 1, "uip", 1, "pvRatio", 0.0002));
            return ToolResult.success(envelope(items, 5000, metadata));
        });
        var result = run(tool, List.of("browser"), List.of());
        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result)).hasSize(5000);
        assertThat(map(result.get("meta"))).containsEntry("totalRows", 5000).containsEntry("truncated", false).containsEntry("resultComplete", true);
        assertThat(calls).hasSize(10);
    }

    @Test
    void longRangePendingJobsCarryTheExactNormalizedDimensionRequest() {
        var filters = List.<Map<String, Object>>of(filter("refererDomain", "b.test", "a.test"));
        var tool = tool(request -> {
            assertThat(request.arguments).containsEntry("queryKind", "DIMENSION_BREAKDOWN")
                    .containsEntry("dimensions", DIMENSIONS).containsEntry("filters", DimensionQuery.filters(filters));
            return ToolResult.success(Map.of("jobId", "job-breakdown", "state", "QUEUED"));
        });
        var result = data(tool.getDimensionBreakdown("alpha", "2026-08-15", "2026-09-13", DIMENSIONS, filters, null, null, TRUSTED));

        assertThat(result).containsEntry("status", "PENDING");
        assertThat(rows(result)).isEmpty();
        assertThat(map(result.get("continuation"))).containsEntry("jobId", "job-breakdown").containsEntry("dimensions", DIMENSIONS)
                .containsEntry("filters", DimensionQuery.filters(filters)).containsEntry("startDate", "2026-08-15");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path).isEqualTo(BASE + "/jobs");
    }

    @Test
    void pendingContinuationChecksOnceWithoutResubmittingOrPolling() {
        var tool = tool(request -> ToolResult.success(Map.of("jobId", "job-breakdown", "state", "RUNNING")));
        var result = data(tool.getDimensionBreakdown("alpha", "2026-08-15", "2026-09-13", DIMENSIONS, List.of(), null, "job-breakdown", TRUSTED));
        assertThat(result).containsEntry("status", "PENDING");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).method).isEqualTo("GET");
        assertThat(calls.get(0).path).isEqualTo(BASE + "/jobs/job-breakdown");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void succeededJobRequiresMatchingFiltersBeforeItsMetricsCanBeUsed(boolean filtersMatch) {
        var filters = List.<Map<String, Object>>of(filter("device", "PC"));
        var tool = tool(request -> {
            if (!request.path.endsWith("/page")) return ToolResult.success(Map.of("jobId", "job-breakdown", "state", "SUCCEEDED"));
            var metadata = jobMeta(DIMENSIONS, filtersMatch ? filters : List.of(filter("device", "MOBILE")));
            return ToolResult.success(envelope(List.of(bucket("广东省", "KNOWN", "PC", 2, 1.0)), 2, metadata));
        });
        var result = data(tool.getDimensionBreakdown("alpha", "2026-08-15", "2026-09-13", DIMENSIONS, filters, null, "job-breakdown", TRUSTED));
        assertThat(result).containsEntry("status", filtersMatch ? "READY" : "INCOMPLETE");
        assertThat(rows(result)).hasSize(filtersMatch ? 1 : 0);
        assertThat(calls).hasSize(2);
    }

    @Test
    void asyncPaginationFollowsTheExactReturnedPageIndexAndRetainsWholeWindowUv() {
        var tool = tool(request -> {
            if (!request.path.endsWith("/page")) return ToolResult.success(Map.of("jobId", "job-breakdown", "state", "SUCCEEDED"));
            var metadata = jobMeta(DIMENSIONS, List.of());
            int index = ((Number) request.arguments.get("pageIndex")).intValue();
            if (index == 0) metadata.put("nextPageIndex", 3);
            return ToolResult.success(envelope(List.of(bucket(index == 0 ? "广东省" : "北京市", "KNOWN", "PC", 1, 0.5)), 2, metadata));
        });
        var result = data(tool.getDimensionBreakdown("alpha", "2026-08-15", "2026-09-13", DIMENSIONS, List.of(), null, "job-breakdown", TRUSTED));
        assertThat(result).containsEntry("status", "READY");
        assertThat(rows(result)).hasSize(2);
        assertThat(map(result.get("metrics"))).containsEntry("uv", 1L);
        assertThat(calls).hasSize(3);
        assertThat(calls.get(2).arguments).containsEntry("pageIndex", 3);
    }

    @Test
    void synchronousTooLargeResponseSubmitsTheSameJointQueryAndDoesNotFallBackToStats() {
        var tool = tool(request -> request.path.endsWith("/dimensions") ? ToolResult.failure("TOO_LARGE")
                : ToolResult.success(Map.of("jobId", "job-breakdown", "state", "QUEUED")));
        var result = run(tool, DIMENSIONS, List.of(filter("device", "PC")));
        assertThat(result).containsEntry("status", "PENDING");
        assertThat(calls).hasSize(2).allSatisfy(request -> assertThat(request.method).isEqualTo("POST"));
        assertThat(calls.get(1).arguments).containsEntry("queryKind", "DIMENSION_BREAKDOWN").containsEntry("dimensions", DIMENSIONS)
                .containsEntry("filters", List.of(filter("device", "PC")));
    }

    @Test
    void generatedCallbackSupportsNestedFilterMapsAndOmittedOptionalFields() {
        var tool = tool(request -> ToolResult.success(envelope(List.of(bucket("广东省", "KNOWN", "PC", 1, 1.0)), 1,
                meta("snapshot", DIMENSIONS, DimensionQuery.filters(request.arguments.get("filters"))))));
        var registry = new AgentToolRegistry(MethodToolCallbackProvider.builder().toolObjects(tool).build());
        var callback = registry.findByName("get_dimension_breakdown").orElseThrow();
        var arguments = new LinkedHashMap<String, Object>(Map.of("gid", "alpha", "startDate", "2026-09-07", "endDate", "2026-09-13", "dimensions", DIMENSIONS));
        assertThat(data(callback.execute(new ToolContext("session", "alice", arguments, ALICE)))).containsEntry("status", "READY");
        arguments.put("filters", List.of(filter("device", "PC")));
        assertThat(data(callback.execute(new ToolContext("session", "alice", arguments, ALICE)))).containsEntry("status", "READY");
        assertThat(calls).hasSize(2);
    }

    private Map<String, Object> run(DimensionBreakdownTool tool, List<String> dimensions, List<Map<String, Object>> filters) {
        return data(tool.getDimensionBreakdown("alpha", "2026-09-07", "2026-09-13", dimensions, filters, null, null, TRUSTED));
    }
    private DimensionBreakdownTool tool(Function<Request, ToolResult> handler) {
        return new DimensionBreakdownTool(new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> arguments) {
                var request = new Request("GET", path, new LinkedHashMap<>(arguments), context);
                calls.add(request); return handler.apply(request);
            }
            public ToolResult post(String path, ToolContext context, Map<String, Object> arguments) {
                var request = new Request("POST", path, new LinkedHashMap<>(arguments), context);
                calls.add(request); return handler.apply(request);
            }
        });
    }
    private static Map<String, Object> meta(String snapshot, List<String> dimensions, List<Map<String, Object>> filters) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("snapshotId", snapshot); meta.put("queryKind", "DIMENSION_BREAKDOWN"); meta.put("gid", "alpha");
        meta.put("dimensions", dimensions); meta.put("filters", filters); meta.put("resultComplete", true);
        meta.put("dimensionQualityScope", "FILTERED_FULL_WINDOW"); meta.put("availability", "AVAILABLE");
        meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("metricVersion", "click-v1");
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        return meta;
    }
    private static Map<String, Object> jobMeta(List<String> dimensions, List<Map<String, Object>> filters) {
        var meta = meta("job-breakdown", dimensions, filters);
        meta.put("groupScopeComplete", true);
        meta.put("requestedStart", LocalDate.parse("2026-08-15").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        meta.put("requestedEnd", LocalDate.parse("2026-09-13").plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
        return meta;
    }
    private static Map<String, Object> envelope(List<Map<String, Object>> rows, long pv, Map<String, Object> meta) {
        return Map.of("items", rows, "metrics", Map.of("requested", Map.of("pv", pv, "uv", pv == 0 ? 0L : 1L,
                "uip", pv == 0 ? 0L : 1L, "ratioDenominator", pv)), "meta", meta);
    }
    private static Map<String, Object> bucket(String province, String state, String device, long pv, double ratio) {
        var provinceCell = new LinkedHashMap<String, Object>();
        provinceCell.put("value", province); provinceCell.put("state", state);
        return Map.of("dimensions", Map.of("province", provinceCell, "device", Map.of("value", device, "state", "KNOWN")),
                "pv", pv, "uv", 1L, "uip", 1L, "pvRatio", ratio);
    }
    private static Map<String, Object> filter(String dimension, String... values) {
        return Map.of("dimension", dimension, "operator", "IN", "values", List.of(values));
    }
    private static Map<String, Object> data(ToolResult result) { assertThat(result.success()).as(result.message()).isTrue(); return map(result.data()); }
    private static List<Map<String, Object>> rows(Map<String, Object> result) { return ((List<?>) result.get("rows")).stream().map(DimensionBreakdownToolTest::map).toList(); }
    private static Map<String, Object> cell(Map<String, Object> row, String dimension) { return map(map(row.get("dimensions")).get(dimension)); }
    private static Map<String, Object> map(Object value) {
        var result = new LinkedHashMap<String, Object>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private record Request(String method, String path, Map<String, Object> arguments, ToolContext context) {}
    private record InvalidRequest(List<String> dimensions, List<Map<String, Object>> filters) {}
}
