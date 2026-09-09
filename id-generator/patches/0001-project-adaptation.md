# Controlled Segment adaptation boundary

Source lock: `86a6441d263497b9f9ee321de13422b9c63f0c06`; see `../UPSTREAM.md` and the per-file manifest. This record is written before production implementation.

## Retained source design

- `SegmentIDGenImpl.updateSegmentFromDb`: reserve an exclusive interval in an independent transaction; publish only after commit.
- `SegmentBuffer`: Current/Next, readiness and single-flight refill, switching only after Current is exhausted.
- `getIdFromSegmentBuffer`: local allocation and asynchronous next-segment preparation.
- Segment consumption-time feedback: bounded doubling/halving for future requests only.

## Necessary adaptations (project code owns their correctness)

- Replace upstream per-ID `getAndIncrement`, read/write-lock and mutable slot reset with one short, deadline-bounded allocation mutex. A chunk consumes `min(requested, remaining)` in one cursor transition; nextId delegates to the same take primitive. New segment objects are never reset/reused, preventing delayed references from consuming a later generation.
- Replace `synchronized(buffer)` initialization that performs DB I/O, unbounded executor maximum size, busy-spin waiting and unmanaged scheduled tag refresh with one bounded refill executor and a deadline-bound condition. Cold load, refill and all DB calls occur outside the allocation mutex; submission rejection, failure and closure complete the refill state.
- Fixed namespace `shortlink_global`; no tag scanning, dynamic tag creation or public tag argument. Values are 1 through 2^52-1; the stored exclusive upper bound starts at 1.
- Replace MyBatis DAO plumbing with native JDBC/DataSource. A locked read plus guarded UPDATE and SELECT run on an independent connection/transaction, returning the actual shrunken tail interval. Unknown commit outcomes never publish that candidate interval.
- Dynamic step has explicit enable/disable, min/max, separate fast/slow thresholds, consecutive-sample hysteresis and cooldown; only subsequent DB requests change.
- Codec is independent project code: a versioned fixed 52-bit Feistel permutation and fixed-width Base62. It is obfuscation, not authorization or an encryption promise.

These changes touch the upstream concurrency core, not merely an API wrapper. The retained reference files are not compiled alongside the implementation and there is only one production generator. Validation must cover the new Range, deadline, partial-failure, tail and lifecycle invariants directly.

## Implemented files and validation record

- `SegmentIdGenerator`: Current/Next objects, one finite refill executor, shared take primitive, immutable result ranges, explicit failure/close states, counters and regression rejection.
- `JdbcSegmentStore`: native JDBC protocol, dedicated-connection/catalog/engine/primary-key checks, actual tail range and commit-unknown behavior.
- `StepController` / `StepPolicy`: bounded duration feedback, consecutive observations, cooldown and fixed-mode reset.
- `ShortCodeCodec`: separate numeric eight-round permutation and fixed nine-character Base62; independent reference vectors in the README/tests.
- Supporting API/configuration records: `IdGenerator`, `IdRange`, `IdGenerationException`, `SegmentStore`, `GeneratorOptions`, `JdbcOptions`, `GeneratorSnapshot`.

Executed on 2026-09-06 using Microsoft OpenJDK 17.0.16: `mvn -B -ntp -pl id-generator -am test -Dstyle.color=never`. Result: **19 tests, 0 failures/errors/skips**. This covers mixed instances, range crossing, immutable old results, cold single-flight, blocked prefetch without blocking current allocation, timeout/interruption/rejection recovery, partial discard, bounded close, final tail, store regression, feedback controls and codec vectors/inverse properties.

`JdbcSegmentStoreIntegrationTest` executed on 2026-09-06 against isolated MySQL 8.0.36, catalog `shortlink_id_test_v07`: **8 tests, 0 failures/errors/skips**. Command: `mvn -B -ntp -pl id-generator -Pintegration clean verify -Dstyle.color=never`, Java 17 with `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8`. The same run passed all 19 unit tests. It covers actual concurrent transactions, the final tail, failure before commit, commit-ACK loss after durable commit, independent business rollback, lock timeout, catalog/engine checks, and generator restart. No load benchmark, E2E test or existing development database operation has been performed.
