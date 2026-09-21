package com.jupiter.shortlink.agent.tool.shortlink;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure comparison projection over already fetched, whole-window statistics. */
final class CampaignStatisticsComparisonCalculator {
    private static final List<String> METRICS = List.of("pv", "uv", "uip");

    private CampaignStatisticsComparisonCalculator() {}

    static List<Map<String, Object>> calculate(List<Map<String, Object>> rows,
            List<Map<String, Object>> scopes, List<Map<String, Object>> periods, Clock clock) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        rows.forEach(row -> indexed.put(text(row.get("key")), row));
        List<Map<String, Object>> result = new ArrayList<>();
        for (var scope : scopes) for (int index = 1; index < periods.size(); index++)
            differences(indexed.get(key(query(scope, periods.get(0)))),
                    indexed.get(key(query(scope, periods.get(index)))), "PERIOD", result, clock);
        for (var period : periods) for (int index = 1; index < scopes.size(); index++)
            differences(indexed.get(key(query(scopes.get(index), period))),
                    indexed.get(key(query(scopes.get(0), period))), "OBJECT", result, clock);
        return result;
    }

    private static void differences(Map<String, Object> target, Map<String, Object> baseline,
            String kind, List<Map<String, Object>> output, Clock clock) {
        if (target == null || baseline == null) return;
        List<String> warnings = new ArrayList<>();
        boolean comparable = "READY".equals(target.get("status")) && "READY".equals(baseline.get("status"));
        if (!comparable) warnings.add("至少一个整段期间汇总不可用");
        Map<String, Object> targetMeta = map(target.get("quality")), baselineMeta = map(baseline.get("quality"));
        if (!complete(targetMeta) || !complete(baselineMeta)) {
            comparable = false;
            warnings.add("数据不完整或已过期，差值仅描述已观测记录，不据此推断增长率");
        }
        if (text(targetMeta.get("metricVersion")).isEmpty()
                || !Objects.equals(targetMeta.get("metricVersion"), baselineMeta.get("metricVersion"))) {
            comparable = false;
            warnings.add("指标版本缺失或不一致");
        }
        LocalDate today = LocalDate.now(clock);
        if (!LocalDate.parse(text(target.get("endDate"))).isBefore(today)
                || !LocalDate.parse(text(baseline.get("endDate"))).isBefore(today)) {
            comparable = false;
            warnings.add("请求期间尚未结束，当前观测值不能作为完整期间对比");
        }
        if ("PERIOD".equals(kind)) {
            LocalDate ts = LocalDate.parse(text(target.get("startDate"))), te = LocalDate.parse(text(target.get("endDate")));
            LocalDate bs = LocalDate.parse(text(baseline.get("startDate"))), be = LocalDate.parse(text(baseline.get("endDate")));
            if (ChronoUnit.DAYS.between(ts, te) != ChronoUnit.DAYS.between(bs, be)) {
                comparable = false;
                warnings.add("两个期间长度不同");
            }
            if (!(te.isBefore(bs) || be.isBefore(ts))) {
                comparable = false;
                warnings.add("两个期间存在重叠");
            }
        }
        for (String metric : METRICS) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("kind", kind);
            comparison.put("targetKey", target.get("key"));
            comparison.put("baselineKey", baseline.get("key"));
            comparison.put("metric", metric);
            BigDecimal current = optionalNumber(target.get(metric)), previous = optionalNumber(baseline.get(metric));
            BigDecimal delta = current == null || previous == null ? null : current.subtract(previous);
            comparison.put("delta", delta);
            comparison.put("rate", comparable && delta != null ? ratio(delta, previous) : null);
            comparison.put("comparable", comparable);
            List<String> reasons = new ArrayList<>(warnings);
            if (previous != null && previous.signum() == 0) reasons.add("基期为零，变化率无定义");
            comparison.put("warnings", reasons);
            output.add(comparison);
        }
    }

    private static Map<String, Object> query(Map<String, Object> scope, Map<String, Object> period) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gid", scope.get("gid"));
        if (!text(scope.get("fullShortUrl")).isEmpty()) result.put("fullShortUrl", scope.get("fullShortUrl"));
        result.put("startDate", period.get("startDate"));
        result.put("endDate", period.get("endDate"));
        return result;
    }

    private static String key(Map<String, Object> query) {
        return text(query.get("gid")) + "|" + text(query.get("fullShortUrl")).replaceFirst("(?i)^https?://", "") + "|"
                + text(query.get("startDate")) + "|" + text(query.get("endDate"));
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static BigDecimal optionalNumber(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try { return new BigDecimal(value.toString()); } catch (NumberFormatException invalid) { return null; }
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null : numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private static boolean complete(Map<String, Object> meta) {
        return "AVAILABLE".equals(meta.get("availability")) && "COMPLETE".equals(meta.get("completeness"))
                && "FRESH".equals(meta.get("freshness")) && !Boolean.TRUE.equals(meta.get("provisional"));
    }

    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
