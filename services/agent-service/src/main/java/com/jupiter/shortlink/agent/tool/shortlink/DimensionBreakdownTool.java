package com.jupiter.shortlink.agent.tool.shortlink;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** A true joint breakdown; independent marginal distributions cannot substitute for these rows. */
@Component
public class DimensionBreakdownTool {
    private static final List<String> METRICS = List.of("pv", "uv", "uip");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("day", "日期"), Map.entry("hour", "小时"), Map.entry("weekday", "星期"),
            Map.entry("country", "国家或地区"), Map.entry("province", "省份"), Map.entry("device", "设备"),
            Map.entry("os", "操作系统"), Map.entry("browser", "浏览器"), Map.entry("isp", "运营商"),
            Map.entry("refererDomain", "来源域"));
    private final CampaignStatisticsTools statistics;

    public DimensionBreakdownTool(ShortLinkBusinessGateway gateway) {
        statistics = new CampaignStatisticsTools(gateway);
    }

    @Tool(name = "get_dimension_breakdown", description = "Get a true joint click breakdown for one to three dimensions, "
            + "with exact IN or IS_UNKNOWN filters. Retrieves the complete frozen result before presenting it. "
            + "The denominator and distinct UV/UIP describe the filtered whole window, not summed bucket UV. "
            + "Keep UNKNOWN and NOT_APPLICABLE separate; refererDomain is not an attributed ad channel. "
            + "Long periods return continuation arguments for a later user request.")
    public ToolResult getDimensionBreakdown(
            @ToolParam(description = "Owned group id.") String gid,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd.") String startDate,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd; at most 180 calendar days.") String endDate,
            @ToolParam(description = "One to three unique dimensions in output order: day, hour, weekday, country, province, device, os, browser, isp, refererDomain.") List<String> dimensions,
            @ToolParam(required = false, description = "Up to eight unique dimension filters: dimension, operator IN with string values or IS_UNKNOWN without values; exact stored values only.") List<Map<String, Object>> filters,
            @ToolParam(required = false, description = "Optional exact short-link URL within the group.") String fullShortUrl,
            @ToolParam(required = false, description = "Exact DIMENSION_BREAKDOWN jobId returned in continuation.") String jobId,
            org.springframework.ai.chat.model.ToolContext trusted) {
        try {
            var context = CampaignStatisticsTools.trustedContext(trusted);
            List<String> canonicalDimensions = DimensionQuery.dimensions(dimensions);
            List<Map<String, Object>> canonicalFilters = DimensionQuery.filters(filters);
            Map<String, Object> query = query(gid, startDate, endDate, fullShortUrl, canonicalDimensions, canonicalFilters);
            CampaignStatisticsTools.Fetch fetched = statistics.fetchDimensions(query, text(jobId), context);
            Map<String, Object> result = new LinkedHashMap<>(query);
            result.put("type", "dimension_breakdown");
            result.put("status", fetched.status());
            result.put("columns", columns(canonicalDimensions));
            if (!"READY".equals(fetched.status())) {
                result.put("rows", List.of());
                result.put("metrics", Map.of());
                result.put("meta", Map.of("resultComplete", false, "truncated", false,
                        "dimensionQualityScope", "FILTERED_FULL_WINDOW"));
                result.put("warnings", List.of(fetched.message()));
                if ("PENDING".equals(fetched.status())) {
                    Map<String, Object> continuation = new LinkedHashMap<>(query);
                    continuation.put("jobId", fetched.jobId());
                    result.put("continuation", continuation);
                }
                return ToolResult.success(result);
            }
            Map<String, Object> data = fetched.data();
            Map<String, Object> summary = map(map(data.get("metrics")).get("requested"));
            Map<String, Object> meta = map(data.get("meta"));
            List<Map<String, Object>> rows = rows(data.get("items"));
            String error = verify(rows, summary, meta, canonicalDimensions);
            if (error != null) {
                result.put("status", "INCOMPLETE");
                result.put("rows", List.of());
                result.put("metrics", Map.of());
                meta.put("resultComplete", false);
                result.put("meta", meta);
                result.put("warnings", List.of(error));
                return ToolResult.success(result);
            }
            meta.put("resultComplete", true);
            meta.put("truncated", false);
            meta.put("totalRows", rows.size());
            // All frozen pages have already been read; only the UI's local table pagination remains.
            meta.remove("nextCursor");
            meta.remove("nextPageIndex");
            result.put("rows", rows);
            result.put("metrics", summary);
            result.put("meta", meta);
            result.put("warnings", warnings(meta, canonicalDimensions, canonicalFilters));
            return ToolResult.success(result);
        } catch (IllegalArgumentException invalid) {
            return ToolResult.failure(invalid.getMessage());
        }
    }

    private static Map<String, Object> query(String gid, String startDate, String endDate,
            String fullShortUrl, List<String> dimensions, List<Map<String, Object>> filters) {
        require(!text(gid).isEmpty(), "请提供分组标识");
        LocalDate start, end;
        try {
            start = LocalDate.parse(text(startDate));
            end = LocalDate.parse(text(endDate));
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("请提供有效的统计起止日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        require(days >= 1 && days <= 180, "联合分析期间必须为 1 至 180 个自然日");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gid", text(gid));
        if (!text(fullShortUrl).isEmpty()) result.put("fullShortUrl", text(fullShortUrl));
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("dimensions", dimensions);
        result.put("filters", filters);
        return result;
    }

    private static String verify(List<Map<String, Object>> rows, Map<String, Object> summary,
            Map<String, Object> meta, List<String> dimensions) {
        if (!Boolean.TRUE.equals(meta.get("resultComplete")) || Boolean.TRUE.equals(meta.get("truncated"))
                || !"FILTERED_FULL_WINDOW".equals(meta.get("dimensionQualityScope")))
            return "结果未证明已完成完整的筛选后联合聚合，未用独立维度分布替代交叉数据";
        if (!validMetrics(summary)) return "筛选后整段期间汇总缺失或无效，未推测总体 PV、UV、UIP";
        BigDecimal denominator = number(summary.get("ratioDenominator"));
        if (denominator == null || denominator.compareTo(number(summary.get("pv"))) != 0)
            return "占比分母与筛选后整段期间 PV 不一致";
        BigDecimal totalPv = BigDecimal.ZERO;
        Set<List<Object>> buckets = new LinkedHashSet<>();
        for (var row : rows) {
            if (!validMetrics(row)) return "联合维度桶的统计值缺失或无效，未返回不完整结果";
            Map<String, Object> cells = map(row.get("dimensions"));
            if (cells.size() != dimensions.size()) return "结果维度与请求的联合维度不一致";
            List<Object> key = new ArrayList<>();
            for (String dimension : dimensions) {
                Map<String, Object> cell = map(cells.get(dimension));
                String state = text(cell.get("state"));
                Object value = cell.get("value");
                if (!Set.of("KNOWN", "UNKNOWN", "NOT_APPLICABLE").contains(state)) return "联合维度桶缺少明确的值状态";
                if ("KNOWN".equals(state) && (!(value instanceof String) || value.toString().isBlank())) return "已知维度桶缺少字符串值";
                if (!"KNOWN".equals(state) && value != null) return "未知或不适用维度桶不能包含伪造的已知值";
                if ("NOT_APPLICABLE".equals(state) && !"province".equals(dimension)) return "不适用维度状态与省份统计口径不符";
                key.add(state);
                key.add(value);
            }
            if (!buckets.add(key)) return "联合维度桶重复，无法确认完整聚合结果";
            BigDecimal pv = number(row.get("pv"));
            totalPv = totalPv.add(pv);
            BigDecimal ratio = number(row.get("pvRatio"));
            if (denominator.signum() == 0) {
                if (row.get("pvRatio") != null) return "零分母不能产生有效占比";
            } else {
                BigDecimal expected = pv.divide(denominator, 12, RoundingMode.HALF_UP);
                if (ratio == null || ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0
                        || ratio.subtract(expected).abs().compareTo(new BigDecimal("0.00000001")) > 0)
                    return "联合维度桶的 PV 占比与筛选后完整分母不一致";
            }
        }
        return totalPv.compareTo(denominator) == 0 ? null : "联合维度桶的 PV 合计与筛选后整段汇总不一致";
    }

    private static List<String> warnings(Map<String, Object> meta, List<String> dimensions, List<Map<String, Object>> filters) {
        List<String> result = new ArrayList<>();
        result.add("PV 占比的分母是筛选后的整段期间 PV；UV、UIP 按整段期间单独去重，不能直接累加各桶值。");
        if (!filters.isEmpty()) result.add("维度覆盖率仅描述筛选后的数据；筛选后覆盖完整不代表原始分组采集完整。");
        if (!"AVAILABLE".equals(meta.get("availability")) || !"COMPLETE".equals(meta.get("completeness"))
                || !"FRESH".equals(meta.get("freshness")) || Boolean.TRUE.equals(meta.get("provisional")))
            result.add("当前数据不完整、已过期或质量未知，表格仅描述已观测记录，不代表完整实际流量。");
        if (!"COMPLETE".equals(map(meta.get("collectionQuality")).get("status")))
            result.add("采集完整性未得到证明，保留原始数据质量限制。");
        CampaignStatisticsTools.addApproximationWarning(meta, result, "");
        if (dimensions.contains("province")) result.add("省份来自离线 IP 归属；未知与已知非中国访问的不适用状态分开保留。");
        if (dimensions.contains("refererDomain") || filters.stream().anyMatch(filter -> "refererDomain".equals(filter.get("dimension"))))
            result.add("来源域只代表已记录的来源主机，不能直接等同广告渠道；空来源为未知，不推断为直接访问。");
        return result;
    }

    private static List<Map<String, Object>> columns(List<String> dimensions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String dimension : dimensions) result.add(Map.of("key", dimension, "label", LABELS.get(dimension), "dimension", true));
        for (String metric : METRICS) result.add(Map.of("key", metric, "label", metric.toUpperCase(java.util.Locale.ROOT)));
        result.add(Map.of("key", "pvRatio", "label", "筛选后 PV 占比"));
        return result;
    }

    private static boolean validMetrics(Map<String, Object> values) {
        return METRICS.stream().allMatch(metric -> {
            BigDecimal value = number(values.get(metric));
            return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
        });
    }
    private static BigDecimal number(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException invalid) { return null; }
    }
    private static List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> values) values.forEach(row -> result.add(map(row)));
        return result;
    }
    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) values.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
