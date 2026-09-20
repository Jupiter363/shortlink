-- Typed result facts for one durable run revision.  Report bodies and capabilities remain in
-- their own lifecycle stores; this table keeps only a verified reference and the next action.
CREATE TABLE campaign_run_result_binding (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    report_id VARCHAR(128),
    report_revision INT,
    execution_status VARCHAR(24) NOT NULL,
    next_action_kind VARCHAR(24) NOT NULL,
    next_action_reason VARCHAR(96),
    required_inputs_json LONGTEXT NOT NULL,
    limitations_json LONGTEXT NOT NULL,
    source_row_version BIGINT NOT NULL,
    source_advance_token VARCHAR(36) NOT NULL,
    binding_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision),
    CONSTRAINT uq_campaign_run_result_report UNIQUE (report_id, report_revision),
    CONSTRAINT ck_campaign_run_result_report_ref CHECK (
        (report_id IS NULL AND report_revision IS NULL)
        OR (report_id IS NOT NULL AND report_revision IS NOT NULL AND report_revision > 0)
    ),
    CONSTRAINT ck_campaign_run_result_status CHECK (
        execution_status IN ('EMPTY','RUNNING','WAITING','SUCCEEDED','FAILED','CANCELLED','SUPERSEDED','UNKNOWN')
    ),
    CONSTRAINT ck_campaign_run_result_action CHECK (
        next_action_kind IN ('NONE','CONTINUE','WAIT','NEEDS_INPUT','REPLAN','RETRY','CANCEL')
    ),
    CONSTRAINT ck_campaign_run_result_version CHECK (
        revision > 0 AND source_row_version >= 0 AND binding_version > 0
    ),
    CONSTRAINT fk_campaign_run_result_run FOREIGN KEY (run_id, revision)
        REFERENCES campaign_run_ledger (run_id, revision)
);
