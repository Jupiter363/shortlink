# Leaf Segment source lock

Frozen before implementation on 2026-09-06.

- Repository: https://github.com/Meituan-Dianping/Leaf
- Full commit: `86a6441d263497b9f9ee321de13422b9c63f0c06` (official master/HEAD resolved with `git ls-remote`).
- Immutable source: https://github.com/Meituan-Dianping/Leaf/tree/86a6441d263497b9f9ee321de13422b9c63f0c06
- License: upstream Apache-2.0, copied byte-for-byte to `LICENSE`. No NOTICE exists in the complete tree at this commit; `upstream/leaf-tree.json` records the inspected tree.
- `upstream/source-manifest.json` records each retained file's upstream path, Git blob SHA-1 and SHA-256. Downloads were checked against official tree blob identities before adaptation.
- `upstream/leaf/` is an unmodified reference snapshot, outside Maven source roots. Upstream POMs use `.reference` suffixes and are not build inputs.

This module is a controlled, source-informed adaptation, not the unmodified Leaf binary or Leaf Server. The retained protocol is independent transactional high-water reservation, Current/Next segments, one asynchronous refill and consumption-time step feedback. Upstream locking, cursor and lifecycle code must change together for the project's Range/bounded-resource contract; the adaptation does not inherit upstream throughput claims. See `patches/0001-project-adaptation.md` for the frozen implementation boundary and subsequent validation record.

Production dependencies are Java 17 (`java.sql`, `javax.sql.DataSource`, concurrency primitives) only. The embedding service supplies a dedicated bounded MySQL connection pool with connect/acquisition/network timeouts. No Spring, MyBatis, perf4j, SLF4J, Guava, Curator or Leaf Server dependency is required by the adapted core. Test dependencies: JUnit Jupiter and MySQL Connector/J; Maven configuration is owned by the root integrator.
