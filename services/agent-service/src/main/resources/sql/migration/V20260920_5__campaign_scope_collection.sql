-- Header references the existing immutable Action definition; member pages are never copied into Graph state.
CREATE TABLE campaign_scope_collection (
    collection_id VARCHAR(96) NOT NULL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    action_id VARCHAR(96) NOT NULL,
    gid VARCHAR(64) NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    expires_at BIGINT NOT NULL,
    collection_state VARCHAR(24) NOT NULL,
    enumeration_version CHAR(64),
    next_cursor BIGINT,
    page_count INT NOT NULL,
    member_count BIGINT NOT NULL,
    artifact_id VARCHAR(96),
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_campaign_scope_binding UNIQUE (collection_id, run_id, revision),
    CONSTRAINT uq_campaign_scope_artifact UNIQUE (artifact_id),
    CONSTRAINT fk_campaign_scope_action FOREIGN KEY (run_id, revision, action_id)
        REFERENCES campaign_action_ledger (run_id, revision, action_id),
    CONSTRAINT fk_campaign_scope_artifact FOREIGN KEY (artifact_id)
        REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_campaign_scope_payload FOREIGN KEY (artifact_id)
        REFERENCES campaign_artifact_payload (artifact_id),
    CONSTRAINT ck_campaign_scope_counts CHECK (page_count >= 0 AND member_count >= 0)
);

CREATE TABLE campaign_scope_page (
    collection_id VARCHAR(96) NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    page_index INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    wire_hash CHAR(64) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    row_count INT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (collection_id, page_index),
    CONSTRAINT uq_campaign_scope_child UNIQUE (run_id, revision, child_id),
    CONSTRAINT fk_campaign_scope_page_header FOREIGN KEY (collection_id, run_id, revision)
        REFERENCES campaign_scope_collection (collection_id, run_id, revision),
    CONSTRAINT fk_campaign_scope_page_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT ck_campaign_scope_page_counts CHECK (page_index >= 0 AND row_count >= 0 AND row_count <= 500)
);
