package com.jupiter.shortlink.agent.tool.shortlink;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ranking projection over complete whole-window link metrics. */
final class CampaignStatisticsRankingCalculator {
    private static final List<String> METRICS = List.of("pv", "uv", "uip");

    private CampaignStatisticsRankingCalculator() {}

    record Result(List<Map<String, Object>> rows, BigDecimal totalPv, String error) {
        Result {
            rows = List.copyOf(rows);
            if (error != null && error.isBlank()) throw new IllegalArgumentException("Ranking error is empty");
        }
        boolean complete() { return error == null; }
    }

    static Result calculate(List<Map<String, Object>> items, Map<String, Object> summary,
            Map<String, Object> query, String metric, int limit) {
        if (!validMetrics(summary)) return failure("整段期间的分组汇总缺失，无法确认完整排名");
        Set<String> identities = new LinkedHashSet<>();
        BigDecimal totalPv = BigDecimal.ZERO;
        for (var item : items) {
            if (!validMetrics(item) || text(item.get("linkId")).isEmpty()
                    || !identities.add(text(item.get("linkId"))))
                return failure("短链统计缺失、无效或重复，未输出不完整排名");
            totalPv = totalPv.add(number(item.get("pv")));
        }
        if (totalPv.compareTo(number(summary.get("pv"))) != 0)
            return failure("短链 PV 合计与冻结分组汇总不一致，未输出不完整排名");
        List<Map<String, Object>> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.<Map<String, Object>, BigDecimal>comparing(row -> number(row.get(metric))).reversed()
                .thenComparing(row -> text(row.get("linkId"))));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, sorted.size()); index++) {
            Map<String, Object> row = new LinkedHashMap<>(sorted.get(index));
            row.putAll(query);
            row.put("rank", index + 1);
            row.put("pvShare", ratio(number(row.get("pv")), totalPv));
            rows.add(row);
        }
        return new Result(rows, totalPv, null);
    }

    private static Result failure(String message) { return new Result(List.of(), BigDecimal.ZERO, message); }
    private static boolean validMetrics(Map<String, Object> data) {
        return METRICS.stream().allMatch(metric -> {
            BigDecimal value = optionalNumber(data.get(metric));
            return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
        });
    }
    private static BigDecimal number(Object value) { return new BigDecimal(value.toString()); }
    private static BigDecimal optionalNumber(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try { return number(value); } catch (NumberFormatException invalid) { return null; }
    }
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.divide(denominator, 8, java.math.RoundingMode.HALF_UP);
    }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
