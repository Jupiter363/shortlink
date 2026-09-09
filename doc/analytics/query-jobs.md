# Persistent analytics query jobs

The Analytics API owns this job worker. It uses the existing independent analytics control MySQL database and immutable ClickHouse `rebuild_input` builds. It does not start another event consumer or recompute independently selected seven-day snapshots.

POST `/internal/analytics/v1/jobs` takes `{requestId, query}` where `query` is the existing `QueryRequest`. Only `METRICS` (daily, per-link PV/UV/UIP/denied) and `ACCESS_RECORDS` are accepted. The interval is `[startInclusive,endExclusive)`, at most 180 days. `snapshotId`, `cursor`, named windows and `COMMON_AVAILABLE_END` are rejected. Full currently authorized scope is capped at 500 links.

POST `/{jobId}/status`, `/{jobId}/cancel`, `/{jobId}/page`, and `/{jobId}/export` under `/internal/analytics/v1/jobs` take the current `{tenantId,subjectId,authVersion,pageIndex,size}`. Page index is zero based; size is fixed at 500 (last page may be shorter). All endpoints require `X-Internal-Token`. Submit, execution, publication, every status/page/export page and cancellation recheck current Command authorization and recovery epoch. A changed ownership scope rejects old results. New credentials may read a previously committed result only after their current authorization succeeds.

Admission atomically freezes all required finalized canonical manifest rows, their source cuts, build IDs, revisions, dataset/metric versions and eligible replicas. Missing windows or insufficient observation watermark return `NOT_READY`; snapshots never substitute latest builds. A common replica must prove every selected immutable build before execution. `METRICS` emits sparse rows for observed business days in `Asia/Shanghai`; zero-event days are not fabricated. Results report canonical coverage separately from UNKNOWN producer collection quality; missing country and other unavailable dimensions are not zero.

States are `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`. Result pages remain invisible until the fenced terminal commit. Recovered attempts delete uncommitted pages and rerun against the same persisted manifest. Lease tokens and owner IDs are checked while holding the job row lock for every result-page write and final publication. Cancellation increments the token. Unknown database outcomes are recovered from the job/request identity. Jobs whose lease expires can be reclaimed; three attempts are the hard limit.

Budgets: 8 active jobs globally and 2 per tenant; 128 retained jobs globally and 8 per tenant; 16 MiB frozen manifest; 64 MiB/200,000 result rows per job; 500 rows and at most 1 MiB per result page; two local workers with no queue; 5 minutes per execution; 24 hours retention. Admission reserves the full result allowance for queued/running jobs, plus actual manifest bytes, under a 1 GiB global persistent reservation. Completed jobs charge their actual result bytes and retain a separate storage slot until cleanup, so a finished download does not continue to consume an execution slot. HTTP query execution has explicit ClickHouse memory/read-byte/result limits and bounded JSON line parsing. Export uses persisted pages and bounded CSV buffers; it never reruns ClickHouse or rebuilds all rows in memory. Budget failures are explicit, never truncated successful reports.

Only operator-managed schema installation creates these tables; startup never silently initializes a control database. The integration owner must append the following SQL to `deploy/mysql/002-analytics-control-schema.sql`:

```sql
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
```

Immutable build retention must account for non-expired `analytics_query_job.manifest_json` references as well as synchronous snapshots before any future build garbage collector is enabled. Current immutable rebuild tables have no autonomous TTL. Expired jobs are removed in bounded batches, pages first, under the admission gate; running attempts are fenced before their slot can be reused. Epoch changes invalidate old jobs immediately but do not rewrite their frozen selections.
