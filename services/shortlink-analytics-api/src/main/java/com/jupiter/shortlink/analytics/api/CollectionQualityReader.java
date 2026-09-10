package com.jupiter.shortlink.analytics.api;

import com.jupiter.shortlink.contract.EventEnricher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CollectionQualityReader {
    private final JdbcTemplate db;
    private final List<String> endpoints;

    public CollectionQualityReader(
            JdbcTemplate d, @Value("${analytics.producer-quality.urls:}") String urls) {
        db = d;
        endpoints =
                Arrays.stream(urls.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    public Map<String, Object> read(long start, long end, long now) {
        if (endpoints.isEmpty())
            return Map.of(
                    "status", "UNKNOWN", "reasons", List.of("PRODUCER_ROSTER_NOT_CONFIGURED"));
        Set<String> unknown = new LinkedHashSet<>(), degraded = new LinkedHashSet<>();
        for (String endpoint : endpoints)
            for (String lane : List.of("click", "result")) {
                var samples =
                        db.queryForList(
                                "SELECT * FROM analytics_source_quality WHERE endpoint_id=? AND"
                                        + " lane=? ORDER BY observed_at DESC LIMIT 1",
                                EventEnricher.sha256(endpoint),
                                lane);
                if (samples.isEmpty()) {
                    unknown.add("PRODUCER_SAMPLE_MISSING");
                    continue;
                }
                var s = samples.get(0);
                long observed = n(s, "observed_at");
                if (observed < end || now - observed > 30000) {
                    unknown.add("PRODUCER_SAMPLE_STALE");
                    continue;
                }
                if (n(s, "started_at") > start) {
                    unknown.add("PRODUCER_HISTORY_NOT_COVERED");
                    continue;
                }
                if (n(s, "pending") > 0) unknown.add("PRODUCER_DELIVERY_PENDING");
                var baseline =
                        db.queryForList(
                                "SELECT failed,rejected FROM analytics_source_quality WHERE"
                                        + " endpoint_id=? AND producer_instance_id=? AND lane=? AND"
                                        + " observed_at<=? ORDER BY observed_at DESC LIMIT 1",
                                EventEnricher.sha256(endpoint),
                                s.get("producer_instance_id"),
                                lane,
                                start);
                long oldFailed = baseline.isEmpty() ? 0 : n(baseline.get(0), "failed"),
                        oldRejected = baseline.isEmpty() ? 0 : n(baseline.get(0), "rejected");
                if (n(s, "failed") < oldFailed || n(s, "rejected") < oldRejected)
                    unknown.add("PRODUCER_COUNTER_RESET");
                if (n(s, "failed") > oldFailed || n(s, "rejected") > oldRejected)
                    degraded.add("PRODUCER_CAPTURE_LOSS");
            }
        if (!degraded.isEmpty()) {
            degraded.addAll(unknown);
            return Map.of("status", "DEGRADED", "reasons", List.copyOf(degraded));
        }
        return Map.of(
                "status",
                unknown.isEmpty() ? "NORMAL" : "UNKNOWN",
                "reasons",
                List.copyOf(unknown));
    }

    private long n(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }
}
