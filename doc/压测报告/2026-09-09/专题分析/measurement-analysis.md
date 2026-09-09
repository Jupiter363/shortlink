# fanout-r8000 wall-clock and evidence-export audit

Read-only scope: stage/native source, bounded result/log/process samples, build proof and metadata obtained by the separate native parsing lane. No test, build, load, Kafka operation, resource mutation or original evidence modification. This lane did not read or parse the 785,077,114-byte native summary body. All original FAIL checks remain unchanged.

## Conclusion

**The stage deadline includes native post-load summary construction and file export. This round's wall-clock stop occurred after all HTTP exchanges had finished. It adds a harness failure to a round that already genuinely failed HTTP and arrival criteria; removing that extra harness failure would not make the round pass.**

The existing `measurementComplete=false` is not evidence that the late external signal truncated the file: native first stopped for HTTP_STATUS_OTHER/status504 near the end of the scheduled window, while its independent arithmetic `conservationPassed` is true. Exact full diagnostic coverage/SHA must still be established by the separately assigned streaming audit. The existing result reports verified completion windows, complete timed exchanges, correlated IDs and a parseable/SHA-recorded native artifact; this lane does not substitute those checks for an independent full-body audit.

## Source identity

The result binds native binary SHA256 `931602247f59b7fe9d9b01bec71b8cc01a3701c74d8bdc39f93c62e515141571`. `.work/real-breakthrough-20260909/native-correlated-client/build-run-1/build-proof.json` records the same binary and the tested source identities. Current small source SHA values match that proof:

- `main.go`: `ac4a6a02e6e575b789c9c96da57ac3f2789f66a606c3bd4518f8559400676480`
- `scheduler.go`: `6a50c69ec77e11b0ebda518e3e9a3cc7ec21a07fcd34581e5c9ba6cb27c92633`
- `summary.go`: `04f9970cf0a08ff4722080bf34d418fdca49df65e33e800b403f5631a8f1588e`
- `arrival_diagnostics.go`: `8712eb8ecd687f10902bfeac0defbfb10c69080242b54c5616e92db4c0c421dd`
- `client.go`: `c3551c57c2c6381e8a8c69623818a01eddf105a8f84fbc4592995bdae91d76da`

All native source references below mean that source directory. Stage references mean `.work/real-fanout-20260909/stage.py`. Run evidence means `.work/performance/20260909T132900Z-09adb5ee/stages/fanout-r8000/`.

## Exact clock boundaries and evidence

| Point | UTC / elapsed | Evidence and interpretation |
| --- | --- | --- |
| Stage preparation starts | 13:39:23.033180 | result.startedAt; not the wall-clock deadline origin |
| Client launch recorded | 13:39:33.320226 | result.clientLaunch; taken immediately before Popen |
| Native scheduling origin | 13:39:33.449395253 | native warmup window start, supplied by the bounded prefix audit |
| Formal window starts | 13:40:03.449395253 | origin + 30 s |
| First invalid HTTP triggers stop | origin + 89.989697869 s | native stop.reason=HTTP_STATUS_OTHER, status=504; strictly before the planned end by 10.302131 ms |
| Scheduled plan end | 13:41:03.449395253 | configured origin + 90 s |
| Scheduler exits | origin + 90.001310729 s | native scheduler.schedulingElapsedMillis; all 601,500 arrivals classified |
| All worker sessions have returned | approximately 13:41:03.696379599 | native runtime.totalElapsedMillis=90,246.984346 is captured just before summarize, after sessions.Wait; it excludes summary/export |
| Outer deadline | roughly 13:41:28.3 | 30 + 60 + 25 = 115 s from a monotonic reading shortly after launch/observer creation; no precise trigger timestamp was persisted |
| Native process lifecycle observed ended | 13:41:29.807217 | result.clientInterval.end after process.wait and client sampler stop; not an exact syscall-exit timestamp |
| Stage evidence complete | 13:42:15.484934 | includes later queue drain, Kafka boundary, Python parsing, source proof and hashing; outside the 115 s native deadline |

The elapsed interval from the recorded post-sessions boundary to the outer observed process end is **26.110837401 s**. It includes native summarization/JSON encoding/write/Sync plus wrapper exit-detection/sampler overhead. There are no per-phase timestamps to divide those costs more precisely. Do not describe all 26.11 s as fsync time or pure JSON CPU.

Stage lines 543–558 launch the child and observer, then set `deadline = monotonic() + warmup + duration + 25`. Lines 560–578 keep that same deadline while `process.poll() is None`; they do not distinguish active requests, draining, summary construction or writing. The deadline precedes the sampling branch, so at a later loop iteration it wins even if requests are already finished. A synchronous `sample()` can delay the next deadline check; the source is a bounded-intent watchdog, not proof of an exact 115.000-second signal.

