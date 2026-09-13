package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only display policy: partial observations stay visible without becoming complete evidence.
 */
final class CampaignStatsPresentation {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private CampaignStatsPresentation() {}

    static Map<String, Object> observedMetrics(Map<String, Object> envelope) {
        Map<String, Object> meta = map(envelope.get("meta"));
        if (!"AVAILABLE".equals(meta.get("availability"))
                || !textPresent(meta.get("snapshotId"))
                || !textPresent(meta.get("recoveryEpoch"))
                || !("COMPLETE".equals(meta.get("completeness"))
                        || "PARTIAL".equals(meta.get("completeness")))) return Map.of();
        return map(map(envelope.get("metrics")).get("requested"));
    }

    static String qualityMessage(Map<String, Object> envelope) {
        Map<String, Object> meta = map(envelope.get("meta"));
        List<String> parts = new ArrayList<>();
        if (observedMetrics(envelope).isEmpty()) {
            parts.add("尚无带有效快照的可用统计指标，不能据此推断为零流量");
        } else if ("PARTIAL".equals(meta.get("completeness"))) {
            parts.add("当前仅为部分采集结果，指标为已观测值，不代表完整流量");
        }
        if (Boolean.TRUE.equals(meta.get("provisional"))) {
            parts.add("临时快照，统计值可能随采集数据修订");
        }
        if ("STALE".equals(meta.get("freshness"))) {
            parts.add("数据已陈旧，不能作为当前实时状态");
        }
        String start = boundary(meta.get("requestedStart"));
        String end = boundary(meta.get("requestedEnd"));
        if (!start.isBlank() && !end.isBlank()) {
            parts.add("请求范围（Asia/Shanghai）：" + start + " 至 " + end + "，结束边界不含");
        }
        String effectiveEnd = boundary(meta.get("effectiveEnd"));
        if (!effectiveEnd.isBlank() && !effectiveEnd.equals(end)) {
            parts.add("有效查询结束边界：" + effectiveEnd);
        }
        String captured = boundary(meta.get("snapshotCreatedAt"));
        if (!captured.isBlank()) parts.add("快照生成时间：" + captured);
        Map<String, Object> approximation = map(meta.get("approximation"));
        List<String> estimates = new ArrayList<>();
        for (String metric : List.of("uv", "uip", "topIpShare", "topVisitorShare")) {
            if ("APPROXIMATE".equals(map(approximation.get(metric)).get("type"))) {
                estimates.add(label(metric));
            }
        }
        if (!estimates.isEmpty()) parts.add("估算指标：" + String.join("、", estimates));
        if (meta.get("missingMetrics") instanceof List<?> missing && !missing.isEmpty()) {
            parts.add(
                    "缺少统计维度："
                            + missing.stream()
                                    .map(String::valueOf)
                                    .map(CampaignStatsPresentation::label)
                                    .distinct()
                                    .collect(Collectors.joining("、")));
        }
        return String.join("；", parts) + (parts.isEmpty() ? "" : "。");
    }

    private static String label(String value) {
        return switch (value) {
            case "uv" -> "UV";
            case "uip" -> "UIP";
            case "topIpShare" -> "IP 集中度";
            case "topVisitorShare" -> "访客集中度";
            case "country" -> "国家/地区";
            case "localeCnStats", "topRegionShare" -> "地区构成";
            case "networkStats" -> "运营商/ISP";
            case "uvTypeStats" -> "新老访客";
            default -> value;
        };
    }

    private static boolean textPresent(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static String boundary(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0) return "";
        try {
            return DATE_TIME.format(Instant.ofEpochMilli(number.longValue()));
        } catch (java.time.DateTimeException invalid) {
            return "";
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
