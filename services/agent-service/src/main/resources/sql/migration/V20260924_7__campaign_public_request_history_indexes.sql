-- Keep owner-scoped history and exact-session recovery bounded on growing request ledgers.
CREATE INDEX ix_campaign_public_owner_recent
    ON campaign_public_request (tenant_id, subject_name, auth_version, created_at, request_id);
CREATE INDEX ix_campaign_public_owner_session_recent
    ON campaign_public_request (tenant_id, subject_name, auth_version, session_id, created_at, request_id);
