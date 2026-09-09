# Embedded ID generator

Java 17; controlled Leaf Segment adaptation. Read `UPSTREAM.md` before changing the pinned source or concurrency protocol. This module does not include a server, a runtime Leaf dependency or a second namespace.

## Public API

```java
IdGenerator ids = new SegmentIdGenerator(new JdbcSegmentStore(dedicatedDataSource));
long id = ids.nextId();
List<IdRange> ranges = ids.reserveRanges(500); // exactly 500 IDs, possibly across segments
ShortCodeCodec codec = new ShortCodeCodec(fixed32ByteKey);
String shortUri = codec.encode(id);
long sameId = codec.decode(shortUri);
ids.close(); // embedding application closes its DataSource separately
```

`IdRange(startInclusive,endExclusive)` is immutable. Valid IDs satisfy `1 <= id < 2^52`; ranges may end at `2^52`. A failed Range call exposes no partial result; already consumed local IDs are burned. A business transaction rollback does not recycle IDs. The batch layer, not this allocator, must persist/reuse committed row identities and resolve unknown business commits before assigning another identity.

The fixed namespace is `shortlink_global`; there is no user/tenant/domain namespace parameter. One process normally owns one generator instance for this namespace. Multiple processes reserve through the same authoritative database. A short mutex serializes local cursor changes, and an allocation consumes an entire segment portion in one transition rather than looping over `nextId`. Current/Next segment objects are never reset for reuse.

## Database and resource contract

The integrator owns `deploy/mysql/001-business-schema.sql`. Required table shape:

```sql
CREATE TABLE t_id_alloc (
  biz_tag VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  max_id BIGINT NOT NULL,
  step INT NOT NULL,
  update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
INSERT INTO t_id_alloc(biz_tag,max_id,step) VALUES ('shortlink_global',1,100000);
```

This example is a schema requirement, not a startup migration: the runtime never creates or repairs the table/row. Use only the controlled schema initializer for a new environment. `max_id` is an exclusive high-water mark. The JDBC adapter checks the expected catalog, InnoDB, the exact primary key, namespace row and bounds. The table `step` is a positive initial configuration fact; each reservation captures its actual local policy size independently and never derives a tail start from that stored/configured step.

Provide a **dedicated, non-transaction-aware DataSource** with bounded pool acquisition and connect time, autoCommit=true, finite connections, and the MySQL driver. Do not pass Spring's transaction-aware DataSource proxy or a business transaction connection. The adapter sets finite statement, InnoDB lock-wait and network timeouts. DataSource acquisition/connect bounds are an embedding requirement: generic JDBC cannot forcibly interrupt an arbitrary misconfigured pool. The generator isolates it behind one daemon refill thread and one queue slot, so caller deadlines and close deadlines remain finite even if a driver ignores interrupts; operators must still retire unhealthy connections/processes.

Each reservation locks the row, computes the actual tail grant, conditionally advances the high-water mark, reads back the exact end and commits before publication. A thrown commit/close SQL error discards the candidate, even when the server may already have committed. It is safe to retry a fresh independent reservation; gaps are allowed. No generator lock is held during JDBC, sleep or a Future wait. Public reservations, lock acquisition, refill retry count/backoff and shutdown have separate finite budgets. Failure/rejection releases the single-flight state; caller timeout does not start a second concurrent refill.

Defaults: fixed step 100000, prefetch at 20% remaining, request count at most 50000, at most 128 returned ranges, three refill attempts, one refill worker. `GeneratorOptions` explicitly configures these limits. `configureStep` changes only future requests. Dynamic mode requires consecutive fast/slow segment-consumption observations and a cooldown; default mode is fixed. `snapshot()` exposes allocation work, failure/discard counts, step changes and current/next readiness. These are correctness/operational counters, not a throughput claim.

Restoring/promoting the allocation database requires stopping and isolating old writers/generators, discarding all local segments and proving the new start is at least every previously reserved `endExclusive`. Business `MAX(linkId)` is insufficient. The module refuses a locally observed regressing store but cannot prove cluster-wide backup safety; it does not invent an allocation-lease system.

## Frozen codec

Version `feistel52-mix64-8-base62-v1`: split 52 bits into two 26-bit halves. Eight Feistel rounds use `newLeft=right`, `newRight=left XOR F(right,key[round])`. `F` is the low 26 bits of the documented unsigned 64-bit mix in `ShortCodeCodec`; overflow is Java long arithmetic. The 32-byte input key is four big-endian words; per-round expansion is performed once in the constructor. No per-ID UUID, URL hash, Bloom access, collision retry, Mac or Cipher allocation occurs.

Alphabet `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz`, left-padded to nine digits. Feistel is a bijection for every fixed round function/key: each round is inverted by recovering previous right from new left, then previous left by XOR. Base62 is injective on the 52-bit output domain. Thus uniqueness follows from unique IDs and the fixed key/version/alphabet, not sample collision tests. ID zero remains reserved and its corresponding code is rejected on decode. This custom permutation provides only obfuscation, **not cryptographic security or access authorization**. Never rotate the key within this namespace without a separately approved address-space design.

Independent fixed vectors for key bytes `00 01 ... 1f`:

| ID | Short code |
| --- | --- |
| 1 | 1ehusmDXL |
| 2 | A6HTUQmEz |
| 62 | Jr5z0yO89 |
| 67108863 | A7PT8lf59 |
| 67108864 | CwXGuknxE |
| 4503599627370495 | FR0nftDCp |

## Validation

Run with Java 17: `mvn -pl id-generator -am test`. Unit tests use deterministic barriers and a bounded fake store to verify mixed allocation, initialization, slow refill, interruption, rejection recovery, partial discard, close, fixed vectors and dynamic feedback. These are not load benchmarks.

`JdbcSegmentStoreIntegrationTest` requires a newly created, dedicated MySQL catalog beginning `shortlink_id_test` and explicit reset permission. It resets only that catalog's `t_id_alloc`; do not point it at a development database. Set `SHORTLINK_ID_TEST_JDBC_URL`, `SHORTLINK_ID_TEST_CATALOG`, `SHORTLINK_ID_TEST_USER`, `SHORTLINK_ID_TEST_PASSWORD`, and `SHORTLINK_ID_TEST_ALLOW_RESET=true`, then run `mvn -pl id-generator -am -Pintegration verify`. Missing environment skips real-DB tests and must be reported as unverified. Tests inject both sides of commit-ACK loss through a JDBC proxy while using actual MySQL transactions, plus concurrent reservations, tail exhaustion, lock timeout and independent business rollback.
