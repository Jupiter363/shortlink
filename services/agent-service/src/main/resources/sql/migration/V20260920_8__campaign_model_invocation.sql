-- Opt-in MODEL children share the existing callback, writer and process-death proof ledger.
-- Apply before enabling MODEL writers. Older binaries cannot read MODEL rows; mixed writers are unsupported.
ALTER TABLE campaign_action_ledger ADD COLUMN action_kind VARCHAR(24) NOT NULL DEFAULT 'CAPABILITY';
ALTER TABLE campaign_action_ledger MODIFY COLUMN executor_kind VARCHAR(16) NULL;
ALTER TABLE campaign_action_ledger MODIFY COLUMN executor_name VARCHAR(128) NULL;
ALTER TABLE campaign_action_ledger MODIFY COLUMN executor_version VARCHAR(128) NULL;
ALTER TABLE campaign_action_ledger ADD CONSTRAINT ck_campaign_action_kind CHECK (
    (action_kind = 'CAPABILITY' AND executor_kind IS NOT NULL AND executor_kind IN ('TOOL', 'SKILL')
        AND executor_name IS NOT NULL AND executor_version IS NOT NULL)
    OR (action_kind = 'MODEL' AND executor_kind IS NULL AND executor_name IS NULL AND executor_version IS NULL)
);

-- MODEL has a frozen model envelope, never a synthetic HTTP request or LOCAL calculation.
ALTER TABLE campaign_child_ledger ADD COLUMN model_invocation_json LONGTEXT NULL;
ALTER TABLE campaign_child_ledger ADD COLUMN model_invocation_hash CHAR(64) NULL;
ALTER TABLE campaign_child_ledger ADD CONSTRAINT ck_campaign_model_envelope CHECK (
    (child_mode = 'MODEL' AND model_invocation_json IS NOT NULL AND model_invocation_hash IS NOT NULL
        AND wire_method IS NULL AND wire_path IS NULL AND wire_body IS NULL AND wire_hash IS NULL
        AND local_invocation_json IS NULL AND local_invocation_hash IS NULL AND job_id IS NULL AND artifact_id IS NULL)
    OR (child_mode <> 'MODEL' AND model_invocation_json IS NULL AND model_invocation_hash IS NULL)
);

-- The public bounded DTO is an internal model fact, not a business-output Artifact.
CREATE TABLE campaign_model_response (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    response_json LONGTEXT NOT NULL,
    response_hash CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, child_id),
    CONSTRAINT fk_campaign_model_response_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id)
);
