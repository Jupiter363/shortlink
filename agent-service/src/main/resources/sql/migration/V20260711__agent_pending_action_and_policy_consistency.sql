-- Agent pending-action and policy-consistency compatibility migration for MySQL 8.
--
-- MANUAL RUNBOOK (schema owner / DBA):
-- 1. PRE-FLIGHT. If t_agent_schema_migration_history exists, confirm this returns no rows:
--    SELECT version FROM t_agent_schema_migration_history WHERE version = 'V20260711';
--    If V20260711 history exists, STOP; rerunning is prohibited.
--    Inventory the additive objects before execution:
--    SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME IN ('t_agent_pending_action', 't_agent_risk_policy_effective',
--                         't_agent_risk_policy_sync_outbox');
--    SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_agent_risk_policy'
--      AND COLUMN_NAME IN ('idempotency_key', 'policy_version');
--    SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS
--    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_agent_risk_policy'
--      AND INDEX_NAME = 'idx_agent_risk_policy_key_version';
--    For each new table already present, inspect its complete definition:
--    SHOW CREATE TABLE t_agent_pending_action;
--    SHOW CREATE TABLE t_agent_risk_policy_effective;
--    SHOW CREATE TABLE t_agent_risk_policy_sync_outbox;
-- 2. MySQL DDL statements do not form one transaction. If some DDL succeeds but the
--    migration-history row is missing, DO NOT rerun the full script.
-- 3. RECOVERY. Use information_schema and SHOW CREATE TABLE to verify all three tables,
--    both nullable columns, and idx_agent_risk_policy_key_version exactly match this script.
--    If the structure is complete, execute only the final migration-history INSERT.
--    If it is incomplete or drifted, a DBA must repair only the missing objects, verify
--    the complete structure again, and then execute only the final migration-history INSERT.
-- 4. If V20260711 history exists, STOP; any later repair must be a separately reviewed change.
--
-- This project does not use Flyway or Liquibase.

CREATE TABLE IF NOT EXISTS t_agent_schema_migration_history (
    version VARCHAR(64) NOT NULL,
    description VARCHAR(256) NOT NULL,
    script_name VARCHAR(256) NOT NULL,
    applied_by VARCHAR(256) NOT NULL,
    applied_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
);

CREATE TABLE IF NOT EXISTS t_agent_pending_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    action_id VARCHAR(128) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    action_type VARCHAR(128) NOT NULL,
    payload_version INTEGER NOT NULL,
    authorization_scope VARCHAR(32) NOT NULL,
    owner_username VARCHAR(128) NOT NULL DEFAULT '',
    gid VARCHAR(64) NOT NULL DEFAULT '',
    target_type VARCHAR(32) NOT NULL,
    target_key VARCHAR(512) NOT NULL,
    target_ref_json LONGTEXT NOT NULL,
    title VARCHAR(256) NOT NULL,
    summary VARCHAR(2048) NOT NULL DEFAULT '',
    payload_json LONGTEXT NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    active_slot_key CHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    expire_time TIMESTAMP NULL,
    version BIGINT NOT NULL DEFAULT 0,
    execution_token VARCHAR(128) NOT NULL DEFAULT '',
    execution_lease_until TIMESTAMP NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    result_json LONGTEXT NOT NULL,
    failure_code VARCHAR(128) NOT NULL DEFAULT '',
    failure_message VARCHAR(2048) NOT NULL DEFAULT '',
    proposed_by VARCHAR(128) NOT NULL,
    confirmed_by VARCHAR(128) NOT NULL DEFAULT '',
    confirmed_time TIMESTAMP NULL,
    rejected_by VARCHAR(128) NOT NULL DEFAULT '',
    rejected_time TIMESTAMP NULL,
    rejection_reason VARCHAR(2048) NOT NULL DEFAULT '',
    rejection_review_action VARCHAR(32) NULL,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    event_id VARCHAR(128) NOT NULL DEFAULT '',
    batch_id VARCHAR(128) NOT NULL DEFAULT '',
    session_id VARCHAR(256) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_pending_action_action_id UNIQUE (action_id),
    CONSTRAINT uk_agent_pending_action_type_idempotency UNIQUE (action_type, idempotency_key),
    CONSTRAINT uk_agent_pending_action_active_slot UNIQUE (active_slot_key),
    KEY idx_agent_pending_action_gid_status (gid, status, update_time),
    KEY idx_agent_pending_action_execution_lease (status, execution_lease_until),
    KEY idx_agent_pending_action_event_id (event_id),
    KEY idx_agent_pending_action_batch_gid (batch_id, gid)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_policy_effective (
    id BIGINT NOT NULL AUTO_INCREMENT,
    policy_key VARCHAR(512) NOT NULL,
    policy_id VARCHAR(128) NOT NULL,
    policy_version BIGINT NOT NULL,
    gid VARCHAR(64) NOT NULL DEFAULT '',
    action VARCHAR(64) NOT NULL,
    desired_state VARCHAR(32) NOT NULL,
    policy_payload_json LONGTEXT NOT NULL,
    redis_value_json LONGTEXT NOT NULL,
    effective_time TIMESTAMP NOT NULL,
    expire_time TIMESTAMP NULL,
    sync_status VARCHAR(32) NOT NULL,
    last_outbox_id VARCHAR(128) NOT NULL DEFAULT '',
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_policy_effective_key UNIQUE (policy_key)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_policy_sync_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    outbox_id VARCHAR(128) NOT NULL,
    policy_key VARCHAR(512) NOT NULL,
    policy_id VARCHAR(128) NOT NULL,
    policy_version BIGINT NOT NULL,
    operation VARCHAR(32) NOT NULL,
    redis_value_json LONGTEXT NOT NULL,
    expected_redis_value LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP NULL,
    owner_token VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMP NULL,
    last_error VARCHAR(2048) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_policy_sync_outbox_id UNIQUE (outbox_id),
    CONSTRAINT uk_agent_risk_policy_sync_outbox_operation UNIQUE (policy_key, policy_version, operation),
    KEY idx_agent_risk_policy_sync_outbox_claim (status, next_retry_time, lease_until),
    KEY idx_agent_risk_policy_sync_outbox_key_version (policy_key, policy_version)
);

ALTER TABLE t_agent_risk_policy
    ADD COLUMN idempotency_key VARCHAR(512) NULL AFTER policy_key,
    ADD COLUMN policy_version BIGINT NULL AFTER idempotency_key,
    ADD INDEX idx_agent_risk_policy_key_version (policy_key, policy_version);

INSERT INTO t_agent_schema_migration_history (
    version,
    description,
    script_name,
    applied_by
)
VALUES (
    'V20260711',
    'Agent pending actions and policy consistency compatibility schema',
    'V20260711__agent_pending_action_and_policy_consistency.sql',
    CURRENT_USER()
);
