CREATE TABLE campaign_report_lifecycle (
    report_id VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    plan_revision INT NOT NULL,
    owner_name VARCHAR(256) NOT NULL,
    capability VARCHAR(256) NOT NULL,
    manifest_json LONGTEXT NOT NULL,
    manifest_checksum CHAR(64) NOT NULL,
    evidence_retained_until BIGINT NOT NULL,
    retained_until BIGINT NOT NULL,
    reuse_expires_at BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    row_version BIGINT NOT NULL,
    reference_count INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (report_id,revision),
    CONSTRAINT uq_campaign_report_manifest UNIQUE (report_id,revision,manifest_checksum),
    CONSTRAINT ck_campaign_report_revision CHECK (revision > 0 AND plan_revision > 0),
    CONSTRAINT ck_campaign_report_status CHECK (status IN ('STAGING','READY')),
    CONSTRAINT ck_campaign_report_expiry CHECK (evidence_retained_until > 0 AND retained_until > 0 AND reuse_expires_at > 0),
    CONSTRAINT ck_campaign_report_version CHECK (row_version > 0 AND reference_count >= 0)
);

CREATE TABLE campaign_report_reference (
    report_id VARCHAR(128) NOT NULL,
    revision INT NOT NULL,
    reference_id VARCHAR(256) NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (report_id,revision,reference_id),
    CONSTRAINT fk_campaign_report_reference FOREIGN KEY (report_id,revision)
        REFERENCES campaign_report_lifecycle (report_id,revision)
);
