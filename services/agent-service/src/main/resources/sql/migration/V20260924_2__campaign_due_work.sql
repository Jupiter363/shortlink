-- Scheduling observations only: these rows never grant model replay or process takeover.
CREATE TABLE campaign_due_work (
    work_id VARCHAR(256) NOT NULL,
    run_id VARCHAR(256) NOT NULL,
    work_state VARCHAR(16) NOT NULL,
    due_at BIGINT NOT NULL,
    attempt_count INT NOT NULL,
    row_version BIGINT NOT NULL,
    reason_code VARCHAR(96),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (work_id),
    CONSTRAINT ck_campaign_due_state CHECK (work_state IN ('READY','DONE','BLOCKED')),
    CONSTRAINT ck_campaign_due_attempt CHECK (attempt_count >= 0 AND row_version >= 1)
);
CREATE INDEX ix_campaign_due_scan ON campaign_due_work (work_state, due_at, work_id);
CREATE INDEX ix_campaign_due_run ON campaign_due_work (run_id);
