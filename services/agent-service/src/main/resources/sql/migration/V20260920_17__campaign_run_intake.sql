-- Opt-in trusted typed intake. The primary identity is the SHA-256 of tenant/subject/session/key.
-- Auth/profile/proposal changes under that identity conflict instead of creating another Run.
CREATE TABLE campaign_run_intake (
    request_id VARCHAR(96) NOT NULL,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    session_id VARCHAR(96) NOT NULL,
    request_key VARCHAR(256) NOT NULL,
    profile_ref VARCHAR(128) NOT NULL,
    profile_version VARCHAR(128) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    plan_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_state VARCHAR(16) NOT NULL,
    proposal_json LONGTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    frozen_at BIGINT,
    PRIMARY KEY (request_id),
    CONSTRAINT uq_campaign_run_intake_run UNIQUE (run_id),
    CONSTRAINT ck_campaign_run_intake_auth CHECK (auth_version > 0),
    CONSTRAINT ck_campaign_run_intake_revision CHECK (revision=1),
    CONSTRAINT ck_campaign_run_intake_state CHECK (
        (request_state='PENDING' AND frozen_at IS NULL)
        OR (request_state='FROZEN' AND frozen_at IS NOT NULL))
);
