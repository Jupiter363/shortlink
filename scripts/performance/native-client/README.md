# Native redirect client 1.4.1

This Linux/amd64 Go source starts from the frozen `1.4.0-correlated-owners`
generator in `.work/real-breakthrough-20260909/native-correlated-client`.
All inherited Go tests are retained unchanged. This is a load-generation tool,
not a production service; its unit tests use owned loopback fixtures only.

Optional private configuration field:

```json
{"requestsDrainedReceipt":"/absolute/new-stage/requests-drained.json"}
```

When present, `runId` and `label` must be nonempty. Existing output or receipt
files prevent a new workload; prior evidence is never overwritten. A private
same-directory temporary file is fsynced and hard-linked atomically into the
new receipt path. The filesystem must support hard links. The receipt is at
most 4096 bytes and contains no headers, targets, cookies, or credentials.

The receipt is published only after all workers, HTTP exchanges, owner returns
and owned connections have finished, before percentile sorting, diagnostic
summary allocation or JSON export. Its Linux CLOCK_MONOTONIC timestamp, PID and
`/proc/self/stat` startTicks bind it to the wrapper's recorded process identity.
This is local process evidence, not cryptographic authentication against an
actor with the same filesystem privileges. Keep the stage directory private.

A drained failed request remains a failure. The receipt retains completion,
stop reason and lightweight counters; it never claims capacity PASS. Publishing
failure still produces the original full summary and returns exit 74. Original
HTTP deadlines (at most 10 seconds), no retry/no redirect behavior, all 64 source
addresses, 512/1024/2048/4096 owners, per-source ownership, arrival times and
conservation checks are unchanged. Request elapsed time is captured before
receipt I/O and export. Summary schema remains 1; receipt schema is separately 1.

Build with an already available Go >=1.24 toolchain, no dependency download:

```bash
GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off CGO_ENABLED=0 go test -count=1 -timeout=90s ./...
GOTOOLCHAIN=local GOPROXY=off GOSUMDB=off CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -trimpath -buildvcs=false -o /absolute/private-build/native-client .
```

The actual local toolchain is `/opt/shortlink-perf/toolchains/go1.27.1/go/bin/go`.
Build outputs belong under `.work`, not this source directory.

The wrapper imports `scripts/performance/native_lifecycle.py`. Capture
`spawned_monotonic_ns=time.monotonic_ns()` before `Popen`, then immediately read
`start_ticks=read_start_ticks(proc.pid)` (a missing process is a failed launch).
Construct `NativeLifecycle` with run_id, label, pid, start_ticks, captured time,
active_budget_seconds=`warmup + duration + 25`, export_budget_seconds=60 and the
configured workers. Times are nanoseconds from the same Linux monotonic clock.

Poll `observe(receipt_path, now_ns=time.monotonic_ns(), returncode=proc.poll())`:
`ACTIVE` keeps the original activity deadline; `EXPORTING` is bounded by the
receipt's drain time plus 60 seconds; `FAILED` requires the existing failure and
termination handling. `EXITED` requires complete JSON parsing and a call to
`finalize(summary, returncode, now_ns=time.monotonic_ns())`. Only `COMPLETE`
permits downstream evaluation. Summary parsing also uses the export budget.
All target coverage, drops, latency, fixture and Kafka checks remain required;
`Decision.capacity_pass` is always false. The lifecycle module starts no process
and does not parse an unbounded output while the generator is still writing.

On a monitor stop, latch the original failure, call
`request_stop(now_ns=time.monotonic_ns(), grace_seconds=12)` and send SIGTERM
once. Continue polling: `STOPPING` retains the earlier of the activity deadline
and first-stop time plus 12 seconds; a timely valid receipt permits bounded
export. Repeated stop requests cannot extend either deadline. Kill a still
running child only on a real lifecycle deadline/failure or an independent hard
output limit. Do not reuse a fixed 12-second terminate-and-kill helper after
requests have drained: it can truncate a valid larger export. A stopped run
remains failed even when its export completes successfully.
