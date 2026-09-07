# Analytics runtime and contract

The implementation is split across `event-contract`, `analytics-flink`, `analytics-worker`, and `shortlink-analytics-api`. Java 17 is required. The business database is never used for analytics leases, archive progress, build manifests, or result snapshots.

## Processes

1. Provision Kafka raw topics with broker `LogAppendTime`, bounded message sizes and retention. Both `shortlink.click.raw.v1` and `shortlink.gateway.request.v1` belong in the source inventory. The broker timestamp is transport receipt time; payload `occurredAt` remains the event time.
2. Apply `deploy/mysql/002-analytics-control-schema.sql` to an explicitly selected **analytics control** database. Apply the ClickHouse SQL files to a newly provisioned analytics database. The replicated template requires an existing cluster, Keeper, and `{shard}` / `{replica}` macros.
3. Start the Worker, initialize recovery through Command's durable pause fence, let its independent raw consumer establish source coverage, and activate only after the recovery proofs pass. The process does not initialize an epoch at startup.
4. Run `AnalyticsFlinkJob` on Flink 1.20.3 with Kafka connector 3.4.0-1.20 and client 3.9.0. Required arguments: `--bootstrap`, `--build-id`, `--recovery-epoch`, `--checkpoint-uri`. Set `ANALYTICS_HASH_KEY` to the same immutable secret as the Worker. Checkpoints and the unique transaction prefix are required for Kafka exactly-once output. A deployment must preserve a build's checkpoint identity; rebuilding from an unrelated state requires a new build.
5. Run ClickHouse's official Kafka Connect sink 1.4.0 using `deploy/clickhouse/connect-config.json`. The three derived topics map to the camel-case landing tables and then to query tables through materialized views. The connector uses `read_committed`, synchronous inserts, `reportInsertedOffsets=true`, and at-least-once delivery. Receipt identity and explicit result versions handle repeat inserts; ClickHouse merges are never the correctness boundary.
6. Start the Analytics API and route Admin's authorized calls to it. Agent reads the same API through Admin; there is no per-click Agent invocation or Agent Kafka consumer.

## Configuration

Both Spring applications require a control-database `spring.datasource.*`, `analytics.internal-token` (at least 24 characters), ClickHouse endpoint/user/password/database, and the Command URL. Worker additionally requires `analytics.kafka.bootstrap`, `analytics.hash-key` (at least 32 bytes), and `analytics.s3.endpoint/access-key/secret-key/bucket`. API requires `analytics.worker.url`.

`analytics.producer-quality.urls` is the same explicit Redirect instance roster in Worker and API. Worker polls each instance's authenticated `/internal/v1/events/quality`, preserving instance identity, start time, and long counters. Missing/stale/restarted/unsettled observations are `UNKNOWN`; failed or rejected deliveries are `DEGRADED`. `NORMAL` requires actual covered counter evidence. A missing GeoIP dataset is separately exposed through `missingMetrics`; no location is invented.

The identity key is immutable for `detail-v1`. Archive envelopes include a key fingerprint and frozen interpretation. Rebuild rejects a mismatched key instead of silently splitting UV identities. Retrying or replaying an event retains `eventId` and its payload. New Kafka receipt coordinates remain separate from the logical event identity.

Click `eventId` and request-result `decisionId` use `EventIdentity.bind(occurredAt, suffix)`: `v1:<exact decimal UTC milliseconds>:<controlled producer suffix>`. The suffix is 1–256 printable ASCII characters; producers issue it once and preserve the complete serialized event across retries. Bare legacy IDs and time-prefix mismatches are quarantined as `INVALID_EVENT_IDENTITY` by both Flink and archive intake. Validation version is `broker-time-v2`. An ID with a forged event time cannot enter another window; the legitimate original remains countable, while conflicting behavior at the same bound time is still removed by payload-conflict deduplication. This is a coordinated first-release protocol change: v1 validation archives are rejected, not silently reinterpreted, and all producers and consumers must use the same release in a fresh environment.

## HTTP

Every endpoint below requires `X-Internal-Token`; empty defaults are rejected.

