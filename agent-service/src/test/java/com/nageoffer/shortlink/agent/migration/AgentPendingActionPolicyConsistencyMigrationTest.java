package com.nageoffer.shortlink.agent.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPendingActionPolicyConsistencyMigrationTest {

    private static final String MIGRATION_PATH =
            "sql/migration/V20260711__agent_pending_action_and_policy_consistency.sql";
    private static final List<String> NEW_TABLES = List.of(
            "t_agent_pending_action",
            "t_agent_risk_policy_effective",
            "t_agent_risk_policy_sync_outbox"
    );

    @Test
    void baselineAndMigrationKeepTheThreeNewTableDefinitionsAligned() throws IOException {
        String schema = read("sql/agent_service_schema.sql");
        String migration = read(MIGRATION_PATH);

        for (String table : NEW_TABLES) {
            assertThat(normalize(tableBlock(schema, table)))
                    .as("baseline and V20260711 must define the same %s table", table)
                    .isEqualTo(normalize(tableBlock(migration, table)));
        }
    }

    @Test
    void newTablesContainTheRequiredUniquenessAndQueueIndexes() throws IOException {
        String schema = read("sql/agent_service_schema.sql");
        String pendingAction = tableBlock(schema, "t_agent_pending_action");
        String effectivePolicy = tableBlock(schema, "t_agent_risk_policy_effective");
        String syncOutbox = tableBlock(schema, "t_agent_risk_policy_sync_outbox");

        assertThat(pendingAction)
                .contains("CONSTRAINT uk_agent_pending_action_action_id UNIQUE (action_id)")
                .contains("rejection_review_action VARCHAR(32) NULL")
                .contains("CONSTRAINT uk_agent_pending_action_type_idempotency UNIQUE (action_type, idempotency_key)")
                .contains("CONSTRAINT uk_agent_pending_action_active_slot UNIQUE (active_slot_key)")
                .contains("KEY idx_agent_pending_action_gid_status (gid, status, update_time)")
                .contains("KEY idx_agent_pending_action_execution_lease (status, execution_lease_until)")
                .contains("KEY idx_agent_pending_action_event_id (event_id)")
                .contains("KEY idx_agent_pending_action_batch_gid (batch_id, gid)");
        assertThat(effectivePolicy)
                .contains("UNIQUE (policy_key)");
        assertThat(syncOutbox)
                .contains("UNIQUE (outbox_id)")
                .contains("UNIQUE (policy_key, policy_version, operation)")
                .contains("KEY idx_agent_risk_policy_sync_outbox_claim (status, next_retry_time, lease_until)")
                .contains("KEY idx_agent_risk_policy_sync_outbox_key_version (policy_key, policy_version)");
        assertThat(schema)
                .contains("idempotency_key VARCHAR(512) NULL")
                .contains("policy_version BIGINT NULL")
                .contains("KEY idx_agent_risk_policy_key_version (policy_key, policy_version)")
                .doesNotContain("uk_agent_risk_policy_idempotency");
    }

    @Test
    void migrationDocumentsManualPreflightAndPartialDdlRecovery() throws IOException {
        String migration = read(MIGRATION_PATH);

        assertThat(migration)
                .contains("MANUAL RUNBOOK")
                .contains("SELECT version FROM t_agent_schema_migration_history")
                .contains("SHOW CREATE TABLE t_agent_pending_action")
                .contains("SHOW CREATE TABLE t_agent_risk_policy_effective")
                .contains("SHOW CREATE TABLE t_agent_risk_policy_sync_outbox")
                .contains("information_schema.COLUMNS")
                .contains("information_schema.STATISTICS")
                .contains("DDL statements do not form one transaction")
                .contains("DO NOT rerun the full script")
                .contains("execute only the final migration-history INSERT")
                .contains("If V20260711 history exists, STOP");
    }

    @Test
    void migrationAddsOnlyTheCompatibleV20260711StructuresInOrder() throws IOException {
        String migration = read(MIGRATION_PATH);

        assertThat(migration)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_pending_action")
                .contains("CREATE TABLE IF NOT EXISTS t_agent_risk_policy_effective")
                .contains("CREATE TABLE IF NOT EXISTS t_agent_risk_policy_sync_outbox")
                .contains("ADD COLUMN idempotency_key VARCHAR(512) NULL")
                .contains("ADD COLUMN policy_version BIGINT NULL")
                .contains("V20260711")
                .doesNotContain("ADD COLUMN idempotency_key VARCHAR(512) NOT NULL")
                .doesNotContain("ADD COLUMN policy_version BIGINT NOT NULL")
                .doesNotContain("MODIFY COLUMN idempotency_key VARCHAR(512) NOT NULL")
                .doesNotContain("MODIFY COLUMN policy_version BIGINT NOT NULL")
                .doesNotContain("uk_agent_risk_policy_idempotency")
                .doesNotContain("ROW_NUMBER")
                .doesNotContain("UPDATE t_agent_risk_policy")
                .doesNotContain("INSERT INTO t_agent_risk_policy_effective")
                .doesNotContain("INSERT INTO t_agent_risk_policy_sync_outbox")
                .doesNotContain("DELETE FROM t_agent_risk_policy")
                .doesNotContain("SET idempotency_key")
                .doesNotContain("ON DUPLICATE KEY UPDATE");

        assertThat(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_schema_migration_history"))
                .isLessThan(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_pending_action"));
        assertThat(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_pending_action"))
                .isLessThan(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_risk_policy_effective"));
        assertThat(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_risk_policy_effective"))
                .isLessThan(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_risk_policy_sync_outbox"));
        assertThat(position(migration, "CREATE TABLE IF NOT EXISTS t_agent_risk_policy_sync_outbox"))
                .isLessThan(position(migration, "ALTER TABLE t_agent_risk_policy"));
        assertThat(position(migration, "ALTER TABLE t_agent_risk_policy"))
                .isLessThan(position(migration, "INSERT INTO t_agent_schema_migration_history"));
    }

    private String read(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        assertThat(resource.exists())
                .as("SQL resource must be packaged with agent-service: %s", path)
                .isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private String tableBlock(String sql, String tableName) {
        Pattern pattern = Pattern.compile(
                "(?ms)^[ \\t]*CREATE TABLE IF NOT EXISTS[ \\t]+"
                        + Pattern.quote(tableName)
                        + "[ \\t]*\\(.*?^[ \\t]*\\);"
        );
        Matcher matcher = pattern.matcher(sql.replace("\r\n", "\n"));
        assertThat(matcher.find())
                .as("CREATE TABLE block must exist: %s", tableName)
                .isTrue();
        return matcher.group();
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private int position(String sql, String token) {
        int position = sql.indexOf(token);
        assertThat(position)
                .as("migration token must exist: %s", token)
                .isGreaterThanOrEqualTo(0);
        return position;
    }
}
