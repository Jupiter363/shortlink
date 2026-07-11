-- Risk-policy history constraints and legacy backfill for MySQL 8 and H2 MySQL mode.
--
-- MANUAL RUNBOOK (schema owner / DBA; execute once without a continue-on-error option):
-- 1. PAUSE risk-profile scheduler, risk-analysis worker, and policy writers on every
--    agent-service instance. Confirm no old saveActive() writer remains alive.
-- 2. Back up t_agent_risk_policy, t_agent_risk_policy_effective,
--    t_agent_risk_policy_sync_outbox, and t_agent_schema_migration_history.
-- 3. PRE-FLIGHT. V20260711 must be present and V20260712 must be absent:
--    SELECT version FROM t_agent_schema_migration_history
--    WHERE version IN ('V20260711', 'V20260712') ORDER BY version;
--    The result must contain exactly V20260711. Both initialization targets must be empty:
--    SELECT COUNT(*) FROM t_agent_risk_policy_effective;
--    SELECT COUNT(*) FROM t_agent_risk_policy_sync_outbox;
--    Both counts must be zero. The executable preflight sentinels below enforce these rules.
--    Inspect active payloads before pausing writers; unsafe payloads must be disabled and
--    replaced through the approved policy path rather than copied into effective/outbox:
--    SELECT policy_id FROM t_agent_risk_policy
--    WHERE LOWER(policy_payload_json) LIKE '%rawip%'
--       OR LOWER(policy_payload_json) LIKE '%ipaddress%'
--       OR LOWER(policy_payload_json) LIKE '%visitorid%'
--       OR LOWER(policy_payload_json) LIKE '%userid%'
--       OR policy_payload_json REGEXP '(^|[^0-9])([0-9]{1,3}[.]){3}[0-9]{1,3}([^0-9]|$)';
-- 4. Run the complete file in one database session. A duplicate-key error whose key starts
--    with preflight_ or names a post-backfill invariant is an intentional STOP signal.
--    Do not continue after an error. ROLLBACK before investigating and rerunning from a
--    fresh session; the policy writers must remain paused.
-- 5. Verify V20260712 history exists, both history columns are NOT NULL, both named unique
--    constraints exist, each policy_key has at most one ACTIVE row, and effective/outbox
--    rows have matching policy_id, policy_version, and redis_value_json. Resume workers
--    only after the legacy UPSERT outbox has been observed and policy writers are healthy.
--
-- PARTIAL DDL RECOVERY:
-- MySQL DDL statements do not form one transaction. This script commits all validated DML
-- before changing nullability, so a failure after COMMIT can leave some DDL applied while
-- V20260712 history is absent. In that state, DO NOT rerun the full script: the non-empty
-- effective/outbox preflight is expected to reject it. Inspect information_schema.COLUMNS,
-- information_schema.TABLE_CONSTRAINTS, and SHOW CREATE TABLE t_agent_risk_policy. Apply
-- only the missing MODIFY/ADD CONSTRAINT statements, rerun the documented invariant
-- queries, and execute only the final migration-history INSERT after every object matches.
-- If failure occurred before COMMIT, explicitly ROLLBACK; after correcting the reported
-- invariant, rerun the full script from a fresh session because no persistent DDL ran.
-- After V20260712 succeeds, do not roll back to the legacy saveActive() writer.
--
-- This project does not use Flyway or Liquibase.

CREATE TEMPORARY TABLE tmp_risk_policy_migration_guard (
    guard_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (guard_name)
);

-- Every guard key is inserted once up front. A violated invariant inserts the same key
-- again and raises a duplicate-key error that stops ordinary MySQL/H2 script runners.
INSERT INTO tmp_risk_policy_migration_guard (guard_name)
VALUES
    ('preflight_v11_missing'),
    ('preflight_v12_present'),
    ('preflight_effective_not_empty'),
    ('preflight_outbox_not_empty'),
    ('preflight_sensitive_policy_payload'),
    ('null_or_blank_history'),
    ('duplicate_history_identity'),
    ('nonpositive_history_version'),
    ('inconsistent_history_versions'),
    ('inconsistent_active_effective'),
    ('inconsistent_effective_outbox');

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'preflight_v11_missing'
WHERE NOT EXISTS (
    SELECT 1
    FROM t_agent_schema_migration_history
    WHERE version = 'V20260711'
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'preflight_v12_present'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_schema_migration_history
    WHERE version = 'V20260712'
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'preflight_effective_not_empty'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_risk_policy_effective
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'preflight_outbox_not_empty'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_risk_policy_sync_outbox
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'preflight_sensitive_policy_payload'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_risk_policy
    WHERE status = 'ACTIVE'
      AND (
          LOWER(policy_payload_json) LIKE '%rawip%'
          OR LOWER(policy_payload_json) LIKE '%ipaddress%'
          OR LOWER(policy_payload_json) LIKE '%visitorid%'
          OR LOWER(policy_payload_json) LIKE '%userid%'
          OR policy_payload_json REGEXP '(^|[^0-9])([0-9]{1,3}[.]){3}[0-9]{1,3}([^0-9]|$)'
      )
);

