package com.nageoffer.shortlink.agent.agent.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class CampaignInsightCardFactory {

    private static final long MIN_PV_FOR_TRAFFIC_ANOMALY = 50L;
    private static final double PV_PER_UV_WARNING = 5.0D;
    private static final double UIP_SHARE_WARNING = 0.2D;
    private static final double TOP_IP_SHARE_WARNING = 0.3D;
    private static final double TOP3_IP_SHARE_WARNING = 0.5D;
    private static final double DAILY_SPIKE_RATIO = 2.0D;
    private static final double DAILY_DROP_RATIO = 0.5D;
    private static final long DAILY_DELTA_WARNING = 20L;
    private static final double PEAK_HOUR_SHARE_WARNING = 0.4D;
    private static final double TOP3_HOUR_SHARE_WARNING = 0.7D;
    private static final double PROFILE_SHARE_WARNING = 0.7D;
    private static final long PROFILE_COUNT_WARNING = 20L;

    List<Object> build(List<Map<String, Object>> toolExecutions) {
        List<Object> cards = new ArrayList<>();
        for (Map<String, Object> execution : toolExecutions) {
            if (!Boolean.TRUE.equals(execution.get("success")) || !isStatsTool(execution)) {
                continue;
            }
            Map<String, Object> stats = mapValue(execution.get("data"));
            if (stats.isEmpty()) {
                continue;
            }
            addTrafficAnomalyCards(cards, execution, stats);
            addPerformanceInsightCards(cards, execution, stats);
        }
        return cards;
    }

    Object sanitizeForPrompt(Object value) {
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(sanitizeForPrompt(item));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String textKey = String.valueOf(key);
                if ("user".equalsIgnoreCase(textKey)) {
                    return;
                }
                if ("ip".equalsIgnoreCase(textKey)) {
                    result.put(textKey, maskIp(textValue(item)));
                    return;
                }
                result.put(textKey, sanitizeForPrompt(item));
            });
            return result;
        }
        return value;
    }

    private void addTrafficAnomalyCards(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        long pv = longValue(stats.get("pv"));
        long uv = longValue(stats.get("uv"));
        long uip = longValue(stats.get("uip"));
        if (pv >= MIN_PV_FOR_TRAFFIC_ANOMALY && uv > 0) {
            double pvPerUv = ratio(pv, uv);
            if (pvPerUv >= PV_PER_UV_WARNING) {
                cards.add(derivedCard(
                        "traffic_anomaly",
                        "Traffic anomaly",
                        execution,
                        "warning",
                        summary("traffic", "high_repeat_visits", "repeat_visit"),
                        metrics(
                                "pv", pv,
                                "uv", uv,
                                "pvPerUv", round4(pvPerUv)
                        ),
                        thresholds("pvPerUvWarning", PV_PER_UV_WARNING, "minPv", MIN_PV_FOR_TRAFFIC_ANOMALY),
                        evidence("message", "PV is high relative to UV")
                ));
            }
        }
        if (pv >= MIN_PV_FOR_TRAFFIC_ANOMALY && pv > 0) {
            double uipShare = ratio(uip, pv);
            if (uipShare <= UIP_SHARE_WARNING) {
                cards.add(derivedCard(
                        "traffic_anomaly",
                        "Traffic anomaly",
                        execution,
                        "warning",
                        summary("traffic", "low_uip_share", "network_exit_concentration"),
                        metrics(
                                "pv", pv,
                                "uip", uip,
                                "uipShare", round4(uipShare)
                        ),
                        thresholds("uipShareWarning", UIP_SHARE_WARNING, "minPv", MIN_PV_FOR_TRAFFIC_ANOMALY),
                        evidence("message", "UIP is low relative to PV")
                ));
            }
        }
        addTopIpAnomalyCard(cards, execution, stats, pv);
    }

    private void addTopIpAnomalyCard(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats, long pv) {
        List<Object> topIpRows = listValue(stats.get("topIpStats"));
        if (pv <= 0 || topIpRows.isEmpty()) {
            return;
        }
        Map<String, Object> topIp = mapValue(topIpRows.get(0));
        long topIpCount = longValue(topIp.get("cnt"));
        long top3IpCount = topIpRows.stream()
                .limit(3)
                .map(this::mapValue)
                .mapToLong(each -> longValue(each.get("cnt")))
                .sum();
        double topIpShare = ratio(topIpCount, pv);
        double top3IpShare = ratio(top3IpCount, pv);
        if (topIpShare < TOP_IP_SHARE_WARNING && top3IpShare < TOP3_IP_SHARE_WARNING) {
            return;
        }
        cards.add(derivedCard(
                "traffic_anomaly",
                "Traffic anomaly",
                execution,
                "warning",
                summary("traffic", "top_ip_concentration", "ip_concentration"),
                metrics(
                        "pv", pv,
                        "topIpCount", topIpCount,
                        "topIpShare", round4(topIpShare),
                        "top3IpCount", top3IpCount,
                        "top3IpShare", round4(top3IpShare)
                ),
                thresholds(
                        "topIpShareWarning", TOP_IP_SHARE_WARNING,
                        "top3IpShareWarning", TOP3_IP_SHARE_WARNING
                ),
                evidence(
                        "maskedTopIp", maskIp(textValue(topIp.get("ip"))),
                        "topIpCount", topIpCount
                )
        ));
    }

    private void addPerformanceInsightCards(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        addDailyTrendInsightCard(cards, execution, stats);
        addHourConcentrationInsightCard(cards, execution, stats);
        addProfileConcentrationInsightCard(cards, execution, stats, "browserStats", "browser", "browser");
        addProfileConcentrationInsightCard(cards, execution, stats, "osStats", "os", "os");
        addProfileConcentrationInsightCard(cards, execution, stats, "deviceStats", "device", "device");
        addProfileConcentrationInsightCard(cards, execution, stats, "networkStats", "network", "network");
        addProfileConcentrationInsightCard(cards, execution, stats, "localeCnStats", "locale", "locale");
    }

    private void addDailyTrendInsightCard(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        List<Object> dailyRows = sortedDailyRows(listValue(stats.get("daily")));
        if (dailyRows.size() < 2) {
            return;
        }
        Map<String, Object> latestRow = mapValue(dailyRows.get(dailyRows.size() - 1));
        long latestPv = longValue(latestRow.get("pv"));
        int baselineStart = Math.max(0, dailyRows.size() - 4);
        List<Object> baselineRows = dailyRows.subList(baselineStart, dailyRows.size() - 1);
        double baselineAverage = baselineRows.stream()
                .map(this::mapValue)
                .mapToLong(each -> longValue(each.get("pv")))
                .average()
                .orElse(0D);
        if (baselineAverage <= 0) {
            return;
        }
        double changeRatio = ratio(latestPv, baselineAverage);
        long deltaPv = Math.round(Math.abs(latestPv - baselineAverage));
        if (changeRatio >= DAILY_SPIKE_RATIO && deltaPv >= DAILY_DELTA_WARNING) {
            cards.add(dailyTrendCard(execution, latestRow, "daily_pv_spike", latestPv, baselineAverage, changeRatio, deltaPv));
        } else if (changeRatio <= DAILY_DROP_RATIO && deltaPv >= DAILY_DELTA_WARNING) {
            cards.add(dailyTrendCard(execution, latestRow, "daily_pv_drop", latestPv, baselineAverage, changeRatio, deltaPv));
        }
    }

    private List<Object> sortedDailyRows(List<Object> dailyRows) {
        List<Object> sortedRows = new ArrayList<>(dailyRows);
        sortedRows.sort(Comparator.comparing(row -> textValue(mapValue(row).get("date"))));
        return sortedRows;
    }

    private Map<String, Object> dailyTrendCard(
            Map<String, Object> execution,
            Map<String, Object> latestRow,
            String reasonCode,
            long latestPv,
            double baselineAverage,
            double changeRatio,
            long deltaPv
    ) {
        return derivedCard(
                "performance_insight",
                "Performance insight",
                execution,
                "info",
                summary("trend", reasonCode, "daily_pv"),
                metrics(
                        "latestPv", latestPv,
                        "baselinePvAverage", round4(baselineAverage),
                        "changeRatio", round4(changeRatio),
                        "deltaPv", deltaPv
                ),
                thresholds(
                        "dailySpikeRatio", DAILY_SPIKE_RATIO,
                        "dailyDropRatio", DAILY_DROP_RATIO,
                        "deltaPvWarning", DAILY_DELTA_WARNING
                ),
                evidence("date", latestRow.get("date"))
        );
    }

    private void addHourConcentrationInsightCard(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        List<Object> hourRows = listValue(stats.get("hourStats"));
        if (hourRows.isEmpty()) {
            return;
        }
        List<Long> hourValues = hourRows.stream()
                .map(this::longValue)
                .toList();
        long total = hourValues.stream().mapToLong(Long::longValue).sum();
        if (total <= 0) {
            return;
        }
        long peakValue = hourValues.stream().mapToLong(Long::longValue).max().orElse(0L);
        int peakHour = hourValues.indexOf(peakValue);
        long top3Value = hourValues.stream()
                .sorted(Comparator.reverseOrder())
                .limit(3)
                .mapToLong(Long::longValue)
                .sum();
        double peakShare = ratio(peakValue, total);
        double top3Share = ratio(top3Value, total);
        if (peakShare < PEAK_HOUR_SHARE_WARNING && top3Share < TOP3_HOUR_SHARE_WARNING) {
            return;
        }
        cards.add(derivedCard(
                "performance_insight",
                "Performance insight",
                execution,
                "info",
                summary("time", "hour_concentration", "hour"),
                metrics(
                        "totalPv", total,
                        "peakHour", (long) peakHour,
                        "peakHourPv", peakValue,
                        "peakHourShare", round4(peakShare),
                        "top3HourPv", top3Value,
                        "top3HourShare", round4(top3Share)
                ),
                thresholds(
                        "peakHourShareWarning", PEAK_HOUR_SHARE_WARNING,
                        "top3HourShareWarning", TOP3_HOUR_SHARE_WARNING
                ),
                evidence("peakHour", peakHour)
        ));
    }

    private void addProfileConcentrationInsightCard(
            List<Object> cards,
            Map<String, Object> execution,
            Map<String, Object> stats,
            String statsKey,
            String labelKey,
            String dimension
    ) {
        List<Object> rows = listValue(stats.get(statsKey));
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> topRow = rows.stream()
                .map(this::mapValue)
                .max(Comparator.comparingDouble(each -> doubleValue(each.get("ratio"))))
                .orElse(Map.of());
        long count = longValue(topRow.get("cnt"));
        double ratio = doubleValue(topRow.get("ratio"));
        if (ratio < PROFILE_SHARE_WARNING || count < PROFILE_COUNT_WARNING) {
            return;
        }
        Map<String, Object> summary = summary("profile", "profile_concentration", dimension);
        summary.put("dimension", dimension);
        cards.add(derivedCard(
                "performance_insight",
                "Performance insight",
                execution,
                "info",
                summary,
                metrics(
                        "cnt", count,
                        "ratio", round4(ratio)
                ),
                thresholds(
                        "profileShareWarning", PROFILE_SHARE_WARNING,
                        "profileCountWarning", PROFILE_COUNT_WARNING
                ),
                evidence(
                        "dimension", dimension,
                        "label", textValue(topRow.get(labelKey)),
                        "cnt", count,
                        "ratio", round4(ratio)
                )
        ));
    }

    private boolean isStatsTool(Map<String, Object> execution) {
        String toolName = textValue(execution.get("name"));
        return "get_group_stats".equals(toolName) || "get_short_link_stats".equals(toolName);
    }

    private Map<String, Object> derivedCard(
            String type,
            String title,
            Map<String, Object> execution,
            String severity,
            Map<String, Object> summary,
            Map<String, Object> metrics,
            Map<String, Object> thresholds,
            Map<String, Object> evidence
    ) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", type);
        card.put("title", title);
        card.put("sourceTool", textValue(execution.get("name")));
        card.put("arguments", mapValue(execution.get("arguments")));
        card.put("severity", severity);
        card.put("summary", summary);
        card.put("metrics", metrics);
        card.put("thresholds", thresholds);
        card.put("evidence", evidence);
        return card;
    }

    private Map<String, Object> summary(String category, String reasonCode, String signal) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", category);
        summary.put("reasonCode", reasonCode);
        summary.put("signal", signal);
        return summary;
    }

    private Map<String, Object> metrics(Object... keyValues) {
        return linkedMap(keyValues);
    }

    private Map<String, Object> thresholds(Object... keyValues) {
        return linkedMap(keyValues);
    }

    private Map<String, Object> evidence(Object... keyValues) {
        return linkedMap(keyValues);
    }

    private Map<String, Object> linkedMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return result;
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Object> listValue(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0D;
        }
    }

    private double ratio(double numerator, double denominator) {
        return denominator == 0D ? 0D : numerator / denominator;
    }

    private double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private String maskIp(String ip) {
        if (ip.isBlank()) {
            return "";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return ip.length() <= 6 ? "***" : ip.substring(0, 6) + "***";
    }

    private String textValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