Stage lines 417–430 send SIGINT, wait up to 12 s and only then SIGKILL if necessary. Here nativeReturnCode is 2, and `native-client.log` contains the final `finished ... reason=HTTP_STATUS_OTHER` line. Native main.go:66–74 emits that line only after JSON encoding and `out.Sync()` both succeed, then returns 2 for its existing failure state. The evidence therefore supports a completed export and ordinary failure exit, not a SIGKILL-truncated export. The exact time at which SIGINT was delivered/handled was not recorded.

## Native execution order makes the distinction material

1. `client.go:85–123` retains the original per-exchange absolute deadline, spanning connect/write/header/body. `client.go:145–167` identifies an invalid HTTP status and invokes the stop callback at headers; it still preserves subsequent body/exchange evidence. The first 504 is a response result, not a 115-second timeout manufactured by the outer runner.
2. `scheduler.go:380–386` finishes or stops admission, closes worker job channels, waits for all outstanding sessions under their original request deadlines, then closes the signal watch channel. No further request is dispatched by the summary/export path.
3. `scheduler.go:387–394` snapshots the original first-stop reason and calls summarize after sessions.Wait. `summary.go:108–111` assigns **both** measurementComplete and scheduling_complete from `complete && !stopped`; they are not independent full-record conservation indicators.
4. `summary.go:148–200` aggregates latency arrays, sorts quantiles, and constructs detailed diagnostics. `arrival_diagnostics.go:152–235` builds every covered request, unsent-arrival and owner-return record within the existing one-million diagnostic prefix limit. All this happens after the requests are finished.
5. `main.go:66–72` encodes the entire Summary with indentation, writes it, Syncs it and only then emits the final line. The output was exclusively reserved at main.go:34–41 before network activity, but existence of that output file alone does not certify completion.

The inspected local Go 1.27.1 non-jsonv2 encoder implementation (`/opt/shortlink-perf/toolchains/go1.27.1/go/src/encoding/json/stream.go:216–245`) marshals one whole value into an encode buffer, creates an indentation buffer and only then writes it. Consequently, `json.NewEncoder(file).Encode(wholeSummary)` is not by itself a bounded-per-record streaming solution. The build script inherits GOEXPERIMENT without recording it, so this audit does not separately certify that build option; the application source unquestionably requests one whole, indented Summary value. Any exact allocation attribution still requires appropriate export-phase measurement.

Observed `native-process.jsonl` supports substantial **post-load** work: RSS was 362,151,936 bytes at 13:41:03.389819, then rose after requests finished to 5,642,559,488 bytes at 13:41:23.393621. CPU remained active through most of that rise and fell to about 4–6% in the last samples. This is consistent with summary/serialization followed by I/O, but not sufficient to separate allocation, GC, encoding, write or Sync. It cannot explain the earlier 504s by itself: summary construction begins only after all those exchanges finish. The result's whole-process CPU mean/RSS mean includes this export interval and must not be presented as steady request-serving resource cost.

## What remains a real failure

`result.json` preserves 601,500 scheduled arrivals, 600,699 sent/completed/received, 600,580 correct, 119 incorrect HTTP, 801 dropped, no cancelled arrivals and no incomplete exchanges. The bounded prefix independently reports arithmetic conservation true, scheduler busy drops 716 plus late drops 85, and all 600,699 gateway IDs valid hex32. The separate streaming lane is checking every request/drop record.

- The 119 wrong responses and 801 missing sends already prevent a capacity PASS.
- Stage lines 395–396 combine native first-stop semantics with completion/conservation acceptance. Thus `nativeConservationVerified=false` in the old result does not mean `conservationPassed=false`; the other conjunct, scheduling_complete, is false after the HTTP stop. Keep the original check unchanged and explain its two inputs separately.
- Stage lines 369–375 request the strict all-success multiplicity ledger using total.correct. Because quality is false, lines 392–393 also force the three-lane/Kafka capacity gates false. The resulting multiplicity errors must not automatically be called message loss. A failure-aware diagnostic ledger may separately reconcile actual HTTP status classes and real producer/Kafka deltas, without weakening or replacing the all-success capacity gate.
- The result's 7,953.0833 correct completions/s is explicitly divided by the planned 60-second window (`completed_windows`, lines 267–298), not by the strict admission-active duration. Strict active formal duration from min(planned end, scheduler elapsed, first stop elapsed) is 59.989697869 s. Neither value establishes a passing 8k steady platform. Preserve the planned-window measurement and label any additional active-window figure distinctly; do not recalculate the original result in place.

