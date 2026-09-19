-- Opt-in business step ledger. No production component applies this migration automatically.
-- Outputs contain artifact IDs only, never payloads or native Graph state.
CREATE TABLE campaign_step_ledger (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    ordinal_index INT NOT NULL,
    definition_json LONGTEXT NOT NULL,
    depends_on_json LONGTEXT NOT NULL,
    allowed_outputs_json LONGTEXT NOT NULL,
    required_outputs_json LONGTEXT NOT NULL,
    specification_hash CHAR(64) NOT NULL,
    step_status VARCHAR(24) NOT NULL,
    row_version BIGINT NOT NULL,
    attempt_id VARCHAR(36),
    attempt_version BIGINT NOT NULL,
    dispatch_run_version BIGINT,
    dispatch_run_token VARCHAR(36),
    callback_active BOOLEAN NOT NULL,
    reason VARCHAR(256),
    outputs_json LONGTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, step_id),
    CONSTRAINT uq_campaign_step_order UNIQUE (run_id, revision, ordinal_index),
    CONSTRAINT fk_campaign_step_run FOREIGN KEY (run_id, revision)
        REFERENCES campaign_run_ledger (run_id, revision)
);
