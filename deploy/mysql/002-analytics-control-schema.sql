-- Dedicated analytics control database. Run only against a new explicitly selected database.
CREATE TABLE IF NOT EXISTS analytics_epoch (
 singleton TINYINT PRIMARY KEY, recovery_epoch VARCHAR(64) NOT NULL, mode VARCHAR(24) NOT NULL,
 command_ack BOOLEAN NOT NULL DEFAULT FALSE, gate_fence BIGINT NOT NULL DEFAULT 0, updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
-- Epoch must be initialized by an explicit operator operation, never reset by startup.
CREATE TABLE IF NOT EXISTS analytics_archive_progress (
 cluster_id VARCHAR(128) NOT NULL, topic_id VARCHAR(128) NOT NULL, topic VARCHAR(160) NOT NULL,
 partition_id INT NOT NULL, next_offset BIGINT NOT NULL, recovery_epoch VARCHAR(64) NOT NULL, observed_at BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(cluster_id,topic_id,partition_id)
);
CREATE TABLE IF NOT EXISTS analytics_archive_segment (
 segment_id CHAR(64) PRIMARY KEY, cluster_id VARCHAR(128) NOT NULL, topic_id VARCHAR(128) NOT NULL,
 topic VARCHAR(160) NOT NULL, partition_id INT NOT NULL, start_offset BIGINT NOT NULL, end_offset BIGINT NOT NULL,
 object_key VARCHAR(768) NOT NULL, checksum CHAR(64) NOT NULL, record_count INT NOT NULL,
 min_received_at BIGINT NOT NULL, max_received_at BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uq_archive_range(cluster_id,topic_id,partition_id,start_offset,end_offset),
 KEY ix_archive_cut(cluster_id,topic_id,partition_id,start_offset)
);
CREATE TABLE IF NOT EXISTS analytics_rebuild_job (
 job_id VARCHAR(64) PRIMARY KEY, recovery_epoch VARCHAR(64) NOT NULL,
 source_cut LONGTEXT NOT NULL, source_observed_at BIGINT NOT NULL DEFAULT 0, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL, lease_token BIGINT NOT NULL DEFAULT 0, lease_owner VARCHAR(64),
 lease_until TIMESTAMP(3) NULL, build_id VARCHAR(64) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 error_code VARCHAR(128), created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY ix_jobs(status,lease_until)
);
CREATE TABLE IF NOT EXISTS analytics_manifest (
 window_start BIGINT PRIMARY KEY, recovery_epoch VARCHAR(64) NOT NULL,
 build_id VARCHAR(64) NOT NULL, manifest_revision BIGINT NOT NULL,
 source_cut LONGTEXT NOT NULL, source_observed_at BIGINT NOT NULL DEFAULT 0, metric_version VARCHAR(64) NOT NULL,
 dataset_version VARCHAR(64) NOT NULL, finalized BOOLEAN NOT NULL,
 replica_ids TEXT NOT NULL, coverage_proof LONGTEXT NOT NULL, published_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS analytics_publication_intent (
 intent_id VARCHAR(64) PRIMARY KEY, recovery_epoch VARCHAR(64) NOT NULL,
 window_start BIGINT NOT NULL, manifest_revision BIGINT NOT NULL, status VARCHAR(24) NOT NULL, manifest_payload LONGTEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS analytics_snapshot (
 snapshot_id VARCHAR(64) PRIMARY KEY, recovery_epoch VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL, ownership_version VARCHAR(256) NOT NULL,
 request_hash CHAR(64) NOT NULL, result_json LONGTEXT NOT NULL, expires_at TIMESTAMP(3) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), KEY ix_snapshot_expiry(expires_at)
);
CREATE TABLE IF NOT EXISTS analytics_repair_request (
 window_start BIGINT PRIMARY KEY, generation BIGINT NOT NULL, required_cut LONGTEXT,
 requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS analytics_source_quality (
 endpoint_id CHAR(64) NOT NULL, producer_instance_id VARCHAR(128) NOT NULL, lane VARCHAR(24) NOT NULL,
 started_at BIGINT NOT NULL, observed_at BIGINT NOT NULL, attempted BIGINT NOT NULL, delivered BIGINT NOT NULL,
 failed BIGINT NOT NULL, rejected BIGINT NOT NULL, pending BIGINT NOT NULL,
 PRIMARY KEY(endpoint_id,producer_instance_id,lane,observed_at), KEY ix_quality_latest(endpoint_id,lane,observed_at)
);
CREATE TABLE IF NOT EXISTS analytics_recovery_run (
 recovery_epoch VARCHAR(64) PRIMARY KEY, status VARCHAR(24) NOT NULL, phase VARCHAR(24) NOT NULL,
 scan_cursor VARCHAR(1024), lease_owner VARCHAR(64), lease_token BIGINT NOT NULL DEFAULT 0,
 lease_until TIMESTAMP(3), failures INT NOT NULL DEFAULT 0, verified_segments BIGINT NOT NULL DEFAULT 0,
 error_code VARCHAR(128), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS analytics_recovery_catalog (
 recovery_epoch VARCHAR(64) NOT NULL, segment_id CHAR(64) NOT NULL, cluster_id VARCHAR(128) NOT NULL,
 topic_id VARCHAR(128) NOT NULL, partition_id INT NOT NULL, start_offset BIGINT NOT NULL, end_offset BIGINT NOT NULL,
 segment_payload LONGTEXT NOT NULL, verified BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(recovery_epoch,segment_id),
 KEY ix_recovery_order(recovery_epoch,verified,cluster_id,topic_id,partition_id,start_offset,end_offset)
);
CREATE TABLE IF NOT EXISTS analytics_archive_window (
 window_start BIGINT NOT NULL, segment_id CHAR(64) NOT NULL,
 PRIMARY KEY(window_start,segment_id), KEY ix_archive_window_segment(segment_id,window_start)
);
CREATE TABLE IF NOT EXISTS analytics_window_schedule (
 singleton TINYINT PRIMARY KEY, next_window BIGINT NOT NULL, recovery_epoch VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics_query_gate (
 singleton TINYINT PRIMARY KEY
);
INSERT IGNORE INTO analytics_query_gate(singleton) VALUES(1);
CREATE TABLE IF NOT EXISTS analytics_query_job (
 job_id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(128) NOT NULL, subject_id VARCHAR(128) NOT NULL,
 request_id VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, request_json TEXT NOT NULL,
 recovery_epoch VARCHAR(64) NOT NULL, ownership_version VARCHAR(256) NOT NULL,
 manifest_json LONGTEXT NOT NULL, manifest_hash CHAR(64) NOT NULL, state VARCHAR(24) NOT NULL,
 lease_owner VARCHAR(64), lease_token BIGINT NOT NULL DEFAULT 0,
 lease_until BIGINT NOT NULL DEFAULT 0, attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL DEFAULT 0, row_count BIGINT NOT NULL DEFAULT 0,
 byte_count BIGINT NOT NULL DEFAULT 0, page_count INT NOT NULL DEFAULT 0,
 error_code VARCHAR(128), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 UNIQUE KEY uq_query_request(tenant_id,subject_id,request_id),
 KEY ix_query_due(state,next_attempt_at,lease_until), KEY ix_query_expiry(expires_at)
);
CREATE TABLE IF NOT EXISTS analytics_query_page (
 job_id VARCHAR(64) NOT NULL, lease_token BIGINT NOT NULL,
 page_index INT NOT NULL, payload_json MEDIUMTEXT NOT NULL,
 PRIMARY KEY(job_id,lease_token,page_index)
);
