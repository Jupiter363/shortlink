package com.jupiter.shortlink.analytics.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expired materialized pages are no longer readable; immutable build data is not deleted here. */
@Component
public final class SnapshotMaintenance {
    private final JdbcTemplate db;

    public SnapshotMaintenance(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        try {
            db.update(
                    "DELETE FROM analytics_snapshot WHERE expires_at<CURRENT_TIMESTAMP(3) ORDER BY"
                            + " expires_at LIMIT 200");
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Snapshot cleanup unavailable: {}", e.getClass().getSimpleName());
        }
    }
}