CREATE TEMPORARY TABLE tmp_risk_policy_versions (
    policy_id VARCHAR(128) NOT NULL,
    policy_key VARCHAR(512) NOT NULL,
    policy_version BIGINT NOT NULL,
    PRIMARY KEY (policy_id)
);

CREATE TEMPORARY TABLE tmp_risk_policy_active_winner (
    policy_key VARCHAR(512) NOT NULL,
    policy_id VARCHAR(128) NOT NULL,
    policy_version BIGINT NOT NULL,
    gid VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    policy_payload_json LONGTEXT NOT NULL,
    effective_time TIMESTAMP NOT NULL,
    expire_time TIMESTAMP NULL,
    trace_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (policy_key)
);

BEGIN;

-- Backfill the stable history identity before assigning versions.
UPDATE t_agent_risk_policy
SET idempotency_key = CONCAT('legacy:', policy_id)
WHERE idempotency_key IS NULL OR TRIM(idempotency_key) = '';

-- Re-number every history row, including partially populated V11-era rows, into a
-- deterministic contiguous sequence for each policy key.
INSERT INTO tmp_risk_policy_versions (
    policy_id,
    policy_key,
    policy_version
)
SELECT policy_id,
       policy_key,
       ROW_NUMBER() OVER (
           PARTITION BY policy_key
           ORDER BY effective_time ASC, id ASC
       ) AS policy_version
FROM t_agent_risk_policy;

UPDATE t_agent_risk_policy
SET policy_version = (
    SELECT staged.policy_version
    FROM tmp_risk_policy_versions AS staged
    WHERE staged.policy_id = t_agent_risk_policy.policy_id
)
WHERE EXISTS (
    SELECT 1
    FROM tmp_risk_policy_versions AS staged
    WHERE staged.policy_id = t_agent_risk_policy.policy_id
);

-- Capture a winner only when the latest history row for a key is ACTIVE. Ranking all
-- statuses prevents an older ACTIVE row from being resurrected after a newer DISABLED
-- or EXPIRED history row. Equal effective_time values are resolved by the larger id.
INSERT INTO tmp_risk_policy_active_winner (
    policy_key,
    policy_id,
    policy_version,
    gid,
    action,
    policy_payload_json,
    effective_time,
    expire_time,
    trace_id
)
SELECT ranked.policy_key,
       ranked.policy_id,
       ranked.policy_version,
       ranked.gid,
       ranked.action,
       ranked.policy_payload_json,
       ranked.effective_time,
       ranked.expire_time,
       ranked.trace_id
FROM (
    SELECT history.policy_key,
           history.policy_id,
           history.policy_version,
           history.gid,
           history.action,
           history.policy_payload_json,
           history.status,
           history.effective_time,
           history.expire_time,
           history.trace_id,
           ROW_NUMBER() OVER (
               PARTITION BY history.policy_key
               ORDER BY history.effective_time DESC, history.id DESC
           ) AS active_rank
    FROM t_agent_risk_policy AS history
) AS ranked
WHERE ranked.active_rank = 1
  AND ranked.status = 'ACTIVE';

UPDATE t_agent_risk_policy
SET status = 'SUPERSEDED',
    update_time = CURRENT_TIMESTAMP
WHERE status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1
      FROM tmp_risk_policy_active_winner AS winner
      WHERE winner.policy_id = t_agent_risk_policy.policy_id
  );

-- policy_id is globally unique and fits the 128-character outbox identifier column, so
-- it is also the deterministic identifier for each one-shot legacy UPSERT command.
INSERT INTO t_agent_risk_policy_effective (
    policy_key,
    policy_id,
    policy_version,
    gid,
    action,
    desired_state,
    policy_payload_json,
    redis_value_json,
    effective_time,
    expire_time,
    sync_status,
    last_outbox_id,
    trace_id
)
SELECT winner.policy_key,
       winner.policy_id,
       winner.policy_version,
       winner.gid,
       winner.action,
       'ACTIVE',
       winner.policy_payload_json,
       winner.policy_payload_json,
       winner.effective_time,
       winner.expire_time,
       'PENDING',
       winner.policy_id,
       winner.trace_id
FROM tmp_risk_policy_active_winner AS winner;

INSERT INTO t_agent_risk_policy_sync_outbox (
    outbox_id,
    policy_key,
    policy_id,
    policy_version,
    operation,
    redis_value_json,
    expected_redis_value,
    status
)
SELECT winner.policy_id,
       winner.policy_key,
       winner.policy_id,
       winner.policy_version,
       'UPSERT',
       winner.policy_payload_json,
       '',
       'PENDING'
FROM tmp_risk_policy_active_winner AS winner;

