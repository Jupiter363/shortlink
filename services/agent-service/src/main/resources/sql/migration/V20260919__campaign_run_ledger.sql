-- P1b opt-in business ledger. This migration is not enabled by a production component.
-- Native Graph checkpoints are deliberately not stored in these tables.
CREATE TABLE campaign_run_ledger (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    session_id VARCHAR(96) NOT NULL,
    plan_id VARCHAR(96) NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    run_status VARCHAR(24) NOT NULL,
    row_version BIGINT NOT NULL,
    advance_token VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision)
);

CREATE TABLE campaign_action_ledger (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    action_id VARCHAR(96) NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    executor_kind VARCHAR(16) NOT NULL,
    executor_name VARCHAR(128) NOT NULL,
    executor_version VARCHAR(128) NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, action_id),
    CONSTRAINT fk_campaign_action_run FOREIGN KEY (run_id, revision)
        REFERENCES campaign_run_ledger (run_id, revision)
);

CREATE TABLE campaign_child_ledger (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    action_id VARCHAR(96) NOT NULL,
    tenant_id VARCHAR(96) NOT NULL,
    child_mode VARCHAR(16) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    wire_method VARCHAR(8) NOT NULL,
    wire_path VARCHAR(2048) NOT NULL,
    wire_hash CHAR(64) NOT NULL,
    wire_body LONGTEXT NOT NULL,
    child_state VARCHAR(24) NOT NULL,
    job_id VARCHAR(128),
    artifact_id VARCHAR(96),
    attempt_id VARCHAR(36),
    attempt_version BIGINT NOT NULL,
    attempt_purpose VARCHAR(16),
    dispatch_run_version BIGINT,
    dispatch_run_token VARCHAR(36),
    callback_active BOOLEAN NOT NULL,
    unresolved_reason VARCHAR(40),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, child_id),
    CONSTRAINT uq_campaign_child_request UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_campaign_child_action FOREIGN KEY (run_id, revision, action_id)
        REFERENCES campaign_action_ledger (run_id, revision, action_id)
);

CREATE TABLE campaign_artifact (
    artifact_id VARCHAR(96) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    plan_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    action_id VARCHAR(96) NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    executor_version VARCHAR(128) NOT NULL,
    artifact_type VARCHAR(128) NOT NULL,
    schema_version VARCHAR(128) NOT NULL,
    scope_ref VARCHAR(256) NOT NULL,
    periods_ref VARCHAR(256) NOT NULL,
    quality_json LONGTEXT NOT NULL,
    provenance_json LONGTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_campaign_artifact_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id)
);

CREATE TABLE campaign_artifact_payload (
    artifact_id VARCHAR(96) NOT NULL PRIMARY KEY,
    payload_json LONGTEXT NOT NULL,
    CONSTRAINT fk_campaign_payload_metadata FOREIGN KEY (artifact_id)
        REFERENCES campaign_artifact (artifact_id)
);
