-- P2 has exactly one producer per physical job. REQUESTED means LOCAL_ONLY consumption.
-- Future adopt/cancel/consumer/cleanup writers must coordinate through this same binding row.
-- FK pins protect the artifact, its manifest body and receipt. Future page GC must also acquire
-- this binding; this migration does not introduce a page collector or a multi-consumer protocol.
CREATE TABLE campaign_statistics_release (
    binding_id VARCHAR(96) NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(96) NOT NULL,
    subject_name VARCHAR(128) NOT NULL,
    auth_version BIGINT NOT NULL,
    producer_run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    job_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(96) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    artifact_id VARCHAR(96) NOT NULL,
    artifact_hash CHAR(64) NOT NULL,
    chain_hash CHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    binding_version BIGINT NOT NULL,
    release_state VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    confirmed_at BIGINT,
    CONSTRAINT uq_campaign_release_job UNIQUE (tenant_id, subject_name, job_id),
    CONSTRAINT uq_campaign_release_producer UNIQUE (producer_run_id, revision, child_id),
    CONSTRAINT ck_campaign_release_state CHECK (release_state IN ('REQUESTED', 'CONFIRMED')),
    CONSTRAINT ck_campaign_release_version CHECK (binding_version = 1),
    CONSTRAINT fk_campaign_release_receipt FOREIGN KEY (producer_run_id, revision, child_id)
        REFERENCES campaign_statistics_receipt (run_id, revision, child_id),
    CONSTRAINT fk_campaign_release_artifact FOREIGN KEY (artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_campaign_release_payload FOREIGN KEY (artifact_id) REFERENCES campaign_artifact_payload (artifact_id)
);
