-- Preserve existing purpose values and make room for the narrowly authorized SYNC page read.
ALTER TABLE campaign_child_ledger MODIFY COLUMN attempt_purpose VARCHAR(32);
