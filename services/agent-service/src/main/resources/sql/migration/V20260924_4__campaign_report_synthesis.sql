-- One native narrative attempt per frozen plan and exact evidence snapshot.
-- Unknown provider outcomes are retained; neither timeout nor process restart permits redispatch.
CREATE TABLE campaign_report_synthesis (
    synthesis_id CHAR(64) NOT NULL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    synthesis_state VARCHAR(24) NOT NULL,
    callback_active BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_id VARCHAR(36),
    prompt_hash CHAR(64),
    response_json LONGTEXT,
    response_hash CHAR(64),
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    CONSTRAINT uq_campaign_synthesis_snapshot UNIQUE (run_id, revision, evidence_hash),
    CONSTRAINT fk_campaign_synthesis_run FOREIGN KEY (run_id, revision)
        REFERENCES campaign_run_ledger (run_id, revision),
    CONSTRAINT ck_campaign_synthesis_state CHECK
        (synthesis_state IN ('PREPARED', 'DISPATCHING', 'READY', 'UNKNOWN'))
);
