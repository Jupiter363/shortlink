-- Cumulative stop facts survive revisions; they are neither a semantic cache nor a retry counter.
CREATE TABLE campaign_exploration_progress (
    run_id VARCHAR(96) NOT NULL,
    owner_hash CHAR(64) NOT NULL,
    stop_events BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT ck_exploration_progress_count CHECK (stop_events >= 0)
);

-- Immutable per-MODEL proposal receipts. Authoritative requests/outputs remain in existing stores.
CREATE TABLE campaign_exploration_proposal (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    turn_index BIGINT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    model_child_id VARCHAR(96) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    call_id VARCHAR(96),
    source_turn_index BIGINT,
    source_call_id VARCHAR(96),
    outputs_hash CHAR(64),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, step_id, turn_index),
    CONSTRAINT fk_exploration_proposal_progress FOREIGN KEY (run_id) REFERENCES campaign_exploration_progress (run_id),
    CONSTRAINT fk_exploration_proposal_turn FOREIGN KEY (run_id, revision, step_id, turn_index)
        REFERENCES campaign_exploration_turn (run_id, revision, step_id, turn_index),
    CONSTRAINT fk_exploration_proposal_model FOREIGN KEY (run_id, revision, model_child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT fk_exploration_proposal_call FOREIGN KEY (run_id, revision, call_id)
        REFERENCES campaign_exploration_call (run_id, revision, call_id),
    CONSTRAINT fk_exploration_proposal_source FOREIGN KEY (run_id, revision, source_call_id)
        REFERENCES campaign_exploration_call (run_id, revision, call_id),
    CONSTRAINT ck_exploration_proposal_kind CHECK (
        (decision='ADMITTED' AND call_id IS NOT NULL AND source_turn_index IS NULL AND source_call_id IS NULL AND outputs_hash IS NULL)
        OR (decision='STOP' AND call_id IS NULL AND source_turn_index IS NOT NULL AND source_turn_index < turn_index
            AND source_call_id IS NOT NULL AND outputs_hash IS NOT NULL)),
    CONSTRAINT ck_exploration_proposal_turn CHECK (revision > 0 AND turn_index > 0)
);
CREATE INDEX ix_exploration_proposal_fingerprint
    ON campaign_exploration_proposal (run_id, revision, step_id, fingerprint, decision, turn_index);
