package com.jupiter.shortlink.analytics.api.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/** Frozen selections are persisted once and never reconstructed from latest manifests on retry. */
public record ManifestPlan(String epoch, List<Window> windows, List<String> replicas) {
    static final long WINDOW = 300_000L, MAX_RANGE = 180L * 86_400_000;

    public record Window(
            long start,
            String build,
            long revision,
            String sourceCut,
            long observed,
            String metricVersion,
            String datasetVersion,
            String proof) {}

    static ManifestPlan capture(
            JdbcTemplate db,
            ObjectMapper json,
            ApiSettings settings,
            String epoch,
            long start,
            long end) {
        long aligned = Math.floorDiv(start, WINDOW) * WINDOW;
        int expected = Math.toIntExact((end - aligned + WINDOW - 1) / WINDOW);
        var rows =
                db.queryForList(
                        "SELECT"
                            + " window_start,build_id,manifest_revision,source_cut,source_observed_at,metric_version,dataset_version,finalized,replica_ids,coverage_proof"
                            + " FROM analytics_manifest WHERE recovery_epoch=? AND window_start>=?"
                            + " AND window_start<? ORDER BY window_start LIMIT ?",
                        epoch,
                        aligned,
                        end,
                        expected + 1);
        if (rows.size() != expected)
            throw new QueryFailure(
                    "NOT_READY", "Every requested canonical window must be published");
        Set<String> eligible = new LinkedHashSet<>(List.of(settings.clickHouseUrls().split(",")));
        List<Window> result = new ArrayList<>();
        for (var r : rows) {
            long w = ((Number) r.get("window_start")).longValue();
            Object finalized = r.get("finalized");
            if (w != aligned + result.size() * WINDOW
                    || !(Boolean.TRUE.equals(finalized) || "1".equals(finalized.toString())))
                throw new QueryFailure(
                        "NOT_READY", "Canonical windows must be contiguous and finalized");
            long observed = ((Number) r.get("source_observed_at")).longValue();
            if (observed < Math.min(end, w + WINDOW))
                throw new QueryFailure(
                        "NOT_READY", "A selected source cut does not cover its requested window");
            try {
                eligible.retainAll(
                        json.readValue(
                                r.get("replica_ids").toString(),
                                new TypeReference<List<String>>() {}));
            } catch (Exception e) {
                throw new QueryFailure("NOT_READY", "Invalid replica proof");
            }
            result.add(
                    new Window(
                            w,
                            r.get("build_id").toString(),
                            ((Number) r.get("manifest_revision")).longValue(),
                            r.get("source_cut").toString(),
                            observed,
                            r.get("metric_version").toString(),
                            r.get("dataset_version").toString(),
                            r.get("coverage_proof").toString()));
        }
        if (eligible.isEmpty())
            throw new QueryFailure(
                    "NOT_READY", "No common qualified replica for the requested snapshot");
        return new ManifestPlan(epoch, List.copyOf(result), List.copyOf(eligible));
    }

    String predicate() {
        String predicate = CanonicalBuildPredicate.predicate(windows.stream()
                .map(window -> new CanonicalBuildPredicate.Selection(window.build(), window.start())).toList());
        if (predicate.length() > 4 * 1024 * 1024)
            throw new QueryFailure("TOO_LARGE", "Frozen build selection exceeds the query budget");
        return predicate;
    }
}
