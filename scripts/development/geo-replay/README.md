# Bounded geography detail replay

This development utility reads an explicit, immutable Kafka raw source cut and prepares enriched details.
It does not run a second Flink job, write raw clicks, update archives, or commit consumer offsets.
It preserves broker receipt identity, raw payload hash, event identity, privacy hashes, and business facts.
Cut schema version 2 also fixes the SHA-256 `hashKeyFingerprint`, using the same calculation as Worker archives.
Dry-run and apply reject a missing fingerprint, a legacy cut, or a changed runtime key before constructing any
Kafka client. Old cuts must be captured again; do not add a guessed fingerprint to an old evidence file.
Only the two derived topics are allowed output destinations. Analytics queries must deduplicate receipt/event
identity and deterministically prefer the pinned nonempty geography version over legacy unknown dimensions.

The default mode is **dry run**. `--apply` is required to construct a Kafka producer. Apply buffers and validates
the entire bounded input first, then sends one Kafka transaction; downstream readers must use `read_committed`.
An uncertain transaction commit is reported as `APPLY_OUTCOME_UNKNOWN`, never reported as a successful dry run.
The fixed cut and receipt digest support checking a retry; broker offsets, not event timestamps, define its scope.

## Compile and self-test

Use JDK 17. The classpath needs the current event-contract classes, the analytics-flink shaded JAR,
ip2region 3.3.7, and slf4j-api. Current builds shade ip2region into the Flink JAR; older JARs need it separately.
The self-test is entirely local. It includes real CLI subprocesses with synthetic keys and no XDB/security
configuration: capture-key A / runtime-key B must fail for dry-run and apply, and a legacy cut must fail,
with no producer created, no acknowledged derived records, and zero raw/archive writes or offset commits.
These rejection tests do not connect to Kafka or use service credentials.

```powershell
$repo = (Get-Location).Path
$m2 = 'D:\develop\apache-maven-3.9.4\mvn_repo'
$cp = "$repo\libraries\event-contract\target\classes;$repo\jobs\analytics-flink\target\analytics-flink-1.0-SNAPSHOT.jar;$m2\org\lionsoul\ip2region\3.3.7\ip2region-3.3.7.jar;$m2\org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar"
& ./scripts/development/geo-replay/compile.ps1 -ClassPath $cp
$runtime = "$repo\.work\geo-replay\classes;$cp"
```

## Capture, review, dry-run, apply

Supply the same environment as the configured Worker: `ANALYTICS_HASH_KEY`, the four
`ANALYTICS_GEO_IPV4_XDB_PATH/_SHA256`, `ANALYTICS_GEO_IPV6_XDB_PATH/_SHA256` variables,
and optional `KAFKA_SECURITY_PROPERTIES`. Credentials are never command-line parameters or report fields.
Capture must use a trusted Worker's actual runtime configuration. The cut's fingerprint detects later
environment changes; it does not independently prove that an arbitrary key used during capture is the
historical dataset's key. Verify that provenance before capture, and retain the original successful reports.

```powershell
java -cp $runtime com.jupiter.shortlink.tools.GeoReplay --bootstrap localhost:19092 --capture-cut .work/geo-replay/cut.json --report .work/geo-replay/capture.json
java -cp $runtime com.jupiter.shortlink.tools.GeoReplay --bootstrap localhost:19092 --cut .work/geo-replay/cut.json --report .work/geo-replay/dryrun.json
java -cp $runtime com.jupiter.shortlink.tools.GeoReplay --bootstrap localhost:19092 --cut .work/geo-replay/cut.json --report .work/geo-replay/apply.json --apply
```

Inside a configured container, the same classes can run with a colon-separated classpath containing the mounted
classes directory, the new Flink JAR and `/opt/flink/lib/*`. Use that network's Kafka bootstrap address and XDB paths.

Capture uses the broker's cluster ID, topic IDs, partitions, earliest offsets and fixed latest offsets,
plus the trusted runtime key's fingerprint and the pinned geography version.
The JSON can be narrowed to explicit start/end ranges before dry-run; its SHA-256 is recorded in each run report.
Metadata capture reports `replayRequiresNarrowerCut` when the full retained offset span exceeds the selected
replay budget. Dry-run/apply always enforce that budget; capture does not read those event payloads.
Changed topic identity or expired retention rejects the run. Reports and cut files use create-new semantics;
choose new names for each invocation to preserve evidence.

Default limits: 5,000 source offsets/records, 16 MiB prepared data, 128 partitions, 120 seconds.
`--max-records` accepts 1–10,000 and `--deadline-seconds` accepts 10–300; Kafka operations and client cleanup
also have bounded transport timeouts. Control records can make the offset span exceed the number of data records.
An uncommitted raw transaction at the fixed end can cause a timeout; the tool does not bypass `read_committed`.

Reports include the full fixed cut, geoVersion, processed/prepared counts, topic/status counts,
sorted receipt identity digest, transaction outcome, and zero raw/archive writes and consumer offset commits.
They never include raw IP, raw payloads, cookies or API credentials. Invalid input events are counted and skipped.
This is a detail replay; it does not replace canonical manifests. Historical canonical geography still uses the
explicit Worker immutable-build replay.
