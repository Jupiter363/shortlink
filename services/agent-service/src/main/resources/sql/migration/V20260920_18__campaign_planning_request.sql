-- A single pre-Run planning call. UNKNOWN never grants another dispatch attempt.
CREATE TABLE campaign_planning_request (
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
    model_ref VARCHAR(256) NOT NULL,
    model_version VARCHAR(256) NOT NULL,
    configuration_hash CHAR(64) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    request_state VARCHAR(16) NOT NULL,
    callback_active BOOLEAN NOT NULL,
    attempt_id VARCHAR(96),
    invocation_json LONGTEXT,
    invocation_hash CHAR(64),
    response_json LONGTEXT,
    response_hash CHAR(64),
    definition_hash CHAR(64),
    intake_request_id VARCHAR(96),
    reason_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    CONSTRAINT uq_campaign_planning_run UNIQUE (run_id),
    CONSTRAINT ck_campaign_planning_auth CHECK (auth_version > 0),
    CONSTRAINT ck_campaign_planning_state CHECK (request_state IN
        ('PREPARED','DISPATCHING','READY','UNKNOWN','ACCEPTED','REJECTED')),
    CONSTRAINT ck_campaign_planning_callback CHECK
        (callback_active=FALSE OR request_state IN ('DISPATCHING','READY','UNKNOWN')),
    CONSTRAINT ck_campaign_planning_attempt CHECK
        ((request_state='PREPARED' AND attempt_id IS NULL AND invocation_hash IS NULL AND invocation_json IS NULL)
         OR (request_state<>'PREPARED' AND attempt_id IS NOT NULL AND invocation_hash IS NOT NULL AND invocation_json IS NOT NULL)),
    CONSTRAINT ck_campaign_planning_response CHECK
        ((request_state IN ('READY','ACCEPTED','REJECTED') AND response_hash IS NOT NULL AND response_json IS NOT NULL)
         OR (request_state IN ('PREPARED','DISPATCHING','UNKNOWN') AND response_hash IS NULL AND response_json IS NULL)),
    CONSTRAINT ck_campaign_planning_accept CHECK
        ((request_state='ACCEPTED' AND definition_hash IS NOT NULL AND intake_request_id IS NOT NULL)
         OR (request_state<>'ACCEPTED' AND definition_hash IS NULL AND intake_request_id IS NULL))
);
