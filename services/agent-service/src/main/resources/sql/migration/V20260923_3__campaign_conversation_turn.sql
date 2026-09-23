-- Immutable user-request context. Reports and result rows remain in their existing stores.
CREATE TABLE campaign_conversation_turn (
    request_id VARCHAR(96) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    session_id VARCHAR(96) NOT NULL,
    request_key VARCHAR(256) NOT NULL,
    original_question TEXT NOT NULL,
    frozen_selection_json MEDIUMTEXT NOT NULL,
    runtime_mode VARCHAR(64) NOT NULL,
    previous_run_id VARCHAR(96),
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uk_campaign_conversation_turn_run UNIQUE (run_id),
    CONSTRAINT ck_campaign_conversation_turn_auth CHECK (auth_version > 0)
);
CREATE INDEX idx_campaign_conversation_turn_session ON campaign_conversation_turn (session_id, created_at);
