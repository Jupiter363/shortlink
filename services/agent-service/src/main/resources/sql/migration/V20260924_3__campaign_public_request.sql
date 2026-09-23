-- Interpretation uses the same logical Run as its later accepted Plan; it creates no executable Run.
CREATE TABLE campaign_public_request (
 request_id VARCHAR(96) NOT NULL PRIMARY KEY,
 run_id VARCHAR(96) NOT NULL UNIQUE,
 tenant_id VARCHAR(96) NOT NULL,
 subject_name VARCHAR(128) NOT NULL,
 auth_version BIGINT NOT NULL,
 session_id VARCHAR(96) NOT NULL,
 request_key VARCHAR(256) NOT NULL,
 question_text LONGTEXT NOT NULL,
 question_hash CHAR(64) NOT NULL,
 created_at BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 request_state VARCHAR(24) NOT NULL,
 callback_active BOOLEAN NOT NULL DEFAULT FALSE,
 attempt_id VARCHAR(96),
 prompt_hash CHAR(64),
 response_json LONGTEXT,
 target_work_id VARCHAR(96),
 reason_code VARCHAR(128),
 CONSTRAINT ck_campaign_public_state CHECK (request_state IN
 ('PREPARED','DISPATCHING','READY','UNKNOWN','ACCEPTED','NEEDS_INPUT')),
 CONSTRAINT ck_campaign_public_auth CHECK (auth_version>0)
);
