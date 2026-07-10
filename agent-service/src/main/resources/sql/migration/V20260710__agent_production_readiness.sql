-- Agent production-readiness migration for MySQL 8.
--
-- SCHEMA-FIRST RUNBOOK:
-- 1. PAUSE risk-profile scheduler and risk-analysis workers on every agent-service instance.
-- 2. Confirm no process is writing either risk profile table.
-- 3. Back up the four affected tables and record their row counts.
-- 4. Execute this script once with a schema owner account.
-- 5. Verify the duplicate checks return no rows and record the migration-history row.
-- 6. Deploy the matching agent-service code, then resume workers and scheduler.
--
-- This project does not use Flyway or Liquibase. Do not rerun this file after
-- V20260710 is present in t_agent_schema_migration_history.

CREATE TABLE IF NOT EXISTS t_agent_schema_migration_history (
    version VARCHAR(64) NOT NULL,
    description VARCHAR(256) NOT NULL,
    script_name VARCHAR(256) NOT NULL,
    applied_by VARCHAR(256) NOT NULL,
    applied_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (version)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_profile_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id VARCHAR(128) NOT NULL,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    owner_token VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMP,
    scanned_count INTEGER NOT NULL DEFAULT 0,
    generated_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    analysis_job_count INTEGER NOT NULL DEFAULT 0,
    failures_json LONGTEXT NOT NULL,
    error_summary VARCHAR(2048) NOT NULL DEFAULT '',
    start_time TIMESTAMP,
    finish_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_profile_batch_id UNIQUE (batch_id),
    KEY idx_agent_risk_profile_batch_window (window_end, status),
    KEY idx_agent_risk_profile_batch_lease (status, lease_until)
);

-- CREATE TABLE IF NOT EXISTS does not add indexes to an existing batch table.
ALTER TABLE t_agent_risk_profile_batch
    ADD INDEX idx_agent_risk_profile_batch_recovery (update_time, window_end, id, status, lease_until);

CREATE TABLE IF NOT EXISTS t_agent_risk_analysis_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id VARCHAR(128) NOT NULL,
    batch_id VARCHAR(128) NOT NULL,
    gid VARCHAR(64) NOT NULL,
    graph_name VARCHAR(128) NOT NULL,
    graph_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_time TIMESTAMP,
    owner_token VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMP,
    session_id VARCHAR(256) NOT NULL,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    error_summary VARCHAR(2048) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_analysis_job_id UNIQUE (job_id),
    CONSTRAINT uk_agent_risk_analysis_job_scope UNIQUE (batch_id, gid, graph_name, graph_version),
    KEY idx_agent_risk_analysis_job_claim (status, next_retry_time, lease_until)
);

-- Add nullable columns first so legacy rows remain readable while backfill runs.
ALTER TABLE t_agent_short_link_risk_profile ADD COLUMN batch_id VARCHAR(128) NULL AFTER id;
ALTER TABLE t_agent_group_risk_profile ADD COLUMN batch_id VARCHAR(128) NULL AFTER id;

-- Keep historical short-link and group profiles from the same profile window
-- under the same deterministic legacy batch. Never collapse all history to ''.
UPDATE t_agent_short_link_risk_profile
SET batch_id = CONCAT('legacy-risk-profile:', DATE_FORMAT(profile_window_end, '%Y%m%d%H%i%s'))
WHERE batch_id IS NULL OR batch_id = '';

UPDATE t_agent_group_risk_profile
SET batch_id = CONCAT('legacy-risk-profile:', DATE_FORMAT(profile_window_end, '%Y%m%d%H%i%s'))
WHERE batch_id IS NULL OR batch_id = '';

-- Pre-delete visibility: capture these result sets in the migration record.
SELECT batch_id, gid, domain, short_uri, COUNT(*) AS duplicate_count
FROM t_agent_short_link_risk_profile
GROUP BY batch_id, gid, domain, short_uri
HAVING COUNT(*) > 1;

