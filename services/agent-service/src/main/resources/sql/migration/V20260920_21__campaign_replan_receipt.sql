CREATE TABLE campaign_replan_receipt (
    receipt_id VARCHAR(96) NOT NULL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL,
    base_revision INT NOT NULL,
    candidate_revision INT NOT NULL,
    candidate_plan_hash CHAR(64) NOT NULL,
    request_json CLOB NOT NULL,
    decision VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_campaign_replan_receipt_run_revision UNIQUE (run_id, base_revision),
    CONSTRAINT ck_campaign_replan_receipt_revision CHECK (base_revision > 0 AND candidate_revision >= 0),
    CONSTRAINT ck_campaign_replan_receipt_decision CHECK (decision IN ('ACCEPTED','REJECTED'))
);
