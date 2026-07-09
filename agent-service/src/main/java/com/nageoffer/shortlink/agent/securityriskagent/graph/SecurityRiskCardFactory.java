package com.nageoffer.shortlink.agent.securityriskagent.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

class SecurityRiskCardFactory {

    private static final long MIN_PV_FOR_RISK = 50L;
    private static final double TOP_IP_SHARE_WARNING = 0.3D;
    private static final double PV_PER_UV_WARNING = 5.0D;
    private static final double PEAK_HOUR_SHARE_WARNING = 0.6D;
    private static final Pattern IPV4_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}(?!\\d)");
    private static final Pattern USER_IDENTIFIER_PATTERN = Pattern.compile("(?i)\\b(user|username|uid|visitor|account)\\s*([:=])\\s*[^\\s,;\\uFF0C\\uFF1B]+");
    private static final Pattern CN_USER_IDENTIFIER_PATTERN = Pattern.compile("(\u7528\u6237)\\s*([:=\uFF1A])\\s*[^\\s,;\uFF0C\uFF1B]+");

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
            addTopIpConcentration(cards, execution, stats);
            addHighRepeatVisits(cards, execution, stats);
            addHourBurst(cards, execution, stats);
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
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        return value;
    }

    String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = IPV4_PATTERN.matcher(text)
                .replaceAll(match -> match.group(1) + "." + match.group(2) + ".*.*");
        sanitized = USER_IDENTIFIER_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
        return CN_USER_IDENTIFIER_PATTERN.matcher(sanitized)
                .replaceAll(match -> match.group(1) + match.group(2) + "***");
    }

    private void addTopIpConcentration(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        long pv = longValue(stats.get("pv"));
        if (pv < MIN_PV_FOR_RISK) {
            return;
        }
        List<Object> topIpRows = listValue(stats.get("topIpStats"));
        if (topIpRows.isEmpty()) {
            return;
        }
        Map<String, Object> topIp = topIpRows.stream()
                .map(this::mapValue)
                .max(Comparator.comparingLong(each -> longValue(each.get("cnt"))))
                .orElse(Map.of());
        long topIpCount = longValue(topIp.get("cnt"));
        double topIpShare = ratio(topIpCount, pv);
        if (topIpShare < TOP_IP_SHARE_WARNING) {
            return;
        }
        cards.add(riskSignalCard(
                execution,
                "high",
                78,
                "traffic",
                "top_ip_concentration",
                "ip_concentration",
                metrics(
                        "pv", pv,
                        "topIpCount", topIpCount,
                        "topIpShare", round4(topIpShare)
                ),
                thresholds(
                        "topIpShareWarning", TOP_IP_SHARE_WARNING,
                        "minPv", MIN_PV_FOR_RISK
                ),
                evidence(
                        "maskedTopIp", maskIp(textValue(topIp.get("ip"))),
                        "topIpCount", topIpCount
                ),
                List.of(
                        "Review whether the top IP segment matches expected delivery traffic.",
                        "Check channel source and campaign placement for abnormal repeated access."
                )
        ));
    }

    private void addHighRepeatVisits(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        long pv = longValue(stats.get("pv"));
        long uv = longValue(stats.get("uv"));
        if (pv < MIN_PV_FOR_RISK || uv <= 0) {
            return;
        }
        double pvPerUv = ratio(pv, uv);
        if (pvPerUv < PV_PER_UV_WARNING) {
            return;
        }
        cards.add(riskSignalCard(
                execution,
                "medium",
                68,
                "traffic",
                "high_repeat_visits",
                "repeat_visit",
                metrics(
                        "pv", pv,
                        "uv", uv,
                        "pvPerUv", round4(pvPerUv)
                ),
                thresholds(
                        "pvPerUvWarning", PV_PER_UV_WARNING,
                        "minPv", MIN_PV_FOR_RISK
                ),
                evidence("message", "PV is high relative to UV"),
                List.of(
                        "Compare repeated visits with expected campaign mechanics.",
                        "Sample access records to confirm whether repeat visits share IP or device fingerprints."
                )
        ));
    }

    private void addHourBurst(List<Object> cards, Map<String, Object> execution, Map<String, Object> stats) {
        List<Object> hourRows = listValue(stats.get("hourStats"));
        if (hourRows.isEmpty()) {
            return;
        }
        List<Long> hourValues = hourRows.stream()
                .map(this::longValue)
                .toList();
        long total = hourValues.stream().mapToLong(Long::longValue).sum();
        if (total < MIN_PV_FOR_RISK) {
            return;
        }
        long peakValue = hourValues.stream().mapToLong(Long::longValue).max().orElse(0L);
        double peakShare = ratio(peakValue, total);
        if (peakShare < PEAK_HOUR_SHARE_WARNING) {
            return;
        }
        int peakHour = hourValues.indexOf(peakValue);
        cards.add(riskSignalCard(
                execution,
                "medium",
                64,
                "time",
                "hour_burst",
                "peak_hour",
                metrics(
                        "totalPv", total,
                        "peakHour", (long) peakHour,
                        "peakHourPv", peakValue,
                        "peakHourShare", round4(peakShare)
                ),
                thresholds("peakHourShareWarning", PEAK_HOUR_SHARE_WARNING),
                evidence("peakHour", peakHour),
                List.of(
                        "Check whether the peak hour matches a planned campaign launch.",
                        "If no campaign event explains the burst, sample access records around the peak hour."
                )
        ));
    }

    private Map<String, Object> riskSignalCard(
            Map<String, Object> execution,
            String riskLevel,
            int riskScore,
            String category,
            String reasonCode,
            String signal,
            Map<String, Object> metrics,
            Map<String, Object> thresholds,
            Map<String, Object> evidence,
            List<String> recommendedActions
    ) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "risk_signal");
        card.put("title", "Security risk signal");
        card.put("sourceTool", textValue(execution.get("name")));
        card.put("arguments", mapValue(execution.get("arguments")));
        card.put("severity", "warning");
        card.put("riskScore", riskScore);
        card.put("riskLevel", riskLevel);
        card.put("summary", summary(category, reasonCode, signal));
        card.put("metrics", metrics);
        card.put("thresholds", thresholds);
        card.put("evidence", evidence);
        card.put("recommendedActions", recommendedActions);
        return card;
    }

    private Map<String, Object> summary(String category, String reasonCode, String signal) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", category);
        summary.put("reasonCode", reasonCode);
        summary.put("signal", signal);
        return summary;
    }

    private boolean isStatsTool(Map<String, Object> execution) {
        String toolName = textValue(execution.get("name"));
        return "get_group_stats".equals(toolName) || "get_short_link_stats".equals(toolName);
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
