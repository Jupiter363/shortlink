-- A bounded, immutable source-backed read index for the decline-selection LOCAL artifacts.
CREATE TABLE campaign_decline_collection (
    collection_id VARCHAR(96) NOT NULL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    step_id VARCHAR(96) NOT NULL,
    executor_version VARCHAR(128) NOT NULL,
    scope_artifact_id VARCHAR(96) NOT NULL,
    definition_json LONGTEXT NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    committed_pages INT NOT NULL,
    chain_artifact_id VARCHAR(96),
    chain_payload_hash CHAR(64),
    sealed BOOLEAN NOT NULL,
    final_child_id VARCHAR(96),
    selected_artifact_id VARCHAR(96),
    evidence_artifact_id VARCHAR(96),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_decline_selected UNIQUE (selected_artifact_id),
    CONSTRAINT uq_decline_evidence UNIQUE (evidence_artifact_id),
    CONSTRAINT fk_decline_step FOREIGN KEY (run_id, revision, step_id)
        REFERENCES campaign_step_ledger (run_id, revision, step_id),
    CONSTRAINT fk_decline_scope FOREIGN KEY (scope_artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_decline_scope_payload FOREIGN KEY (scope_artifact_id) REFERENCES campaign_artifact_payload (artifact_id),
    CONSTRAINT fk_decline_head FOREIGN KEY (chain_artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_decline_head_payload FOREIGN KEY (chain_artifact_id) REFERENCES campaign_artifact_payload (artifact_id),
    CONSTRAINT fk_decline_selected FOREIGN KEY (selected_artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_decline_selected_payload FOREIGN KEY (selected_artifact_id) REFERENCES campaign_artifact_payload (artifact_id),
    CONSTRAINT fk_decline_evidence FOREIGN KEY (evidence_artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_decline_evidence_payload FOREIGN KEY (evidence_artifact_id) REFERENCES campaign_artifact_payload (artifact_id)
);

CREATE TABLE campaign_decline_page (
    collection_id VARCHAR(96) NOT NULL,
    ordinal_index INT NOT NULL,
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    page_artifact_id VARCHAR(96) NOT NULL,
    page_payload_hash CHAR(64) NOT NULL,
    chain_artifact_id VARCHAR(96) NOT NULL,
    chain_payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY (collection_id, ordinal_index),
    CONSTRAINT uq_decline_page_child UNIQUE (collection_id, child_id),
    CONSTRAINT uq_decline_page_artifact UNIQUE (page_artifact_id),
    CONSTRAINT uq_decline_page_chain UNIQUE (chain_artifact_id),
    CONSTRAINT fk_decline_page_collection FOREIGN KEY (collection_id) REFERENCES campaign_decline_collection (collection_id),
    CONSTRAINT fk_decline_page_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT fk_decline_page_artifact FOREIGN KEY (page_artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_decline_page_payload FOREIGN KEY (page_artifact_id) REFERENCES campaign_artifact_payload (artifact_id),
    CONSTRAINT fk_decline_page_chain FOREIGN KEY (chain_artifact_id) REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_decline_page_chain_payload FOREIGN KEY (chain_artifact_id) REFERENCES campaign_artifact_payload (artifact_id)
);

CREATE TABLE campaign_decline_row (
    collection_id VARCHAR(96) NOT NULL,
    link_id BIGINT NOT NULL,
    ordinal_index INT NOT NULL,
    delta_value BIGINT NOT NULL,
    selected BOOLEAN NOT NULL,
    result_json LONGTEXT NOT NULL,
    PRIMARY KEY (collection_id, link_id),
    CONSTRAINT fk_decline_row_page FOREIGN KEY (collection_id, ordinal_index)
        REFERENCES campaign_decline_page (collection_id, ordinal_index)
);
CREATE INDEX ix_decline_selected_order ON campaign_decline_row (collection_id, selected, delta_value, link_id);
