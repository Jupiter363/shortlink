-- Frozen per-run server allowance. Revisions and native checkpoint replacement do not reset it.
CREATE TABLE campaign_exploration_budget (
    run_id VARCHAR(96) NOT NULL,
    owner_hash CHAR(64) NOT NULL,
    policy_ref VARCHAR(128) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    policy_hash CHAR(64) NOT NULL,
    max_model_turns BIGINT NOT NULL,
    max_calls BIGINT NOT NULL,
    max_repairs BIGINT NOT NULL,
    max_context_bytes BIGINT NOT NULL,
    model_turns BIGINT NOT NULL,
    capability_calls BIGINT NOT NULL,
    repairs BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id),
    CONSTRAINT ck_exploration_budget_limits CHECK (max_model_turns > 0 AND max_calls > 0 AND max_repairs >= 0 AND max_context_bytes > 0),
    CONSTRAINT ck_exploration_budget_usage CHECK (model_turns >= 0 AND capability_calls >= 0 AND repairs >= 0)
);

-- One reservation per logical slot, independent of the writer, callback attempt and request text.
CREATE TABLE campaign_exploration_budget_slot (
    run_id VARCHAR(96) NOT NULL,
    slot_id CHAR(64) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    turn_index BIGINT NOT NULL,
    slot_kind VARCHAR(16) NOT NULL,
    context_bytes BIGINT,
    request_hash CHAR(64),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, slot_id),
    CONSTRAINT uq_exploration_budget_slot UNIQUE (run_id, revision, step_id, slot_kind, turn_index),
    CONSTRAINT fk_exploration_budget_slot_run FOREIGN KEY (run_id) REFERENCES campaign_exploration_budget (run_id),
    CONSTRAINT fk_exploration_budget_slot_step FOREIGN KEY (run_id, revision, step_id)
        REFERENCES campaign_step_ledger (run_id, revision, step_id),
    CONSTRAINT ck_exploration_budget_slot_kind CHECK (slot_kind IN ('MODEL','CALL','REPAIR')),
    CONSTRAINT ck_exploration_budget_slot_index CHECK (revision > 0 AND turn_index > 0),
    CONSTRAINT ck_exploration_budget_context CHECK (
        (context_bytes IS NULL AND request_hash IS NULL)
        OR (slot_kind='MODEL' AND context_bytes IS NOT NULL AND context_bytes >= 0 AND request_hash IS NOT NULL))
);

-- MODEL child is the authoritative envelope; the turn retains only its immutable hash and reference.
ALTER TABLE campaign_exploration_turn DROP COLUMN invocation_json;
