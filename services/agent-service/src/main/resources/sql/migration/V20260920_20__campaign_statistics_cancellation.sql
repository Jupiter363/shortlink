-- This is an operation receipt for the existing physical binding, not a second job ledger.
-- Only PREPARED can grant CANCEL once. Old REQUESTED intents enter UNKNOWN and can only GET.
CREATE TABLE campaign_statistics_cancellation (
    binding_id VARCHAR(96) NOT NULL PRIMARY KEY,
    binding_version BIGINT NOT NULL,
    operation_state VARCHAR(16) NOT NULL,
    attempt_id VARCHAR(96),
    attempt_version BIGINT NOT NULL DEFAULT 0,
    attempt_purpose VARCHAR(16),
    dispatch_run_id VARCHAR(96),
    dispatch_revision INT,
    dispatch_definition_hash CHAR(64),
    dispatch_run_version BIGINT,
    dispatch_run_token VARCHAR(96),
    callback_active BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_response_hash CHAR(64),
    observed_state VARCHAR(16),
    observed_error_code VARCHAR(64),
    observed_total_rows BIGINT,
    observed_page_count INT,
    observed_expires_at BIGINT,
    observed_status_hash CHAR(64),
    reason_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    observed_at BIGINT,
    callback_exited_at BIGINT,
    CONSTRAINT fk_campaign_statistics_cancel_binding FOREIGN KEY (binding_id)
        REFERENCES campaign_statistics_job_binding (binding_id),
    CONSTRAINT ck_campaign_statistics_cancel_operation_state CHECK
        (operation_state IN ('PREPARED','DISPATCHING','UNKNOWN','TERMINAL')),
    CONSTRAINT ck_campaign_statistics_cancel_operation_version CHECK (binding_version > 0 AND attempt_version >= 0),
    CONSTRAINT ck_campaign_statistics_cancel_operation_attempt CHECK (
        (attempt_id IS NULL AND attempt_version=0 AND attempt_purpose IS NULL AND dispatch_run_id IS NULL
            AND dispatch_revision IS NULL AND dispatch_definition_hash IS NULL AND dispatch_run_version IS NULL
            AND dispatch_run_token IS NULL AND callback_active=FALSE AND attempt_response_hash IS NULL)
        OR (attempt_id IS NOT NULL AND attempt_version>0 AND attempt_purpose IS NOT NULL
            AND attempt_purpose IN ('CANCEL','RECONCILE') AND dispatch_run_id IS NOT NULL AND dispatch_revision IS NOT NULL
            AND dispatch_definition_hash IS NOT NULL AND dispatch_run_version IS NOT NULL AND dispatch_run_token IS NOT NULL)),
    CONSTRAINT ck_campaign_statistics_cancel_operation_dispatch CHECK
        (operation_state<>'DISPATCHING' OR (callback_active=TRUE AND attempt_id IS NOT NULL)),
    CONSTRAINT ck_campaign_statistics_cancel_operation_prepared CHECK
        (operation_state<>'PREPARED' OR (attempt_version=0 AND callback_active=FALSE)),
    CONSTRAINT ck_campaign_statistics_cancel_operation_observation CHECK (
        (observed_state IS NULL AND observed_error_code IS NULL AND observed_total_rows IS NULL AND observed_page_count IS NULL
            AND observed_expires_at IS NULL AND observed_status_hash IS NULL AND observed_at IS NULL)
        OR (observed_state IS NOT NULL AND observed_state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')
            AND observed_total_rows IS NOT NULL AND observed_total_rows>=0 AND observed_page_count IS NOT NULL AND observed_page_count>=0
            AND observed_expires_at IS NOT NULL AND observed_expires_at>0 AND observed_status_hash IS NOT NULL AND observed_at IS NOT NULL)),
    CONSTRAINT ck_campaign_statistics_cancel_operation_terminal CHECK
        (operation_state<>'TERMINAL' OR (observed_state IS NOT NULL AND observed_state IN ('SUCCEEDED','FAILED','CANCELLED')))
);
