-- One current, version-fenced result per durable intake request. RUNNING is an unconfirmed
-- attempt after a crash, never proof that a worker remains alive or that a Goal is complete.
CREATE TABLE campaign_advance_outcome (
    request_id VARCHAR(96) NOT NULL,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    session_id VARCHAR(96) NOT NULL,
    request_key VARCHAR(256) NOT NULL,
    profile_ref VARCHAR(128) NOT NULL,
    profile_version VARCHAR(128) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    plan_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    attempt_version BIGINT NOT NULL,
    attempt_status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(32),
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT fk_campaign_advance_outcome_intake FOREIGN KEY (request_id)
        REFERENCES campaign_run_intake (request_id),
    CONSTRAINT ck_campaign_advance_outcome_version CHECK (attempt_version > 0),
    CONSTRAINT ck_campaign_advance_outcome_status CHECK (
        (attempt_status IN ('RUNNING','SUCCEEDED') AND reason_code IS NULL)
        OR (attempt_status='FAILED' AND reason_code IN
            ('ACCESS_DENIED','INVALID_PLAN','RUNTIME_UNAVAILABLE','ADVANCE_BLOCKED')))
);
