-- One physical statistics job interlock. Remote execution and pages remain at the original child.
CREATE TABLE campaign_statistics_job_binding (
    binding_id VARCHAR(96) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    producer_run_id VARCHAR(96) NOT NULL,
    producer_revision INT NOT NULL,
    producer_child_id VARCHAR(96) NOT NULL,
    producer_definition_hash CHAR(64) NOT NULL,
    action_id VARCHAR(96) NOT NULL,
    executor_kind VARCHAR(16) NOT NULL,
    executor_name VARCHAR(128) NOT NULL,
    executor_version VARCHAR(128) NOT NULL,
    output_contract_ref VARCHAR(256) NOT NULL,
    job_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    artifact_id VARCHAR(96) NOT NULL,
    scope_ref VARCHAR(256) NOT NULL,
    periods_ref VARCHAR(256) NOT NULL,
    expires_at BIGINT NOT NULL,
    binding_version BIGINT NOT NULL,
    cancel_intent VARCHAR(16) NOT NULL,
    local_only BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_campaign_statistics_physical_job UNIQUE (tenant_id,subject_name,job_id),
    CONSTRAINT uq_campaign_statistics_original_child UNIQUE (producer_run_id,producer_revision,producer_child_id),
    CONSTRAINT ck_campaign_statistics_cancel CHECK (cancel_intent IN ('NONE','REQUESTED','CONFIRMED')),
    CONSTRAINT ck_campaign_statistics_binding_version CHECK (binding_version > 0 AND expires_at > 0),
    CONSTRAINT fk_campaign_statistics_binding_child FOREIGN KEY (producer_run_id,producer_revision,producer_child_id)
        REFERENCES campaign_child_ledger (run_id,revision,child_id)
);

CREATE TABLE campaign_statistics_consumer (
    consumer_id VARCHAR(96) NOT NULL PRIMARY KEY,
    binding_id VARCHAR(96) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    expectation_json TEXT NOT NULL,
    expectation_hash CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    retired_at BIGINT,
    CONSTRAINT uq_campaign_statistics_consumer_slot UNIQUE (binding_id,run_id,revision,step_id),
    CONSTRAINT fk_campaign_statistics_consumer_binding FOREIGN KEY (binding_id)
        REFERENCES campaign_statistics_job_binding (binding_id),
    CONSTRAINT fk_campaign_statistics_consumer_run FOREIGN KEY (run_id,revision)
        REFERENCES campaign_run_ledger (run_id,revision),
    CONSTRAINT ck_campaign_statistics_consumer_active CHECK ((active=TRUE AND retired_at IS NULL) OR (active=FALSE AND retired_at IS NOT NULL))
);

-- Exact adopted callback identity, not a new producer or a copy of the remote request/result.
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_binding_id VARCHAR(96);
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_consumer_id VARCHAR(96);
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_binding_version BIGINT;
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_run_id VARCHAR(96);
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_revision INT;
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_run_version BIGINT;
ALTER TABLE campaign_child_ledger ADD COLUMN adoption_run_token VARCHAR(96);
ALTER TABLE campaign_child_ledger ADD CONSTRAINT ck_campaign_child_adoption CHECK (
    (adoption_binding_id IS NULL AND adoption_consumer_id IS NULL AND adoption_binding_version IS NULL
        AND adoption_run_id IS NULL AND adoption_revision IS NULL AND adoption_run_version IS NULL AND adoption_run_token IS NULL)
    OR (adoption_binding_id IS NOT NULL AND adoption_consumer_id IS NOT NULL AND adoption_binding_version IS NOT NULL
        AND adoption_run_id IS NOT NULL AND adoption_revision IS NOT NULL AND adoption_run_version IS NOT NULL AND adoption_run_token IS NOT NULL)
);
