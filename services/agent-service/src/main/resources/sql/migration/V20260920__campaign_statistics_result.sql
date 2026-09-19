-- Staged statistics pages remain backend-only until the manifest and READY receipt commit.
CREATE TABLE campaign_statistics_receipt (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    artifact_id VARCHAR(96) NOT NULL,
    spec_json LONGTEXT NOT NULL,
    spec_hash CHAR(64) NOT NULL,
    snapshot_json LONGTEXT,
    metrics_json LONGTEXT,
    next_page_index INT NOT NULL,
    stored_pages INT NOT NULL,
    stored_rows BIGINT NOT NULL,
    stored_bytes BIGINT NOT NULL,
    chain_hash CHAR(64) NOT NULL,
    published BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, child_id),
    CONSTRAINT uq_campaign_statistics_artifact UNIQUE (artifact_id),
    CONSTRAINT fk_campaign_statistics_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id)
);

CREATE TABLE campaign_statistics_page (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    page_index INT NOT NULL,
    next_page_index INT,
    payload_json LONGTEXT NOT NULL,
    checksum CHAR(64) NOT NULL,
    row_count INT NOT NULL,
    byte_count BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (run_id, revision, child_id, page_index),
    CONSTRAINT fk_campaign_statistics_page_receipt FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_statistics_receipt (run_id, revision, child_id)
);
