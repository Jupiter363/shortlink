-- Logical test schema from business 001 and membership 004; engine/collation syntax omitted.

CREATE TABLE t_user (
 id BIGINT NOT NULL PRIMARY KEY, username VARCHAR(64) NOT NULL,
 password VARCHAR(120) NOT NULL, real_name VARCHAR(100), phone VARCHAR(128), mail VARCHAR(512),
 auth_version BIGINT NOT NULL DEFAULT 1, disabled BOOLEAN NOT NULL DEFAULT FALSE,
 deletion_time BIGINT NOT NULL DEFAULT 0, del_flag INT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_username(username)
);

CREATE TABLE t_group (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL,
 gid VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
 link_count BIGINT NOT NULL DEFAULT 0, job_refs BIGINT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 1,
 del_flag INT NOT NULL DEFAULT 0, create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_group(tenant_id,gid), KEY ix_owner(username,del_flag)
);

CREATE TABLE t_link (
 id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, gid VARCHAR(64) NOT NULL,
 domain VARCHAR(253) NOT NULL, short_uri CHAR(9) NOT NULL,
 full_short_url VARCHAR(300) NOT NULL, origin_url VARCHAR(2048) NOT NULL,
 created_type INT NOT NULL DEFAULT 0, valid_date_type INT NOT NULL DEFAULT 0, valid_date DATETIME(3),
 enable_status INT NOT NULL DEFAULT 0, describe_text VARCHAR(1024), favicon VARCHAR(2048), title VARCHAR(512),
 target_revision BIGINT NOT NULL DEFAULT 1, metadata_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 del_flag INT NOT NULL DEFAULT 0, del_time BIGINT NOT NULL DEFAULT 0,
 create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY ix_group(tenant_id,gid,del_flag,id)
);

CREATE TABLE t_link_route (
 link_id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, current_gid VARCHAR(64) NOT NULL,
 domain_norm VARCHAR(253) NOT NULL, short_uri CHAR(9) NOT NULL,
 origin_url VARCHAR(2048) NOT NULL, route_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 expire_at DATETIME(3), route_version BIGINT NOT NULL DEFAULT 1, ownership_version BIGINT NOT NULL DEFAULT 1,
 target_revision BIGINT NOT NULL DEFAULT 1, metadata_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 created_at BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL,
 UNIQUE KEY uk_route_address(domain_norm,short_uri), KEY ix_route_group(tenant_id,current_gid,link_id),
 KEY ix_route_page(tenant_id,current_gid,route_status,created_at,link_id)
);

CREATE TABLE t_command_result (
 tenant_id BIGINT NOT NULL, command_id VARCHAR(128) NOT NULL,
 request_digest CHAR(64) NOT NULL, result_json MEDIUMTEXT NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,command_id)
);

CREATE TABLE t_outbox (
 event_id VARCHAR(64) NOT NULL PRIMARY KEY, topic VARCHAR(128) NOT NULL, event_key VARCHAR(256) NOT NULL,
 payload MEDIUMTEXT NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'READY',
 attempts INT NOT NULL DEFAULT 0, next_attempt_at BIGINT NOT NULL, lease_until BIGINT NOT NULL DEFAULT 0,
 fence BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, published_at BIGINT,
 KEY ix_outbox_due(state,next_attempt_at,lease_until)
);

CREATE TABLE t_policy_resource (
 tenant_id BIGINT NOT NULL,link_id BIGINT NOT NULL,policy_revision BIGINT NOT NULL DEFAULT 0,
 next_transition_at BIGINT, PRIMARY KEY(tenant_id,link_id), KEY ix_policy_transition(next_transition_at)
 ,rate_window_seconds INT,rate_window_lock_until BIGINT
);

CREATE TABLE t_tenant_quota (
 tenant_id BIGINT NOT NULL PRIMARY KEY, used_rows BIGINT NOT NULL DEFAULT 0,reserved_rows BIGINT NOT NULL DEFAULT 0,
 active_jobs INT NOT NULL DEFAULT 0,validation_jobs INT NOT NULL DEFAULT 0,validation_bytes BIGINT NOT NULL DEFAULT 0,
 CHECK(used_rows>=0 AND reserved_rows>=0 AND active_jobs>=0 AND validation_jobs>=0 AND validation_bytes>=0)
);

CREATE TABLE t_batch_job (
 job_id CHAR(36) NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL,username VARCHAR(64) NOT NULL,auth_version BIGINT NOT NULL,
 request_id VARCHAR(96) NOT NULL,request_digest CHAR(64) NOT NULL,gid VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL,input_kind VARCHAR(8) NOT NULL,input_json MEDIUMTEXT,
 object_bucket VARCHAR(63),object_key VARCHAR(512),object_version VARCHAR(256),declared_sha256 CHAR(64),declared_bytes BIGINT NOT NULL,
 actual_sha256 CHAR(64),actual_bytes BIGINT,parser_version VARCHAR(32) NOT NULL DEFAULT 'creation-ndjson-v1',
 total_rows BIGINT NOT NULL DEFAULT 0,valid_rows BIGINT NOT NULL DEFAULT 0,invalid_rows BIGINT NOT NULL DEFAULT 0,
 succeeded_rows BIGINT NOT NULL DEFAULT 0,failed_rows BIGINT NOT NULL DEFAULT 0,reserved_rows BIGINT NOT NULL DEFAULT 0,
 validation_held BOOLEAN NOT NULL DEFAULT TRUE,references_held BOOLEAN NOT NULL DEFAULT TRUE,
 fence BIGINT NOT NULL DEFAULT 0,lease_owner VARCHAR(64),lease_until BIGINT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL DEFAULT 0,attempts INT NOT NULL DEFAULT 0,last_error VARCHAR(512),created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,
 UNIQUE KEY uk_batch_request(tenant_id,request_id),KEY ix_batch_due(state,next_attempt_at,lease_until,tenant_id)
);

CREATE TABLE t_batch_row (
 job_id CHAR(36) NOT NULL,row_no BIGINT NOT NULL,row_digest CHAR(64) NOT NULL,
 creation_json TEXT,state VARCHAR(16) NOT NULL,link_id BIGINT,result_json TEXT,error_code VARCHAR(128),
 PRIMARY KEY(job_id,row_no),UNIQUE KEY uk_batch_link(link_id),KEY ix_batch_pending(job_id,state,row_no)
);

CREATE TABLE IF NOT EXISTS t_route_membership_control (
 namespace VARCHAR(64) NOT NULL PRIMARY KEY,
 generation VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 member_count BIGINT NOT NULL DEFAULT 0,
 mode VARCHAR(16) NOT NULL DEFAULT 'OFF',
 baseline_ready BOOLEAN NOT NULL DEFAULT FALSE,
 transition_id VARCHAR(36) NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CHECK (revision >= 0 AND member_count >= 0),
 CHECK (mode IN ('OFF','SHADOW','ENFORCE','DRAINING'))
);

CREATE TABLE IF NOT EXISTS t_route_membership (
 namespace VARCHAR(64) NOT NULL,
 generation VARCHAR(36) NOT NULL,
 member_ordinal BIGINT NOT NULL,
 registration_revision BIGINT NOT NULL,
 domain_norm VARCHAR(253) NOT NULL,
 short_uri VARCHAR(64) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (namespace,generation,domain_norm,short_uri),
 UNIQUE KEY uk_membership_ordinal (namespace,generation,member_ordinal),
 CHECK (member_ordinal > 0 AND registration_revision > 0)
);