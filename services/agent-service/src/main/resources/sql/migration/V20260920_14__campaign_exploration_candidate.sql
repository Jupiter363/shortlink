-- A server-validated candidate receipt. Source MODEL response owns all original model text.
CREATE TABLE campaign_exploration_candidate (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    assessment_id VARCHAR(96) NOT NULL,
    model_child_id VARCHAR(96) NOT NULL,
    response_hash CHAR(64) NOT NULL,
    candidate_hash CHAR(64) NOT NULL,
    registry_id VARCHAR(512) NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    assessment_json LONGTEXT NOT NULL,
    assessment_hash CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, step_id),
    CONSTRAINT uq_exploration_candidate_id UNIQUE (assessment_id),
    CONSTRAINT ck_exploration_candidate_verdict CHECK (verdict IN
        ('COMPLETE','NEEDS_INPUT','REPLAN_REQUESTED','NO_PROGRESS_REPORTED','REJECTED')),
    CONSTRAINT fk_exploration_candidate_step FOREIGN KEY (run_id, revision, step_id)
        REFERENCES campaign_step_ledger (run_id, revision, step_id),
    CONSTRAINT fk_exploration_candidate_model FOREIGN KEY (run_id, revision, model_child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id)
);
