-- Explicitly enabled only by the capacity-aware Skill invocation store.
ALTER TABLE campaign_skill_invocation DROP CONSTRAINT ck_skill_invocation_state;
ALTER TABLE campaign_skill_invocation ADD CONSTRAINT ck_skill_invocation_state
    CHECK (invocation_state IN ('RUNNING','WAITING','DEFERRED','COMPLETED'));

ALTER TABLE campaign_skill_wait_child ADD COLUMN dependency_kind VARCHAR(16) NOT NULL DEFAULT 'JOB_READY';
ALTER TABLE campaign_skill_wait_child ADD COLUMN capacity_kind VARCHAR(24);
ALTER TABLE campaign_skill_wait_child ADD COLUMN rejected_attempts INT;
ALTER TABLE campaign_skill_wait_child ADD COLUMN retry_not_before BIGINT;
ALTER TABLE campaign_skill_wait_child ADD CONSTRAINT ck_skill_wait_dependency CHECK (
    (dependency_kind='JOB_READY' AND capacity_kind IS NULL AND rejected_attempts IS NULL AND retry_not_before IS NULL)
    OR (dependency_kind='CAPACITY_DUE' AND child_mode='ASYNC' AND receipt_state='PREPARED' AND job_id IS NULL
        AND artifact_id IS NULL AND capacity_kind IS NOT NULL AND rejected_attempts IS NOT NULL AND retry_not_before IS NOT NULL
        AND capacity_kind IN ('ACTIVE_EXECUTION','RESULT_STORAGE','RECOVERY_IDENTITY')
        AND rejected_attempts > 0 AND retry_not_before > 0));
