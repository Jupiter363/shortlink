-- Opt-in process ownership and exact-callback recovery audit. Existing ledgers are unchanged.
-- Writer ownership is immutable; an absent owner never proves that a process has died.
CREATE TABLE campaign_run_owner (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    run_version BIGINT NOT NULL,
    advance_token VARCHAR(36) NOT NULL,
    instance_id VARCHAR(96) NOT NULL,
    process_domain VARCHAR(256) NOT NULL,
    pid BIGINT NOT NULL,
    started_at_millis BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, run_version, advance_token),
    CONSTRAINT uq_campaign_run_owner_version UNIQUE (run_id, revision, run_version),
    CONSTRAINT fk_campaign_owner_run FOREIGN KEY (run_id, revision)
        REFERENCES campaign_run_ledger (run_id, revision)
);

CREATE TABLE campaign_callback_recovery (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    callback_kind VARCHAR(16) NOT NULL,
    callback_id VARCHAR(96) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    attempt_version BIGINT NOT NULL,
    writer_version BIGINT NOT NULL,
    writer_token VARCHAR(36) NOT NULL,
    owner_instance_id VARCHAR(96) NOT NULL,
    recovery_instance_id VARCHAR(96) NOT NULL,
    proof_code VARCHAR(128) NOT NULL,
    recovered_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, callback_kind, callback_id, attempt_id, attempt_version),
    CONSTRAINT fk_campaign_recovery_owner FOREIGN KEY (run_id, revision, writer_version, writer_token)
        REFERENCES campaign_run_owner (run_id, revision, run_version, advance_token)
);
