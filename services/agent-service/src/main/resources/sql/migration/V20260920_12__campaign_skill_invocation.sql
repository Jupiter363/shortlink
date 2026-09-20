-- Opt-in component: a callback returning is not a Skill completion.
CREATE TABLE campaign_skill_invocation (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    call_id VARCHAR(96) NOT NULL,
    call_definition_hash CHAR(64) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    invocation_state VARCHAR(24) NOT NULL,
    row_version BIGINT NOT NULL,
    running_attempt_id VARCHAR(36) NOT NULL,
    running_attempt_version BIGINT NOT NULL,
    completion_id VARCHAR(96),
    outputs_json LONGTEXT,
    outputs_hash CHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, call_id),
    CONSTRAINT ck_skill_invocation_state CHECK (invocation_state IN ('RUNNING','WAITING','COMPLETED')),
    CONSTRAINT fk_skill_invocation_call FOREIGN KEY (run_id, revision, call_id)
        REFERENCES campaign_exploration_call (run_id, revision, call_id)
);

-- Short, immutable receipt identities. Reconciliation may advance the actual child attempt.
CREATE TABLE campaign_skill_wait_child (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    call_id VARCHAR(96) NOT NULL,
    wait_version BIGINT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    spec_hash CHAR(64) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    child_mode VARCHAR(16) NOT NULL,
    job_id VARCHAR(128),
    artifact_id VARCHAR(96),
    receipt_state VARCHAR(24) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    attempt_version BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, call_id, wait_version, child_id),
    CONSTRAINT fk_skill_wait_invocation FOREIGN KEY (run_id, revision, call_id)
        REFERENCES campaign_skill_invocation (run_id, revision, call_id),
    CONSTRAINT fk_skill_wait_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id)
);

-- Preserve the completed callback's exact writer/Step identity before admitting another attempt.
CREATE TABLE campaign_skill_call_attempt (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    call_id VARCHAR(96) NOT NULL,
    attempt_id VARCHAR(36) NOT NULL,
    attempt_version BIGINT NOT NULL,
    step_attempt_id VARCHAR(36) NOT NULL,
    step_attempt_version BIGINT NOT NULL,
    dispatch_run_version BIGINT NOT NULL,
    dispatch_run_token VARCHAR(36) NOT NULL,
    returned_at BIGINT NOT NULL,
    archived_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, call_id, attempt_version),
    CONSTRAINT fk_skill_attempt_invocation FOREIGN KEY (run_id, revision, call_id)
        REFERENCES campaign_skill_invocation (run_id, revision, call_id)
);
