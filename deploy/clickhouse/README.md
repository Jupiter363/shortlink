# Analytics dimensions and upgrade order

`001-analytics.sql` and `002-connect-landing.sql` initialize a new single-node database.
`003-replicated.sql.template` initializes the replicated schema after substituting its database and cluster placeholders.
Existing databases use `004-geo-dimensions.sql`, or the corresponding `.sql.template` for a cluster.
The migration uses `ADD COLUMN IF NOT EXISTS` and updates the existing materialized-view query; it can be repeated without rewriting historical receipts.
ClickHouse supports updating a view with a `TO` destination using [MODIFY QUERY](https://clickhouse.com/docs/reference/statements/alter/view).

The added fields are `province`, `city`, `network`, `geo_status`, and `geo_version`.
Kafka Connect landing names use `geoStatus` and `geoVersion` to match the Java JSON contract.
Missing legacy fields become `UNKNOWN`, with an empty `geo_version`; missing information is never assigned an invented region.
`network` means the IP database's ISP label, not Wi-Fi/mobile access technology.

The connector caches table definitions. Its official [`tableRefreshInterval` setting](https://github.com/ClickHouse/clickhouse-docs/blob/main/docs/integrations/data-ingestion/kafka/kafka-clickhouse-connect-sink.md)
defaults to `0`; this deployment sets it to `60` seconds. Updating ClickHouse DDL alone does not immediately
refresh an already-running task. A stale task can insert known fields such as `parserVersion` while silently
leaving added geography columns at their defaults, then commit the consumed Kafka offsets. A RUNNING task or
successful insert is therefore insufficient evidence that the new columns are being populated.
Run `python scripts/integration/component_adapters.py schema` for a read-only check of the refresh setting,
all geography table declarations, and landing-to-receipt mappings in new-install and upgrade schemas.

Deployment sequence:

1. Keep new-version producers paused while applying the additive ClickHouse migration to every configured replica.
2. Apply the updated connector configuration including `tableRefreshInterval=60`, then explicitly restart the connector **and its tasks** to discard their old table-definition cache. Do not rely on the periodic refresh for this migration boundary.
3. Deploy compatible Analytics API readers for both legacy receipt proofs and `dimensionVersion=geo-v1` proofs. Upgrade Worker and Flink with the same immutable IPv4/IPv6 XDB files and full SHA-256 values; retain the Flink checkpoint for restoration.
4. Use a bounded replay of a known raw receipt as a canary. Verify its new `geoVersion`/`geoStatus` and dimension values in both `derived_events` and `event_receipts`, using its source receipt identity. A private-IP canary must carry its nonempty database version and `NON_PUBLIC` status even though its region remains `UNKNOWN`. Only resume new producers and the remaining replays after this check passes.
5. If the stale task already consumed new-schema records, restart it first and repeat the same fixed-cut detail replay to repair those rows. Kafka offset advancement alone will not replay them. Preserve the original receipt/event identity and verify logical PV remains unchanged after deduplication.
6. For an explicitly requested historical geography rebuild, start Worker with `ANALYTICS_REBUILD_ENRICH_DIMENSIONS=true`, then use the authenticated existing `/internal/analytics/v1/worker/rebuild` endpoint with an aligned, admission-closed time range. Restore the flag to `false` after the planned rebuilds finish.

Runtime geography settings are `ANALYTICS_GEO_IPV4_XDB_PATH`, `ANALYTICS_GEO_IPV4_XDB_SHA256`,
`ANALYTICS_GEO_IPV6_XDB_PATH`, and `ANALYTICS_GEO_IPV6_XDB_SHA256`; configure all four together.
`ANALYTICS_GEO_MAX_CONCURRENCY` bounds lookup concurrency. Database files must remain immutable for each running process.

Normal rebuilds preserve the interpretation captured in the original archive. Opt-in geography rebuilds recheck
the original raw event and copy only geography after checking that identity, payload hash, privacy hashes,
validation, and other base facts have not changed. They write a new `rebuild_input` build and publish new manifests;
they do not replace source archive objects or rewrite old `event_receipts` rows.
A retry containing valid clicks from another geography database version is rejected and requires a new build.

New publication proofs preserve `n` and `digest` and add `dimensionDigest` plus `dimensionVersion=geo-v1`.
The independent dimension digest covers receipt identity, raw payload hash, validation result, country,
province, city, ISP, geography status, and geography database version. Publication requires every replica to agree,
and the number of distinct dimension records must equal the original distinct receipt count.
Legacy proofs without the dimension keys retain their original verification semantics.
