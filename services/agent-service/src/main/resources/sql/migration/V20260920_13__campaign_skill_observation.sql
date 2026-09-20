-- Opt-in Skill receipts refer to the already sealed complete named-output set.
-- Existing reference-only tool sessions do not need to read these additive columns.
ALTER TABLE campaign_exploration_turn ADD COLUMN receipt_kind VARCHAR(16) NOT NULL DEFAULT 'ARTIFACT';
ALTER TABLE campaign_exploration_turn ADD COLUMN skill_completion_id VARCHAR(96) NULL;
ALTER TABLE campaign_exploration_turn ADD COLUMN skill_outputs_hash CHAR(64) NULL;
ALTER TABLE campaign_exploration_turn ADD CONSTRAINT ck_exploration_skill_receipt CHECK (
    (receipt_kind='ARTIFACT' AND skill_completion_id IS NULL AND skill_outputs_hash IS NULL)
    OR (receipt_kind='SKILL' AND receipt_child_id IS NULL AND artifact_id IS NULL AND job_id IS NULL
        AND ((skill_completion_id IS NULL AND skill_outputs_hash IS NULL)
             OR (skill_completion_id IS NOT NULL AND skill_outputs_hash IS NOT NULL)))
);