SELECT batch_id, gid, COUNT(*) AS duplicate_count
FROM t_agent_group_risk_profile
GROUP BY batch_id, gid
HAVING COUNT(*) > 1;

-- Keep the newest row for any historical or partially migrated duplicate key.
DELETE stale
FROM t_agent_short_link_risk_profile AS stale
INNER JOIN t_agent_short_link_risk_profile AS keeper
        ON keeper.batch_id = stale.batch_id
       AND keeper.gid = stale.gid
       AND keeper.domain = stale.domain
       AND keeper.short_uri = stale.short_uri
       AND (
            keeper.profile_window_end > stale.profile_window_end
            OR (
                keeper.profile_window_end = stale.profile_window_end
                AND keeper.id > stale.id
            )
       );

DELETE stale
FROM t_agent_group_risk_profile AS stale
INNER JOIN t_agent_group_risk_profile AS keeper
        ON keeper.batch_id = stale.batch_id
       AND keeper.gid = stale.gid
       AND (
            keeper.profile_window_end > stale.profile_window_end
            OR (
                keeper.profile_window_end = stale.profile_window_end
                AND keeper.id > stale.id
            )
       );

-- Materialize terminal batch rows for legacy profiles so batch-aware queries
-- and operations have a complete historical anchor.
INSERT IGNORE INTO t_agent_risk_profile_batch (
    batch_id,
    window_start,
    window_end,
    status,
    owner_token,
    lease_until,
    scanned_count,
    generated_count,
    failed_count,
    analysis_job_count,
    failures_json,
    error_summary,
    start_time,
    finish_time,
    create_time,
    update_time
)
SELECT legacy.batch_id,
       MIN(legacy.profile_window_start),
       MAX(legacy.profile_window_end),
       'SUCCEEDED',
       '',
       NULL,
       SUM(legacy.short_link_row),
       SUM(legacy.short_link_row),
       0,
       0,
       '[]',
       '',
       MIN(legacy.create_time),
       MAX(legacy.update_time),
       MIN(legacy.create_time),
       MAX(legacy.update_time)
FROM (
    SELECT batch_id,
           profile_window_start,
           profile_window_end,
           create_time,
           update_time,
           1 AS short_link_row
    FROM t_agent_short_link_risk_profile
    UNION ALL
    SELECT batch_id,
           profile_window_start,
           profile_window_end,
           create_time,
           update_time,
           0 AS short_link_row
    FROM t_agent_group_risk_profile
) AS legacy
GROUP BY legacy.batch_id;

-- Re-run duplicate checks. Both queries must return zero rows before continuing.
SELECT batch_id, gid, domain, short_uri, COUNT(*) AS duplicate_count
FROM t_agent_short_link_risk_profile
GROUP BY batch_id, gid, domain, short_uri
HAVING COUNT(*) > 1;

SELECT batch_id, gid, COUNT(*) AS duplicate_count
FROM t_agent_group_risk_profile
GROUP BY batch_id, gid
HAVING COUNT(*) > 1;

ALTER TABLE t_agent_short_link_risk_profile
    MODIFY COLUMN batch_id VARCHAR(128) NOT NULL;

ALTER TABLE t_agent_group_risk_profile
    MODIFY COLUMN batch_id VARCHAR(128) NOT NULL;

ALTER TABLE t_agent_short_link_risk_profile
    ADD CONSTRAINT uk_agent_short_link_profile_batch_target
        UNIQUE (batch_id, gid, domain, short_uri);

ALTER TABLE t_agent_group_risk_profile
    ADD CONSTRAINT uk_agent_group_profile_batch_gid
        UNIQUE (batch_id, gid);

INSERT INTO t_agent_schema_migration_history (
    version,
    description,
    script_name,
    applied_by
)
VALUES (
    'V20260710',
    'Agent production readiness batch and job schema',
    'V20260710__agent_production_readiness.sql',
    CURRENT_USER()
);
