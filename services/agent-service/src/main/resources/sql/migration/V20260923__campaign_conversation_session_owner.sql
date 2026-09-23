-- A client session id is usable for a durable run only after a trusted entry binds it to
-- one account. A newer auth version may replace the old binding; old Runs remain fenced by
-- their immutable Caller.authVersion and can no longer pass the current-principal resolver.
CREATE TABLE campaign_conversation_session_owner (
    session_id VARCHAR(96) NOT NULL,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    bound_at BIGINT NOT NULL,
    PRIMARY KEY (session_id),
    CONSTRAINT ck_campaign_conversation_session_auth CHECK (auth_version > 0)
);
