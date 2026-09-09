# Fanout actual formal windows

Original verdicts remain unchanged. All times UTC on 2026-09-09.

| Stage | Formal start | Formal end | Seconds | Original verdict | HTTP incorrect / drops (measure) |
|---|---|---|---:|---|---:|
| fanout-r6000 | 2026-09-09T13:34:21.947220716Z | 2026-09-09T13:35:21.947220716Z | 60 | CAPACITY_STAGE_PASSED | 0 / 0 |
| fanout-r8000-peak | 2026-09-09T13:37:50.135275454Z | 2026-09-09T13:38:00.135275454Z | 10 | CAPACITY_STAGE_FAILED | 0 / 1 |
| fanout-r8000 | 2026-09-09T13:40:03.449395253Z | 2026-09-09T13:41:03.439093122Z | 59.989697869 | CAPACITY_STAGE_FAILED | 119 / 801 |
| fanout-r7000 | 2026-09-09T13:43:53.376253481Z | 2026-09-09T13:44:53.376253481Z | 60 | CAPACITY_STAGE_PASSED | 0 / 0 |

8k peak has one workerWindowDropped (also counted in workerStartDropped), no incorrect HTTP, and only noDroppedArrivals fails.
8k full has 119 incorrect HTTP and 801 drops (716 busy + 85 late); native stop reason is HTTP_STATUS_OTHER/status 504. A separate stage wallclock monitor failure does not erase those HTTP failures.
6k and 7k passed with zero HTTP/client errors and drops. All four original systemDiagnostics gates passed.

- Formal window uses exact integer nanoseconds: measure.start to min(planned measure.end, warmup.start + stop.elapsedMillis, warmup.start + scheduler.schedulingElapsedMillis).
- All and measure error/drop counts are native phase-cohort aggregates, not a new timestamp-filtered recount; completions may extend beyond the admission cutoff.
- Native stop.status identifies the stop-triggering HTTP status, not the status of every incorrect HTTP response.
- Client process wallclock and nativeClientProcess CPU/RSS cover process lifetime including warmup and post-formal completion/export; they are not formal-load resource aggregates.
- monitorStop contains a reason but no exact stop timestamp here. Its occurrence cannot be timestamped precisely as export-only from these metadata.
- workerStartDropped overlaps its worker subcategories; do not add the parent count to workerWindowDropped or workerStoppedDropped.
- Original verdict, checks and failures are retained without promotion. No system samples, JFR, application source, current resource or service was accessed.

JSON SHA256: 8237dacb5dbf7f36e9b826a60812264e0769064f2e030d159b8d3c11ad0f86b2
Script SHA256: c3cef93877e33f86b73dcc3a7a1e851c658693493c152420ad2ced315add328a