-- POST-BACKFILL SENTINEL GUARDS. Each statement must insert zero rows. Any duplicate-key
-- error means the DML transaction is invalid and must be rolled back without running DDL.
INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'null_or_blank_history'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_risk_policy
    WHERE idempotency_key IS NULL
       OR TRIM(idempotency_key) = ''
       OR policy_version IS NULL
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'duplicate_history_identity'
WHERE EXISTS (
    SELECT idempotency_key
    FROM t_agent_risk_policy
    GROUP BY idempotency_key
    HAVING COUNT(*) > 1
)
OR EXISTS (
    SELECT policy_key, policy_version
    FROM t_agent_risk_policy
    GROUP BY policy_key, policy_version
    HAVING COUNT(*) > 1
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'nonpositive_history_version'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_risk_policy
    WHERE policy_version <= 0
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'inconsistent_history_versions'
WHERE EXISTS (
    SELECT policy_key
    FROM t_agent_risk_policy
    GROUP BY policy_key
    HAVING MIN(policy_version) <> 1
        OR MAX(policy_version) <> COUNT(*)
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'inconsistent_active_effective'
WHERE EXISTS (
    SELECT policy_key
    FROM t_agent_risk_policy
    WHERE status = 'ACTIVE'
    GROUP BY policy_key
    HAVING COUNT(*) > 1
)
OR EXISTS (
    SELECT 1
    FROM t_agent_risk_policy AS history
    WHERE history.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1
          FROM t_agent_risk_policy_effective AS effective
          WHERE effective.policy_key = history.policy_key
            AND effective.policy_id = history.policy_id
            AND effective.policy_version = history.policy_version
            AND effective.gid = history.gid
            AND effective.action = history.action
            AND effective.desired_state = 'ACTIVE'
            AND effective.policy_payload_json = history.policy_payload_json
            AND effective.redis_value_json = history.policy_payload_json
            AND effective.effective_time = history.effective_time
            AND (
                effective.expire_time = history.expire_time
                OR (effective.expire_time IS NULL AND history.expire_time IS NULL)
            )
            AND effective.sync_status = 'PENDING'
      )
)
OR EXISTS (
    SELECT 1
    FROM t_agent_risk_policy_effective AS effective
    WHERE NOT EXISTS (
        SELECT 1
        FROM t_agent_risk_policy AS history
        WHERE history.policy_key = effective.policy_key
          AND history.policy_id = effective.policy_id
          AND history.policy_version = effective.policy_version
          AND history.status = 'ACTIVE'
          AND history.policy_payload_json = effective.policy_payload_json
          AND history.policy_payload_json = effective.redis_value_json
    )
);

INSERT INTO tmp_risk_policy_migration_guard (guard_name)
SELECT 'inconsistent_effective_outbox'
WHERE EXISTS (
    SELECT 1
    FROM t_agent_risk_policy_effective AS effective
    WHERE NOT EXISTS (
        SELECT 1
        FROM t_agent_risk_policy_sync_outbox AS outbox
        WHERE outbox.outbox_id = effective.last_outbox_id
          AND outbox.policy_key = effective.policy_key
          AND outbox.policy_id = effective.policy_id
          AND outbox.policy_version = effective.policy_version
          AND outbox.operation = 'UPSERT'
          AND outbox.redis_value_json = effective.redis_value_json
          AND outbox.expected_redis_value = ''
          AND outbox.status = 'PENDING'
    )
)
OR EXISTS (
    SELECT 1
    FROM t_agent_risk_policy_sync_outbox AS outbox
    WHERE outbox.operation <> 'UPSERT'
       OR outbox.redis_value_json IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM t_agent_risk_policy_effective AS effective
           WHERE effective.last_outbox_id = outbox.outbox_id
             AND effective.policy_key = outbox.policy_key
             AND effective.policy_id = outbox.policy_id
             AND effective.policy_version = outbox.policy_version
             AND effective.redis_value_json = outbox.redis_value_json
       )
);

COMMIT;

ALTER TABLE t_agent_risk_policy
    MODIFY COLUMN idempotency_key VARCHAR(512) NOT NULL;

ALTER TABLE t_agent_risk_policy
    MODIFY COLUMN policy_version BIGINT NOT NULL;

ALTER TABLE t_agent_risk_policy
    ADD CONSTRAINT uk_agent_risk_policy_idempotency UNIQUE (idempotency_key);

ALTER TABLE t_agent_risk_policy
    ADD CONSTRAINT uk_agent_risk_policy_key_version UNIQUE (policy_key, policy_version);

INSERT INTO t_agent_schema_migration_history (
    version,
    description,
    script_name,
    applied_by
)
VALUES (
    'V20260712',
    'Risk policy history constraints and legacy effective/outbox backfill',
    'V20260712__risk_policy_history_constraints_and_backfill.sql',
    CURRENT_USER()
);
