package com.nageoffer.shortlink.agent.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptException;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPendingActionPolicyConsistencyMigrationTest {

    private static final String V11_MIGRATION_PATH =
            "sql/migration/V20260711__agent_pending_action_and_policy_consistency.sql";
    private static final String V12_MIGRATION_PATH =
            "sql/migration/V20260712__risk_policy_history_constraints_and_backfill.sql";
    private static final String V11_MYSQL_MULTI_ACTION_ALTER = """
            ALTER TABLE t_agent_risk_policy
                ADD COLUMN idempotency_key VARCHAR(512) NULL AFTER policy_key,
                ADD COLUMN policy_version BIGINT NULL AFTER idempotency_key,
                ADD INDEX idx_agent_risk_policy_key_version (policy_key, policy_version);
            """;
    private static final String V11_H2_EQUIVALENT_ALTER = """
            ALTER TABLE t_agent_risk_policy
                ADD COLUMN idempotency_key VARCHAR(512) NULL;
            ALTER TABLE t_agent_risk_policy
                ADD COLUMN policy_version BIGINT NULL;
            CREATE INDEX idx_agent_risk_policy_key_version
                ON t_agent_risk_policy (policy_key, policy_version);
            """;
    private static final List<String> NEW_TABLES = List.of(
            "t_agent_pending_action",
            "t_agent_risk_policy_effective",
            "t_agent_risk_policy_sync_outbox"
    );

    @Test
    void baselineAndV11KeepTheThreeNewTableDefinitionsAligned() throws IOException {
        String schema = read("sql/agent_service_schema.sql");
        String migration = read(V11_MIGRATION_PATH);

        for (String table : NEW_TABLES) {
            assertThat(normalize(tableBlock(schema, table)))
                    .as("baseline and V20260711 must define the same %s table", table)
                    .isEqualTo(normalize(tableBlock(migration, table)));
        }
    }

    @Test
    void baselineContainsFinalHistoryConstraintsAndQueueIndexes() throws IOException {
        String schema = read("sql/agent_service_schema.sql");
        String pendingAction = tableBlock(schema, "t_agent_pending_action");
        String riskPolicy = tableBlock(schema, "t_agent_risk_policy");
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
        assertThat(riskPolicy)
                .contains("idempotency_key VARCHAR(512) NOT NULL")
                .contains("policy_version BIGINT NOT NULL")
                .contains("CONSTRAINT uk_agent_risk_policy_idempotency UNIQUE (idempotency_key)")
                .contains("CONSTRAINT uk_agent_risk_policy_key_version UNIQUE (policy_key, policy_version)")
                .contains("KEY idx_agent_risk_policy_key_version (policy_key, policy_version)");
        assertThat(effectivePolicy)
                .contains("UNIQUE (policy_key)");
        assertThat(syncOutbox)
                .contains("UNIQUE (outbox_id)")
                .contains("UNIQUE (policy_key, policy_version, operation)")
                .contains("KEY idx_agent_risk_policy_sync_outbox_claim (status, next_retry_time, lease_until)")
                .contains("KEY idx_agent_risk_policy_sync_outbox_key_version (policy_key, policy_version)");
    }

    @Test
    void v11DocumentsManualPreflightAndPartialDdlRecovery() throws IOException {
        String migration = read(V11_MIGRATION_PATH);

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
    void v11AddsOnlyItsCompatibleStructuresInOrder() throws IOException {
        String migration = read(V11_MIGRATION_PATH);

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

    @Test
    void v12RunbookRequiresOneShotPreflightAndExplainsPartialDdlRecovery() throws IOException {
        String migration = read(V12_MIGRATION_PATH);

        assertThat(migration)
                .contains("MANUAL RUNBOOK")
                .contains("PAUSE risk-profile scheduler, risk-analysis worker, and policy writers")
                .contains("V20260711 must be present and V20260712 must be absent")
                .contains("WHERE version IN ('V20260711', 'V20260712')")
                .contains("SELECT COUNT(*) FROM t_agent_risk_policy_effective")
                .contains("SELECT COUNT(*) FROM t_agent_risk_policy_sync_outbox")
                .contains("Both counts must be zero")
                .contains("preflight_sensitive_policy_payload")
                .contains("policy_payload_json REGEXP")
                .contains("PARTIAL DDL RECOVERY")
                .contains("MySQL DDL statements do not form one transaction")
                .contains("DO NOT rerun the full script")
                .contains("information_schema.COLUMNS")
                .contains("information_schema.TABLE_CONSTRAINTS")
                .contains("SHOW CREATE TABLE t_agent_risk_policy")
                .contains("execute only the final migration-history INSERT")
                .contains("If failure occurred before COMMIT, explicitly ROLLBACK");

        for (String guard : List.of(
                "preflight_v11_missing",
                "preflight_v12_present",
                "preflight_effective_not_empty",
                "preflight_outbox_not_empty",
                "preflight_sensitive_policy_payload"
        )) {
            assertThat(occurrences(migration, "'" + guard + "'"))
                    .as("preflight guard must have a seed and executable duplicate insert: %s", guard)
                    .isEqualTo(2);
        }
    }

    @Test
    void v12BackfillsAndGuardsDataBeforePostCommitConstraints() throws IOException {
        String migration = read(V12_MIGRATION_PATH);
        String normalized = normalize(migration);
        String upper = migration.toUpperCase(Locale.ROOT);

        assertThat(normalized)
                .contains("SET idempotency_key = CONCAT('legacy:', policy_id)")
                .contains("ROW_NUMBER() OVER ( PARTITION BY policy_key ORDER BY effective_time ASC, id ASC )")
                .contains("ROW_NUMBER() OVER ( PARTITION BY history.policy_key ORDER BY history.effective_time DESC, history.id DESC )")
                .contains("SET status = 'SUPERSEDED'")
                .contains("INSERT INTO t_agent_risk_policy_effective")
                .contains("INSERT INTO t_agent_risk_policy_sync_outbox")
                .contains("'UPSERT'")
                .contains("BEGIN;")
                .contains("MODIFY COLUMN idempotency_key VARCHAR(512) NOT NULL")
                .contains("MODIFY COLUMN policy_version BIGINT NOT NULL")
                .contains("ADD CONSTRAINT uk_agent_risk_policy_idempotency UNIQUE (idempotency_key)")
                .contains("ADD CONSTRAINT uk_agent_risk_policy_key_version UNIQUE (policy_key, policy_version)");

        for (String guard : List.of(
                "null_or_blank_history",
                "duplicate_history_identity",
                "nonpositive_history_version",
                "inconsistent_history_versions",
                "inconsistent_active_effective",
                "inconsistent_effective_outbox"
        )) {
            assertThat(occurrences(migration, "'" + guard + "'"))
                    .as("post-backfill guard must have a seed and executable duplicate insert: %s", guard)
                    .isEqualTo(2);
        }

        assertThat(upper)
                .doesNotContain("DELIMITER")
                .doesNotContain("CREATE PROCEDURE")
                .doesNotContain("JSON_SET(")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("DELETE FROM T_AGENT_RISK_POLICY")
                .doesNotContain("DROP TABLE")
                .doesNotContain("DROP COLUMN")
                .doesNotContain("TRUNCATE");
        assertThat(Pattern.compile("(?is)UPDATE\\s+t_agent_risk_policy(?:\\s+AS\\s+\\w+)?\\s+JOIN\\b")
                .matcher(migration).find()).isFalse();
        assertThat(Pattern.compile("(?<![A-Za-z0-9_])@[A-Za-z0-9_]+")
                .matcher(migration).find()).isFalse();

        int backfill = position(migration, "SET idempotency_key = CONCAT('legacy:'");
        int versionStage = position(migration, "INSERT INTO tmp_risk_policy_versions");
        int winnerStage = position(migration, "INSERT INTO tmp_risk_policy_active_winner");
        int supersede = position(migration, "SET status = 'SUPERSEDED'");
        int effectiveInsert = position(migration, "INSERT INTO t_agent_risk_policy_effective");
        int outboxInsert = position(migration, "INSERT INTO t_agent_risk_policy_sync_outbox");
        int postBackfillGuards = position(migration, "POST-BACKFILL SENTINEL GUARDS");
        int commit = position(migration, "\nCOMMIT;");
        int idempotencyNotNull = position(migration, "MODIFY COLUMN idempotency_key VARCHAR(512) NOT NULL");
        int versionNotNull = position(migration, "MODIFY COLUMN policy_version BIGINT NOT NULL");
        int uniqueConstraint = position(migration, "ADD CONSTRAINT uk_agent_risk_policy_idempotency");
        int versionUniqueConstraint = position(migration, "ADD CONSTRAINT uk_agent_risk_policy_key_version");
        int historyInsert = position(migration, "INSERT INTO t_agent_schema_migration_history");

        assertThat(backfill).isLessThan(versionStage);
        assertThat(versionStage).isLessThan(winnerStage);
        assertThat(winnerStage).isLessThan(supersede);
        assertThat(supersede).isLessThan(effectiveInsert);
        assertThat(effectiveInsert).isLessThan(outboxInsert);
        assertThat(outboxInsert).isLessThan(postBackfillGuards);
        assertThat(postBackfillGuards).isLessThan(commit);
        assertThat(commit).isLessThan(idempotencyNotNull);
        assertThat(idempotencyNotNull).isLessThan(versionNotNull);
        assertThat(versionNotNull).isLessThan(uniqueConstraint);
        assertThat(uniqueConstraint).isLessThan(versionUniqueConstraint);
        assertThat(versionUniqueConstraint).isLessThan(historyInsert);
    }

    @Test
    void realV12MigratesV11CompatibleLegacyDataOnH2() throws IOException {
        DataSource dataSource = h2DataSource("risk_policy_two_stage");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createPreV11RiskPolicyTable(jdbcTemplate);

        insertLegacyPolicy(jdbcTemplate, 10L, "old-active", "key-1", "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 9, 0));
        insertLegacyPolicy(jdbcTemplate, 20L, "new-active", "key-1", "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 10, 0));
        insertLegacyPolicy(jdbcTemplate, 30L, "same-time-lower-id", "key-2", "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 11, 0));
        insertLegacyPolicy(jdbcTemplate, 40L, "same-time-higher-id", "key-2", "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 11, 0));
        insertLegacyPolicy(jdbcTemplate, 50L, "disabled-only", "key-3", "DISABLED",
                LocalDateTime.of(2026, 7, 10, 12, 0));
        insertLegacyPolicy(jdbcTemplate, 61L, "active-before-disable", "key-5", "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 12, 30));
        insertLegacyPolicy(jdbcTemplate, 62L, "newer-disabled", "key-5", "DISABLED",
                LocalDateTime.of(2026, 7, 10, 12, 45));

        executeV11WithH2AlterSplit(dataSource);

        assertThat(columnNullable(jdbcTemplate, "idempotency_key")).isTrue();
        assertThat(columnNullable(jdbcTemplate, "policy_version")).isTrue();
        assertThat(constraintCount(jdbcTemplate, "uk_agent_risk_policy_idempotency")).isZero();
        insertLegacyPolicy(jdbcTemplate, 60L, "legacy-after-v11", "key-4", "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 13, 0));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_agent_risk_policy
                WHERE policy_id = 'legacy-after-v11'
                  AND idempotency_key IS NULL
                  AND policy_version IS NULL
                """, Long.class)).isEqualTo(1L);

        executeMigration(V12_MIGRATION_PATH, dataSource);

        assertThat(history(jdbcTemplate, "key-1"))
                .extracting(RiskPolicyRow::policyVersion)
                .containsExactly(1L, 2L);
        assertThat(history(jdbcTemplate, "key-2"))
                .extracting(RiskPolicyRow::policyVersion)
                .containsExactly(1L, 2L);
        assertThat(status(jdbcTemplate, "old-active")).isEqualTo("SUPERSEDED");
        assertThat(status(jdbcTemplate, "new-active")).isEqualTo("ACTIVE");
        assertThat(status(jdbcTemplate, "same-time-lower-id")).isEqualTo("SUPERSEDED");
        assertThat(status(jdbcTemplate, "same-time-higher-id")).isEqualTo("ACTIVE");
        assertThat(status(jdbcTemplate, "active-before-disable")).isEqualTo("SUPERSEDED");
        assertThat(status(jdbcTemplate, "newer-disabled")).isEqualTo("DISABLED");
        assertThat(activeCount(jdbcTemplate, "key-1")).isEqualTo(1L);
        assertThat(activeCount(jdbcTemplate, "key-2")).isEqualTo(1L);
        assertThat(activeCount(jdbcTemplate, "key-5")).isZero();

        assertThat(effective(jdbcTemplate, "key-1"))
                .contains(new EffectiveRow(
                        "new-active",
                        2L,
                        "ACTIVE",
                        "{\"policy\":\"new-active\"}",
                        "{\"policy\":\"new-active\"}",
                        "PENDING",
                        "new-active"
                ));
        assertThat(effective(jdbcTemplate, "key-2"))
                .get()
                .extracting(EffectiveRow::policyId)
                .isEqualTo("same-time-higher-id");
        assertThat(effective(jdbcTemplate, "key-3")).isEmpty();
        assertThat(effective(jdbcTemplate, "key-5")).isEmpty();
        assertThat(outbox(jdbcTemplate, "key-1", 2L, "UPSERT"))
                .contains(new OutboxRow(
                        "new-active",
                        "new-active",
                        "{\"policy\":\"new-active\"}",
                        "",
                        "PENDING"
                ));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_agent_risk_policy
                WHERE idempotency_key IS NULL
                   OR TRIM(idempotency_key) = ''
                   OR policy_version IS NULL
                   OR policy_version <= 0
                """, Long.class)).isZero();
        assertThat(idempotencyKey(jdbcTemplate, "new-active")).isEqualTo("legacy:new-active");
        assertThat(idempotencyKey(jdbcTemplate, "legacy-after-v11"))
                .isEqualTo("legacy:legacy-after-v11");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_agent_risk_policy_effective AS effective
                INNER JOIN t_agent_risk_policy_sync_outbox AS outbox
                        ON outbox.outbox_id = effective.last_outbox_id
                       AND outbox.policy_key = effective.policy_key
                       AND outbox.policy_id = effective.policy_id
                       AND outbox.policy_version = effective.policy_version
                       AND outbox.operation = 'UPSERT'
                       AND outbox.redis_value_json = effective.redis_value_json
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy_effective", Long.class
        )).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy_sync_outbox", Long.class
        )).isEqualTo(3L);

        assertThat(columnNullable(jdbcTemplate, "idempotency_key")).isFalse();
        assertThat(columnNullable(jdbcTemplate, "policy_version")).isFalse();
        assertThat(constraintCount(jdbcTemplate, "uk_agent_risk_policy_idempotency"))
                .isEqualTo(1L);
        assertThat(constraintCount(jdbcTemplate, "uk_agent_risk_policy_key_version"))
                .isEqualTo(1L);
        assertThat(indexCount(jdbcTemplate, "idx_agent_risk_policy_key_version"))
                .isGreaterThanOrEqualTo(1L);
        assertThat(migrationHistoryVersions(jdbcTemplate))
                .containsExactly("V20260711", "V20260712");

        assertThatThrownBy(() -> insertPolicyWithIdentity(
                jdbcTemplate, 70L, "null-idempotency", "key-5", null, 1L
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPolicyWithIdentity(
                jdbcTemplate, 71L, "null-version", "key-5", "manual:null-version", null
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPolicyWithIdentity(
                jdbcTemplate, 72L, "duplicate-idempotency", "key-5", "legacy:new-active", 1L
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPolicyWithIdentity(
                jdbcTemplate, 73L, "duplicate-key-version", "key-1", "manual:duplicate-version", 2L
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> executeMigration(V12_MIGRATION_PATH, dataSource))
                .isInstanceOf(ScriptException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(migrationHistoryVersions(jdbcTemplate))
                .containsExactly("V20260711", "V20260712");
    }

    @Test
    void duplicateIdentitySentinelRollsBackBeforeV12Ddl() throws IOException {
        DataSource dataSource = h2DataSource("risk_policy_duplicate_guard");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createPreV11RiskPolicyTable(jdbcTemplate);
        executeV11WithH2AlterSplit(dataSource);
        insertPolicyWithIdentity(
                jdbcTemplate, 1L, "duplicate-a", "key-a", "manual:duplicate", null
        );
        insertPolicyWithIdentity(
                jdbcTemplate, 2L, "duplicate-b", "key-b", "manual:duplicate", null
        );

        assertThatThrownBy(() -> executeMigration(V12_MIGRATION_PATH, dataSource))
                .isInstanceOf(ScriptException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_schema_migration_history WHERE version = 'V20260712'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy_effective", Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy_sync_outbox", Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM t_agent_risk_policy
                WHERE policy_version IS NOT NULL
                """, Long.class)).isZero();
        assertThat(columnNullable(jdbcTemplate, "idempotency_key")).isTrue();
        assertThat(columnNullable(jdbcTemplate, "policy_version")).isTrue();
        assertThat(constraintCount(jdbcTemplate, "uk_agent_risk_policy_idempotency")).isZero();
    }

    @Test
    void sensitiveActivePayloadPreflightStopsBeforeBackfillAndOutbox() throws IOException {
        DataSource dataSource = h2DataSource("risk_policy_sensitive_payload_guard");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        createPreV11RiskPolicyTable(jdbcTemplate);
        insertLegacyPolicy(
                jdbcTemplate,
                1L,
                "unsafe-active",
                "unsafe-key",
                "ACTIVE",
                LocalDateTime.of(2026, 7, 10, 9, 0)
        );
        executeV11WithH2AlterSplit(dataSource);
        jdbcTemplate.update(
                "UPDATE t_agent_risk_policy SET policy_payload_json = ? WHERE policy_id = ?",
                "{\"action\":\"BLOCK_IP\",\"rawIp\":\"192.0.2.44\"}",
                "unsafe-active"
        );

        assertThatThrownBy(() -> executeMigration(V12_MIGRATION_PATH, dataSource))
                .isInstanceOf(ScriptException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_schema_migration_history WHERE version = 'V20260712'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy_effective", Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy_sync_outbox", Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy WHERE idempotency_key IS NULL",
                Long.class
        )).isEqualTo(1L);
        assertThat(columnNullable(jdbcTemplate, "idempotency_key")).isTrue();
        assertThat(columnNullable(jdbcTemplate, "policy_version")).isTrue();
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

    private int occurrences(String value, String token) {
        return value.split(Pattern.quote(token), -1).length - 1;
    }

    private void executeMigration(String path, DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource);
    }

    private void executeV11WithH2AlterSplit(DataSource dataSource) throws IOException {
        String v11 = read(V11_MIGRATION_PATH);
        assertThat(v11).contains(V11_MYSQL_MULTI_ACTION_ALTER);
        String h2CompatibleV11 = v11.replace(
                V11_MYSQL_MULTI_ACTION_ALTER,
                V11_H2_EQUIVALENT_ALTER
        );
        new ResourceDatabasePopulator(
                new ByteArrayResource(h2CompatibleV11.getBytes(StandardCharsets.UTF_8))
        ).execute(dataSource);
    }

    private void createPreV11RiskPolicyTable(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE t_agent_risk_policy (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    policy_id VARCHAR(128) NOT NULL,
                    policy_key VARCHAR(512) NOT NULL,
                    action VARCHAR(64) NOT NULL,
                    target_type VARCHAR(32) NOT NULL,
                    gid VARCHAR(64) NOT NULL DEFAULT '',
                    domain VARCHAR(256) NOT NULL DEFAULT '',
                    short_uri VARCHAR(128) NOT NULL DEFAULT '',
                    ip_hash VARCHAR(128) NOT NULL DEFAULT '',
                    policy_payload_json LONGTEXT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    effective_time TIMESTAMP NOT NULL,
                    expire_time TIMESTAMP,
                    source VARCHAR(64) NOT NULL,
                    trace_id VARCHAR(128) NOT NULL DEFAULT '',
                    event_id VARCHAR(128) NOT NULL DEFAULT '',
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_agent_risk_policy_policy_id UNIQUE (policy_id)
                )
                """);
    }

    private void insertLegacyPolicy(
            JdbcTemplate jdbcTemplate,
            long id,
            String policyId,
            String policyKey,
            String status,
            LocalDateTime effectiveTime
    ) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_risk_policy (
                            id,
                            policy_id,
                            policy_key,
                            action,
                            target_type,
                            gid,
                            domain,
                            short_uri,
                            ip_hash,
                            policy_payload_json,
                            status,
                            effective_time,
                            expire_time,
                            source,
                            trace_id,
                            event_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                policyId,
                policyKey,
                "DISABLE_SHORT_LINK",
                "SHORT_LINK",
                "gid-001",
                "nurl.ink",
                policyId,
                "",
                "{\"policy\":\"" + policyId + "\"}",
                status,
                Timestamp.valueOf(effectiveTime),
                null,
                "MANUAL_REVIEW",
                "trace-" + policyId,
                "event-" + policyId
        );
    }

    private void insertPolicyWithIdentity(
            JdbcTemplate jdbcTemplate,
            long id,
            String policyId,
            String policyKey,
            String idempotencyKey,
            Long policyVersion
    ) {
        jdbcTemplate.update("""
                        INSERT INTO t_agent_risk_policy (
                            id,
                            policy_id,
                            policy_key,
                            idempotency_key,
                            policy_version,
                            action,
                            target_type,
                            gid,
                            domain,
                            short_uri,
                            ip_hash,
                            policy_payload_json,
                            status,
                            effective_time,
                            expire_time,
                            source,
                            trace_id,
                            event_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                policyId,
                policyKey,
                idempotencyKey,
                policyVersion,
                "DISABLE_SHORT_LINK",
                "SHORT_LINK",
                "gid-001",
                "nurl.ink",
                policyId,
                "",
                "{\"policy\":\"" + policyId + "\"}",
                "DISABLED",
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 10, 14, 0).plusMinutes(id)),
                null,
                "MANUAL_REVIEW",
                "trace-" + policyId,
                "event-" + policyId
        );
    }

    private List<RiskPolicyRow> history(JdbcTemplate jdbcTemplate, String policyKey) {
        return jdbcTemplate.query("""
                        SELECT policy_id, policy_version, status
                        FROM t_agent_risk_policy
                        WHERE policy_key = ?
                        ORDER BY effective_time ASC, id ASC
                        """,
                (rs, rowNum) -> new RiskPolicyRow(
                        rs.getString("policy_id"),
                        rs.getLong("policy_version"),
                        rs.getString("status")
                ),
                policyKey
        );
    }

    private Optional<EffectiveRow> effective(JdbcTemplate jdbcTemplate, String policyKey) {
        return jdbcTemplate.query("""
                        SELECT policy_id,
                               policy_version,
                               desired_state,
                               policy_payload_json,
                               redis_value_json,
                               sync_status,
                               last_outbox_id
                        FROM t_agent_risk_policy_effective
                        WHERE policy_key = ?
                        """,
                (rs, rowNum) -> new EffectiveRow(
                        rs.getString("policy_id"),
                        rs.getLong("policy_version"),
                        rs.getString("desired_state"),
                        rs.getString("policy_payload_json"),
                        rs.getString("redis_value_json"),
                        rs.getString("sync_status"),
                        rs.getString("last_outbox_id")
                ),
                policyKey
        ).stream().findFirst();
    }

    private Optional<OutboxRow> outbox(
            JdbcTemplate jdbcTemplate,
            String policyKey,
            long policyVersion,
            String operation
    ) {
        return jdbcTemplate.query("""
                        SELECT outbox_id,
                               policy_id,
                               redis_value_json,
                               expected_redis_value,
                               status
                        FROM t_agent_risk_policy_sync_outbox
                        WHERE policy_key = ?
                          AND policy_version = ?
                          AND operation = ?
                        """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getString("outbox_id"),
                        rs.getString("policy_id"),
                        rs.getString("redis_value_json"),
                        rs.getString("expected_redis_value"),
                        rs.getString("status")
                ),
                policyKey,
                policyVersion,
                operation
        ).stream().findFirst();
    }

    private String status(JdbcTemplate jdbcTemplate, String policyId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM t_agent_risk_policy WHERE policy_id = ?",
                String.class,
                policyId
        );
    }

    private String idempotencyKey(JdbcTemplate jdbcTemplate, String policyId) {
        return jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM t_agent_risk_policy WHERE policy_id = ?",
                String.class,
                policyId
        );
    }

    private long activeCount(JdbcTemplate jdbcTemplate, String policyKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_agent_risk_policy WHERE policy_key = ? AND status = 'ACTIVE'",
                Long.class,
                policyKey
        );
    }

    private boolean columnNullable(JdbcTemplate jdbcTemplate, String columnName) {
        String nullable = jdbcTemplate.queryForObject("""
                        SELECT IS_NULLABLE
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE LOWER(TABLE_NAME) = 't_agent_risk_policy'
                          AND LOWER(COLUMN_NAME) = LOWER(?)
                        """,
                String.class,
                columnName
        );
        return "YES".equalsIgnoreCase(nullable);
    }

    private long constraintCount(JdbcTemplate jdbcTemplate, String constraintName) {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                        WHERE LOWER(TABLE_NAME) = 't_agent_risk_policy'
                          AND LOWER(CONSTRAINT_NAME) = LOWER(?)
                        """,
                Long.class,
                constraintName
        );
    }

    private long indexCount(JdbcTemplate jdbcTemplate, String indexName) {
        return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM INFORMATION_SCHEMA.INDEXES
                        WHERE LOWER(TABLE_NAME) = 't_agent_risk_policy'
                          AND LOWER(INDEX_NAME) = LOWER(?)
                        """,
                Long.class,
                indexName
        );
    }

    private List<String> migrationHistoryVersions(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForList("""
                SELECT version
                FROM t_agent_schema_migration_history
                ORDER BY version
                """, String.class);
    }

    private DataSource h2DataSource(String prefix) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
        );
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record RiskPolicyRow(String policyId, long policyVersion, String status) {
    }

    private record EffectiveRow(
            String policyId,
            long policyVersion,
            String desiredState,
            String policyPayloadJson,
            String redisValueJson,
            String syncStatus,
            String lastOutboxId
    ) {
    }

    private record OutboxRow(
            String outboxId,
            String policyId,
            String redisValueJson,
            String expectedRedisValue,
            String status
    ) {
    }
}
