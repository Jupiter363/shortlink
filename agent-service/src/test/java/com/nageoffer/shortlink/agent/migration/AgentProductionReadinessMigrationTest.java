package com.nageoffer.shortlink.agent.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProductionReadinessMigrationTest {

    private static final String RECOVERY_INDEX =
            "idx_agent_risk_profile_batch_recovery (update_time, window_end, id, status, lease_until)";

    @Test
    void migrationBackfillsLegacyBatchesBeforeEnforcingBatchUniqueness() throws IOException {
        ClassPathResource migration = new ClassPathResource(
                "sql/migration/V20260710__agent_production_readiness.sql"
        );

        assertThat(migration.exists())
                .as("production readiness migration must be packaged with agent-service")
                .isTrue();
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("PAUSE risk-profile scheduler and risk-analysis workers")
                .contains("CREATE TABLE IF NOT EXISTS t_agent_risk_profile_batch")
                .contains("CREATE TABLE IF NOT EXISTS t_agent_risk_analysis_job")
                .contains("ALTER TABLE t_agent_short_link_risk_profile ADD COLUMN batch_id VARCHAR(128) NULL")
                .contains("ALTER TABLE t_agent_group_risk_profile ADD COLUMN batch_id VARCHAR(128) NULL")
                .contains("CONCAT('legacy-risk-profile:', DATE_FORMAT(profile_window_end, '%Y%m%d%H%i%s'))")
                .contains("HAVING COUNT(*) > 1")
                .contains("DELETE stale")
                .contains("INSERT IGNORE INTO t_agent_risk_profile_batch")
                .contains("MODIFY COLUMN batch_id VARCHAR(128) NOT NULL")
                .contains("uk_agent_short_link_profile_batch_target")
                .contains("uk_agent_group_profile_batch_gid")
                .doesNotContain("SET batch_id = ''");

        assertThat(position(sql, "ADD COLUMN batch_id VARCHAR(128) NULL"))
                .isLessThan(position(sql, "CONCAT('legacy-risk-profile:'"));
        assertThat(position(sql, "CONCAT('legacy-risk-profile:'"))
                .isLessThan(position(sql, "DELETE stale"));
        assertThat(position(sql, "DELETE stale"))
                .isLessThan(position(sql, "MODIFY COLUMN batch_id VARCHAR(128) NOT NULL"));
        assertThat(position(sql, "MODIFY COLUMN batch_id VARCHAR(128) NOT NULL"))
                .isLessThan(position(sql, "uk_agent_short_link_profile_batch_target"));
    }

    @Test
    void schemaAndMigrationAddTheRecoveryQueueIndexWithoutDuplicatingItInCreateTable() throws IOException {
        String schema = new ClassPathResource("sql/agent_service_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String migration = new ClassPathResource(
                "sql/migration/V20260710__agent_production_readiness.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(schema)
                .contains("KEY " + RECOVERY_INDEX);
        assertThat(migration)
                .contains("""
                        ALTER TABLE t_agent_risk_profile_batch
                            ADD INDEX %s;
                        """.formatted(RECOVERY_INDEX));
        assertThat(occurrences(migration, RECOVERY_INDEX)).isEqualTo(1);
        assertThat(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_risk_profile_batch"))
                .isLessThan(position(migration, "ADD INDEX " + RECOVERY_INDEX));
        assertThat(position(migration, "ADD INDEX " + RECOVERY_INDEX))
                .isLessThan(position(migration, "INSERT INTO t_agent_schema_migration_history"));
    }

    private int position(String sql, String token) {
        int position = sql.indexOf(token);
        assertThat(position)
                .as("migration token must exist: %s", token)
                .isGreaterThanOrEqualTo(0);
        return position;
    }

    private int occurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