* `POST /internal/analytics/v1/query` accepts `QueryRequest`: tenant, username subject, auth version, either group or up to 500 link IDs, a half-open millisecond range of at most seven days, optional named `2h/24h/7d` windows, end policy, snapshot and cursor. `queryKind` is `METRICS`, `ACCESS_RECORDS`, or `ACTIVE_LINKS`.
* `POST /internal/analytics/v1/worker/readiness` returns the externally verified recovery epoch and ready state.
* `POST /internal/analytics/v1/worker/coverage` takes `startInclusive` and returns the two raw source cuts, expected receipt counts, and the broker-read-committed head observation time.
* `POST /internal/analytics/v1/worker/rebuild` submits an aligned window range after online admission closes. `GET .../rebuild/{id}` reads its durable status.
* `POST /internal/analytics/v1/worker/recovery/begin` first pauses Command and persists its monotonic `gateFence`, then creates a fresh epoch in object storage before updating the control database. Restart the old archive/repair process after a recovery; it intentionally keeps its original epoch and stays fenced.
* `POST .../recovery/reconcile` verifies the object catalog and raw content and requests missing canonical windows. `POST .../recovery/activate` passes the captured fence back to Command only after source and publication checks. It accepts no caller-supplied readiness Boolean.

StatsEnvelope is in `Result.data`. All count fields are long. `metrics` contains group-level distinct aggregates; `items` contains link/window metrics or the requested record/candidate rows; `meta` contains the fixed request range, effective end, snapshot, manifest/source-cut references, dataset/metric versions, recovery epoch, freshness, completeness, provisional flag, approximation algorithms, collection quality, and missing features. Business zero is returned only with its accompanying coverage state; unavailability is an error, never an empty successful envelope.

`COMMON_AVAILABLE_END` is reserved for explicit background named-window requests. Ordinary date queries keep the user's range. All link and group windows use one ClickHouse query snapshot, and the result is materialized in MySQL before pagination. Every page reauthorizes current ownership and verifies the recovery epoch. Snapshot lifetime is five minutes, maximum 10,000 access records; oversized results fail rather than truncate silently.

Live results remain provisional. They can be complete relative to a fixed raw cut only when every source partition's distinct ClickHouse receipt count matches the archive's receipt coverage. Canonical builds are selected by immutable manifest references. A replica's content fingerprint is checked when publishing and again before a new canonical query. A restored or lagging replica cannot silently supply an empty complete result.

## Recovery and retention

Archive upload precedes its database progress update, which precedes Kafka offset commit. A crash re-seeks the durable ledger. Object names include source identity, offset range, and checksum. The archive preserves original broker timestamps, raw input, validation result/version, parser/hash versions, and enrichment. Rebuild applies the source cut before logical-event deduplication, and never feeds old data to the live raw topic.

Window rebuilds carry a fixed source cut, immutable build ID, lease generation and epoch. Publication is a database transaction guarded by the live lease and epoch. A publication intent preserves the exact manifest payload for the object-storage journal. Superseded results are retained: there is deliberately no automatic deletion of referenced rebuild inputs or result versions. The live receipt table's 180-day TTL is separate from immutable snapshot/build retention; historical ranges without canonical builds are unavailable instead of querying across an expiring live range.

Recovery runs persist their catalog cursor, phase, lease generation and verified segments in the control database. The worker reads 200 catalog entries or verifies 20 raw segments per step; publication journal windows are also recovered, including empty windows. Activation requires the completed catalog proof, source topology/offset coverage, published repair jobs and the current Command gate fence. A replacement worker resumes the durable step; a previous epoch cannot publish.

Raw batches are split into bounded 4-million-character objects. The same archive-progress transaction indexes affected event windows and queues their canonical rebuilds. Rebuild first verifies offset coverage, then pages only archive segments indexed for the requested event windows, filters each original receipt by its fixed source cut, and has a ten-minute execution budget. A durable continuous-window cursor fills missed empty windows; periodic requests rebuild provisional windows when their 24-hour correction interval closes. These are resource contracts, not throughput claims.

Synchronous metric snapshots include Shanghai `daily`, 24 `hourStats`, Monday-first `weekdayStats`, browser/OS/device histograms and hash-only frequent IP/visitor rows. PV and bounded histograms are exact over canonical event identities. Daily UV/UIP use `uniqCombined64`; frequent-item counts include the `topK(...,'counts')` error field and remain approximate. Geography, network and lifetime-new-visitor classifications are explicitly missing, so an empty dimension is never evidence of zero activity. Persistent jobs under `/internal/analytics/v1/jobs` provide bounded 180-day reports and exports; see `query-jobs.md`.

Every non-health API and worker request is authenticated before body parsing, has 16 global in-flight slots with no application queue, and accepts at most 256 KiB for queries/job submission or 16 KiB for control requests. Encoded request bodies are rejected, and Tomcat upload inactivity is bounded to five seconds. Kafka TLS/SASL transport settings are loaded from the absolute file named by `KAFKA_SECURITY_PROPERTIES`; correctness and queue settings cannot be overridden by that file.

