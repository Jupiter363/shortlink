-- Registered local calculations have frozen artifact inputs and no synthetic network request.
ALTER TABLE campaign_child_ledger MODIFY COLUMN wire_method VARCHAR(8) NULL;
ALTER TABLE campaign_child_ledger MODIFY COLUMN wire_path VARCHAR(2048) NULL;
ALTER TABLE campaign_child_ledger MODIFY COLUMN wire_hash CHAR(64) NULL;
ALTER TABLE campaign_child_ledger MODIFY COLUMN wire_body LONGTEXT NULL;
ALTER TABLE campaign_child_ledger ADD COLUMN local_invocation_json LONGTEXT NULL;
ALTER TABLE campaign_child_ledger ADD COLUMN local_invocation_hash CHAR(64) NULL;

CREATE TABLE campaign_local_output (
    run_id VARCHAR(96) NOT NULL,
    revision INT NOT NULL,
    child_id VARCHAR(96) NOT NULL,
    output_name VARCHAR(128) NOT NULL,
    artifact_id VARCHAR(96) NOT NULL,
    PRIMARY KEY (run_id, revision, child_id, output_name),
    CONSTRAINT uq_campaign_local_output_artifact UNIQUE (artifact_id),
    CONSTRAINT fk_campaign_local_output_child FOREIGN KEY (run_id, revision, child_id)
        REFERENCES campaign_child_ledger (run_id, revision, child_id),
    CONSTRAINT fk_campaign_local_output_metadata FOREIGN KEY (artifact_id)
        REFERENCES campaign_artifact (artifact_id),
    CONSTRAINT fk_campaign_local_output_payload FOREIGN KEY (artifact_id)
        REFERENCES campaign_artifact_payload (artifact_id)
);
