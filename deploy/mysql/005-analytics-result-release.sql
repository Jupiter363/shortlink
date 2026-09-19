-- Apply once to the analytics control database after 002-analytics-control-schema.sql.
-- Existing jobs remain ineligible for managed release; their replay identity/TTL are untouched.
ALTER TABLE analytics_query_job
 ADD COLUMN release_allowed BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN result_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 ADD COLUMN released_at BIGINT NULL;

UPDATE analytics_query_job
 SET result_state = CASE
  WHEN state = 'SUCCEEDED' THEN 'AVAILABLE'
  WHEN state IN ('FAILED','CANCELLED') THEN 'UNAVAILABLE'
  ELSE 'PENDING'
 END;