## Smallest bounded repair design for a new measurement revision

The following is a proposal only; no tool, product, budget or source was changed.

1. **Add one small, independently bound lifecycle channel.** A new native build should emit bounded fixed-schema records for scheduling start, admission end and requests-drained, with runId/label, configuration identity, process identity, monotonic elapsed time and immutable first-stop reason/counts. Emit requests-drained immediately after sessions.Wait and before any O(N) summarization. Bind the pipe or exclusively created sidecar to the owned child; reject stale IDs, oversized records, impossible time order, duplicates, truncated records and reuse. Never infer requests-drained from RSS, idle CPU, file existence or a text substring alone.
2. **Separate active-work and export watchdogs.** Keep the current arrival schedule, source-owner mapping, start-lateness limit, first-invalid stop behavior, absolute request timeout, no-outside-window starts and complete tail collection. Until a valid requests-drained marker arrives, enforce the original active-work watchdog. After it arrives, permit only a separately declared bounded export deadline (for example 45 s, chosen and validated before a new run), still under a total stage hard ceiling and existing ownership/interrupt cleanup. A missing marker or overdue active request remains a failure. This does not extend any HTTP request or arrival window. Export timeout/failure remains an evidence failure, never PASS. Persist exact phase start/end and signal/exit timestamps.
3. **Keep monitoring and acceptance complete.** Do not stop queue/error monitoring merely because requests drained. The system observer currently follows client exit and is launched with max-seconds=150; a new export ceiling must be reflected in that finite observer horizon or in an explicitly verified phase-aware stop contract. Preserve the same CPU affinity, sample-loss checks, source proof, terminal queue drain, all three producer lanes and real Kafka boundary accounting. Keep original business failure reason alongside a separate export failure reason.
4. **Reduce export-only memory if needed.** Compact per-record serialization into a bounded buffered writer can retain every request/drop/owner-return record and exact scalar values without encoding one huge indented value. Keep the existing privacy whitelist and exclusive output semantics. A final byte-count/SHA/completion receipt is published only after successful flush/Sync/close; partial data is retained but rejected for acceptance. This should be a separately identifiable tool version. Removing fields, retaining only successes, dropping tail records or sampling diagnostics to meet the timeout is not an acceptable repair.
5. **Avoid replacing the export problem with unbounded postprocessing.** Stage.py:86–89/595 currently reads the entire up-to-2GiB artifact into a Python string/object graph and later hashes it again at line 622. These operations are outside the 115-second deadline, so they did not cause that particular timeout. A future bounded streaming verifier/compact summary can accumulate exactly the same unique-arrival, completion-window, full timing, ID and count checks without one full JSON object graph. Keep a full streaming SHA and reject duplicate/missing/malformed records, and continue to preserve the complete raw evidence.

## Required acceptance before another measurement revision runs

- Pure fixture test with a slow exporter: all arrivals/tail responses finish before the original active deadline; export takes longer than the old extra 25 seconds but less than the new declared export ceiling. It must complete export without extending admission, changing request deadlines or weakening any business check.
- Hung active worker, missing/spoofed/stale phase marker, premature requests-drained marker, backward/nonfinite timestamps and oversized channel data must fail closed; no transition to the export allowance.
- Invalid HTTP near the last scheduled arrival (including a 504), owner/source busy drops and dispatch lateness must preserve exact first-stop time, original failure exit, all classified arrivals, all tail completions, phase assignment and original capacity FAIL. An export success must never upgrade these.
- Export failures including ENOSPC, short write, flush/Sync failure, malformed/truncated JSON and stalled exporter must preserve partial evidence and a distinct failing receipt, with bounded child cleanup and no orphaned process.
- Round-trip equality for all current summary fields and every diagnostic record: successful/incorrect/client-error, dropped/cancelled, original due time, actual completion time, request IDs, owner returns and exact percentile definition. One-million-arrival/resource admission bounds remain unchanged. No Kafka budget revision, retention credit or queue-limit relaxation belongs in this change.
- Validate system/source monitoring through the explicitly recorded phases and maintain actual three-lane/Kafka accounting. Verify the new artifact's full SHA and complete-arrival coverage. Compare runtime behavior only under a newly frozen measurement identity; do not overwrite or reinterpret this run's FAIL as PASS.

Open limitations: no exact encode/write/Sync split or exact watchdog signal timestamp exists in this run; this lane has not independently parsed the giant diagnostic body; the cause of the 504s and source-pool saturation is outside this narrow export audit. Those limitations do not alter the proven post-load deadline overlap.
