-- An exploration capability callback is not a synthetic HTTP or LOCAL child.
-- Real child I/O keeps using campaign_child_ledger; this record owns the surrounding callback.
CREATE TABLE campaign_exploration_call (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    call_id VARCHAR(96) NOT NULL,
    action_id VARCHAR(96) NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    model_child_id VARCHAR(96) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    call_state VARCHAR(24) NOT NULL,
    row_version BIGINT NOT NULL,
    attempt_id VARCHAR(36),
    attempt_version BIGINT NOT NULL,
    step_attempt_id VARCHAR(36),
    step_attempt_version BIGINT,
    dispatch_run_version BIGINT,
    dispatch_run_token VARCHAR(36),
    callback_active BOOLEAN NOT NULL,
    revoked BOOLEAN NOT NULL,
    reason VARCHAR(128),
    returned_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, call_id),
    CONSTRAINT uq_exploration_call_action UNIQUE (run_id, revision, action_id),
    CONSTRAINT uq_exploration_call_model UNIQUE (run_id, revision, model_child_id, tool_call_id),
    CONSTRAINT ck_exploration_call_state CHECK (call_state IN ('PREPARED', 'RUNNING', 'RETURNED', 'UNRESOLVED')),
    CONSTRAINT fk_exploration_call_step FOREIGN KEY (run_id, revision, step_id)
        REFERENCES campaign_step_ledger (run_id, revision, step_id),
    CONSTRAINT fk_exploration_call_action FOREIGN KEY (run_id, revision, action_id)
        REFERENCES campaign_action_ledger (run_id, revision, action_id),
    CONSTRAINT fk_exploration_call_model FOREIGN KEY (run_id, revision, model_child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id)
);

-- An admitted child attempt can outlive its owning callback; retain its exact original parent fact.
ALTER TABLE campaign_child_ledger ADD COLUMN parent_call_id VARCHAR(96) NULL;
ALTER TABLE campaign_child_ledger ADD COLUMN parent_call_attempt_id VARCHAR(96) NULL;
ALTER TABLE campaign_child_ledger ADD COLUMN parent_call_attempt_version BIGINT NULL;
ALTER TABLE campaign_child_ledger ADD CONSTRAINT ck_campaign_child_parent_call CHECK (
    (parent_call_id IS NULL AND parent_call_attempt_id IS NULL AND parent_call_attempt_version IS NULL)
    OR (parent_call_id IS NOT NULL AND parent_call_attempt_id IS NOT NULL AND parent_call_attempt_version IS NOT NULL
        AND parent_call_attempt_version > 0)
);
ALTER TABLE campaign_child_ledger ADD CONSTRAINT fk_campaign_child_parent_call FOREIGN KEY (run_id, revision, parent_call_id)
    REFERENCES campaign_exploration_call (run_id, revision, call_id);
