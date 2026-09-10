package com.jupiter.shortlink.agent;

import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.riskcommon.model.*;
import com.jupiter.shortlink.agent.riskprofile.model.*;

import java.time.*;
import java.util.*;

public final class StatsTestFixtures {
    public static final String SECRET = "agent-integration-test-secret-32-bytes";
    public static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "zhangsan", 7, false);
    public static final long NOW = Instant.parse("2026-07-10T02:00:00Z").toEpochMilli();

    public static Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenantId", "1001");
        meta.put("snapshotId", "snapshot-1");
        meta.put("recoveryEpoch", "epoch-1");
        meta.put("effectiveEnd", NOW);
        meta.put("snapshotCreatedAt", NOW);
        meta.put("snapshotExpiresAt", NOW + 600_000);
        meta.put("manifestVersion", Map.of("window", Map.of("buildId", "build-1", "revision", 1)));
        meta.put("sourceCut", Map.of("click", Map.of("0", 123L)));
        meta.put("metricVersion", "click-v1");
        meta.put("detailDatasetVersion", "detail-1");
        meta.put("availability", "AVAILABLE");
        meta.put("completeness", "COMPLETE");
        meta.put("freshness", "FRESH");
        meta.put("provisional", false);
        meta.put("collectionQuality", Map.of("status", "NORMAL", "reasons", List.of()));
        meta.put(
                "approximation",
                Map.of(
                        "pv",
                        Map.of("type", "EXACT"),
                        "uv",
                        Map.of("type", "EXACT"),
                        "topIpShare",
                        Map.of("type", "EXACT"),
                        "peakHourShare",
                        Map.of("type", "EXACT")));
        return meta;
    }

    public static Map<String, Object> envelope(Map<String, Object> counts) {
        return Map.of("metrics", Map.of("requested", counts), "items", List.of(), "meta", meta());
    }

    public static Map<String, Object> execution(String name, Map<String, Object> counts) {
        return Map.of(
                "name",
                name,
                "arguments",
                Map.of("gid", "g1"),
                "success",
                true,
                "data",
                envelope(counts));
    }

    public static ShortLinkRiskProfile profile() {
        return new ShortLinkRiskProfile(
                "g1",
                "nurl.ink",
                "abc",
                "nurl.ink/abc",
                LocalDateTime.of(2026, 7, 10, 8, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                new ShortLinkRiskMetrics(
                        3_000_000_000L,
                        100,
                        4_000_000_000L,
                        200,
                        5_000_000_000L,
                        300,
                        4D,
                        .8,
                        .8,
                        null,
                        null,
                        null,
                        30D,
                        .7,
                        .8),
                90,
                90,
                RiskLevel.HIGH,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                RiskWatchStatus.NONE,
                List.of(),
                "",
                "batch-1",
                new StatsEvidence("1001", 99, meta(), StatsEvidence.CURRENT_RULE_VERSION));
    }
}
