-- A verified admitted=false receipt is separate from an unknown submission.
-- No request payload or graph state is duplicated. The original child owns the frozen wire body.
CREATE TABLE campaign_submission_deferral (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    wire_hash CHAR(64) NOT NULL,
    capacity_kind VARCHAR(24) NOT NULL,
    rejected_attempts INT NOT NULL,
    retry_not_before BIGINT NOT NULL,
    last_attempt_id VARCHAR(36) NOT NULL,
    last_attempt_version BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, child_id),
    CONSTRAINT fk_campaign_deferral_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT ck_campaign_deferral_count CHECK (rejected_attempts > 0),
    CONSTRAINT ck_campaign_deferral_attempt CHECK (last_attempt_version >= rejected_attempts),
    CONSTRAINT ck_campaign_deferral_deadline CHECK (retry_not_before > 0)
);