## Verification

Unit: `mvn -pl event-contract,analytics-flink,analytics-worker,shortlink-analytics-api -am test` with Java 17 and UTF-8. During parallel development, install the shared parent/event-contract first and omit `-am` for module-local checks.

Explicit component integration: `mvn -pl analytics-worker,shortlink-analytics-api -Pintegration verify`. The disposable stack uses Kafka `19092`, MySQL `13306/shortlink_analytics_control_it`, ClickHouse `18123/shortlink_analytics_it`, MinIO `19000`, and replicas `18124/18125/shortlink_replicated_it`. Apply the 002 control schema and all relevant ClickHouse schemas first. Initialize the Kafka topics using the repository integration fixture. Credentials are the isolated fixture credentials, never development database credentials.

The identity-v2 Worker fixtures create the dedicated `shortlink-analytics-identity-v2-it` bucket so a previous validation-v1 test archive cannot mix with the new interpretation. The storage test sends one legitimate time-bound click twice plus the same ID with a forged time in the next window, then archives and rebuilds both windows through real Kafka, MinIO, MySQL and ClickHouse. Its canonical assertions are original PV=1, forged-window PV=0 and one `INVALID_EVENT_IDENTITY` receipt. The API test separately retains the same-time, different-link payload-conflict and tenant-isolation assertions.

`QueryJobMySqlIntegrationTest` additionally uses a separate, initially empty control fixture so its deliberate singleton-epoch admission test cannot interfere with Worker tests. Set `SHORTLINK_QUERY_TEST_JDBC_URL` to `jdbc:mysql://127.0.0.1:3306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`, `SHORTLINK_QUERY_TEST_USER`, `SHORTLINK_QUERY_TEST_PASSWORD`, and `SHORTLINK_QUERY_TEST_ALLOW_INIT=true` only for the newly provisioned native test database. That fixture cleans its own epoch, window and job rows. Missing explicit initialization authorization fails the test instead of skipping. To run only the Docker-backed API tests, use `-Dit.test=AnalyticsSnapshotIntegrationTest,JobClickHouseIntegrationTest,ApiApplicationIntegrationTest`.

Flink's `FlinkKafkaIntegrationTest` uses a real MiniCluster, RocksDB and Kafka. Run on Linux; a missing Windows native DLL is an environment failure, never a substitute for successful integration. The verified WSL command, after copying the root/module POMs plus `analytics-flink/src` and `event-contract/src` to `/tmp/shortlink-analytics-test`, is:

```bash
cd /tmp/shortlink-analytics-test
JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 \
  /mnt/d/develop/apache-maven-3.9.4/bin/mvn -o -s settings.xml \
  -Dmaven.repo.local=/mnt/d/develop/apache-maven-3.9.4/mvn_repo \
  -pl analytics-flink -Pintegration verify -q
```

Here `settings.xml` preserves mirror ID `alimaven` for `central`, matching the existing Maven cache; the job uses host `localhost:19092`. Inside the Docker network instead, use `-Danalytics.it.bootstrap=kafka:9092`. Linux build outputs stay on the Linux filesystem so Maven does not share Windows module targets. Successful XML/text reports are preserved under `.work/verification/analytics-flink-linux`, `.work/verification/analytics-worker`, `.work/verification/shortlink-analytics-api`, and `.work/verification/event-contract` before root cleanup. The saved Linux report is the authoritative Flink integration result.

The verified identity-v2 wave contains 33 unit/component-method tests and nine explicit integration tests: eight event-contract unit tests; two Flink unit and one Linux integration test; three Worker unit and three integration tests; twenty API unit tests and five integration tests. All passed without skipped cases. Worker/API integration starts the actual production Spring profile, connects to the real dedicated MySQL/MinIO/ClickHouse components, and verifies authentication before body decoding. No throughput or end-to-end performance claim is made by these component tests.

Official references: [Flink connector compatibility](https://flink.apache.org/downloads/), [Kafka broker timestamp semantics](https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/producer/ProducerRecord.html), [ClickHouse connector](https://clickhouse.com/docs/integrations/connectors/data-ingestion/kafka/kafka-clickhouse-connect-sink), [official connector 1.4.0](https://github.com/ClickHouse/clickhouse-kafka-connect/releases/tag/v1.4.0).
