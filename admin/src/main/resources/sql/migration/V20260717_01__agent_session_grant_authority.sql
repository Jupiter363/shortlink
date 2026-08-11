-- Agent authority Session Grant storage for MySQL 8.
--
-- This repository does not run Flyway or Liquibase. Apply this script once
-- with the admin datasource schema owner before enabling Session Grant mode.
-- The statements are also valid in H2 MODE=MySQL for repository integration tests.

CREATE TABLE IF NOT EXISTS t_agent_authority_schema_migration_history (
    version VARCHAR(64) NOT NULL,
    description VARCHAR(256) NOT NULL,
    script_name VARCHAR(256) NOT NULL,
    applied_by VARCHAR(256) NOT NULL,
    applied_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (version)
);

CREATE TABLE IF NOT EXISTS t_agent_session_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    owner_user_id VARCHAR(256) NOT NULL DEFAULT '',
    owner_username VARCHAR(128) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    scopes_json VARCHAR(2048) NOT NULL,
    status VARCHAR(16) NOT NULL,
    grant_version BIGINT NOT NULL,
    latest_jti VARCHAR(160) NOT NULL DEFAULT '',
    latest_token_expires_at TIMESTAMP(3),
    expires_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_session_grant_sid UNIQUE (session_id),
    KEY idx_agent_session_grant_owner (tenant_id, owner_username, status, expires_at),
    KEY idx_agent_session_grant_expiry (status, expires_at),
    KEY idx_agent_session_grant_latest_jti (latest_jti)
);

CREATE TABLE IF NOT EXISTS t_agent_token_revocation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    token_jti VARCHAR(160) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    grant_version BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    revoked_at TIMESTAMP(3) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_token_revocation_jti UNIQUE (token_jti),
    KEY idx_agent_token_revocation_session (tenant_id, session_id, revoked_at),
    KEY idx_agent_token_revocation_expiry (expires_at)
);

CREATE TABLE IF NOT EXISTS t_agent_authority_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3),
    owner_token VARCHAR(128) NOT NULL DEFAULT '',
    lease_until TIMESTAMP(3),
    published_at TIMESTAMP(3),
    last_error VARCHAR(2048) NOT NULL DEFAULT '',
    occurred_at TIMESTAMP(3) NOT NULL,
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_authority_outbox_event UNIQUE (event_id),
    CONSTRAINT uk_agent_authority_outbox_aggregate UNIQUE (
        aggregate_type,
        aggregate_id,
        aggregate_version,
        event_type
    ),
    KEY idx_agent_authority_outbox_claim (status, next_attempt_at, lease_until, id),
    KEY idx_agent_authority_outbox_aggregate (aggregate_type, aggregate_id, aggregate_version)
);

INSERT INTO t_agent_authority_schema_migration_history (
    version,
    description,
    script_name,
    applied_by
)
SELECT 'V20260717_01',
       'Agent Session Grant, token revocation and authority outbox',
       'V20260717_01__agent_session_grant_authority.sql',
       CURRENT_USER
WHERE NOT EXISTS (
    SELECT 1
    FROM t_agent_authority_schema_migration_history
    WHERE version = 'V20260717_01'
);
