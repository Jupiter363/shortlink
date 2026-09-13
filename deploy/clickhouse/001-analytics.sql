CREATE DATABASE IF NOT EXISTS shortlink_analytics;
CREATE TABLE IF NOT EXISTS shortlink_analytics.event_receipts (
 kind LowCardinality(String), cluster_id String, topic_id String, source_topic String,
 source_partition UInt32, source_offset UInt64, received_at Int64, timestamp_type String,
 event_id String, payload_hash FixedString(64), tenant_id String, link_id UInt64, occurred_at Int64,
 visitor_hash String, ip_hash String, browser LowCardinality(String), os LowCardinality(String),
 device LowCardinality(String), country LowCardinality(String), referer_domain String,
 request_source LowCardinality(String), decision_stage LowCardinality(String), status UInt16,
 reason String, validation_version String, validation_result String,
 dataset_version String, parser_version String, hash_version String,
 province String DEFAULT 'UNKNOWN', city String DEFAULT 'UNKNOWN', network String DEFAULT 'UNKNOWN',
 geo_status String DEFAULT 'UNKNOWN', geo_version String DEFAULT ''
) ENGINE=MergeTree PARTITION BY toYYYYMM(toDateTime(received_at/1000))
ORDER BY (source_topic,source_partition,source_offset,cluster_id,topic_id)
TTL toDateTime(received_at/1000) + INTERVAL 180 DAY;
CREATE TABLE IF NOT EXISTS shortlink_analytics.rebuild_input (
 build_id String, window_start Int64, kind LowCardinality(String), tenant_id String, link_id UInt64,
 event_id String, payload_hash FixedString(64), occurred_at Int64, received_at Int64,
 visitor_hash String, ip_hash String, browser String, os String, device String, country String, referer_domain String,
 request_source String, decision_stage String, status UInt16, validation_result String,
 receipt_id String,
 province String DEFAULT 'UNKNOWN', city String DEFAULT 'UNKNOWN', network String DEFAULT 'UNKNOWN',
 geo_status String DEFAULT 'UNKNOWN', geo_version String DEFAULT ''
) ENGINE=MergeTree PARTITION BY toYYYYMM(toDateTime(window_start/1000))
ORDER BY (build_id,tenant_id,event_id,receipt_id);
CREATE TABLE IF NOT EXISTS shortlink_analytics.window_results (
 tenant_id String, link_id UInt64, window_start Int64, window_end Int64,
 build_id String, recovery_epoch String, revision UInt64, pv UInt64, uv Float64, uip Float64,
 visitor_sketch String, ip_sketch String, metric_version String, dataset_version String
) ENGINE=MergeTree PARTITION BY toYYYYMM(toDateTime(window_start/1000))
ORDER BY (build_id,window_start,tenant_id,link_id,revision);
-- Immutable versions intentionally have no autonomous TTL: the control worker owns reference-aware cleanup.
-- Production substitutes ReplicatedMergeTree with provisioned Keeper paths and per-shard routing;
-- query service checks every configured replica's exact build coverage before publication/selection.
