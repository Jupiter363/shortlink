-- Native Graph checkpoints are a projection of these durable model/capability decisions.
CREATE TABLE campaign_exploration_session (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    configuration_hash CHAR(64) NOT NULL,
    original_input LONGTEXT,
    input_hash CHAR(64),
    session_status VARCHAR(24) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    current_turn BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, step_id),
    CONSTRAINT fk_exploration_session_step FOREIGN KEY (run_id, revision, step_id)
        REFERENCES campaign_step_ledger (run_id, revision, step_id),
    CONSTRAINT ck_exploration_session_status CHECK (session_status IN ('ACTIVE','WAITING','CANDIDATE','FAILED','BLOCKED')),
    CONSTRAINT ck_exploration_session_turn CHECK (current_turn > 0),
    CONSTRAINT ck_exploration_session_input CHECK ((original_input IS NULL AND input_hash IS NULL)
        OR (original_input IS NOT NULL AND input_hash IS NOT NULL))
);

CREATE TABLE campaign_exploration_turn (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    turn_index BIGINT NOT NULL,
    model_child_id VARCHAR(96) NOT NULL,
    invocation_json LONGTEXT NOT NULL,
    invocation_hash CHAR(64) NOT NULL,
    decision VARCHAR(24) NOT NULL,
    response_hash CHAR(64),
    call_id VARCHAR(96),
    call_attempt_id VARCHAR(36),
    call_attempt_version BIGINT,
    call_step_attempt_id VARCHAR(36),
    call_step_attempt_version BIGINT,
    call_run_version BIGINT,
    call_run_token VARCHAR(36),
    receipt_child_id VARCHAR(96),
    artifact_id VARCHAR(96),
    job_id VARCHAR(128),
    observation_id VARCHAR(96),
    pending_projected BOOLEAN NOT NULL,
    consumed BOOLEAN NOT NULL,
    native_acknowledged BOOLEAN NOT NULL,
    repair_counted BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, step_id, turn_index),
    CONSTRAINT uq_exploration_turn_model UNIQUE (run_id, revision, model_child_id),
    CONSTRAINT uq_exploration_turn_call UNIQUE (run_id, revision, call_id),
    CONSTRAINT fk_exploration_turn_session FOREIGN KEY (run_id, revision, step_id)
        REFERENCES campaign_exploration_session (run_id, revision, step_id),
    CONSTRAINT fk_exploration_turn_model FOREIGN KEY (run_id, revision, model_child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT fk_exploration_turn_call FOREIGN KEY (run_id, revision, call_id)
        REFERENCES campaign_exploration_call (run_id, revision, call_id),
    CONSTRAINT fk_exploration_turn_receipt FOREIGN KEY (run_id, revision, receipt_child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT ck_exploration_turn_decision CHECK (decision IN ('MODEL','TOOL','OBSERVED','REPAIR','FINAL')),
    CONSTRAINT ck_exploration_turn_index CHECK (turn_index > 0)
);
