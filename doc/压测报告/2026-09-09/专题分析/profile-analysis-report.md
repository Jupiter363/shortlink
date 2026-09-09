# Fanout real-path proc analysis

Original verdicts retained; CPU 100% = one logical CPU. Exact formal windows exclude warmup and export.

| Stage | Verdict | Formal / counter seconds | Java / IO / EDGE CPU% | Fanout / Lettuce CPU% | Host PSI / EDGE some / full % |
|---|---|---:|---:|---:|---:|
| fanout-r6000 | CAPACITY_STAGE_PASSED | 60.000000000 / 59.000475929 | 203.13 / 150.27 / 236.25 | 0.169 / 3.508 | 7.241 / 1.440 / 1.238 |
| fanout-r8000-peak | CAPACITY_STAGE_FAILED | 10.000000000 / 9.001455153 | 251.08 / 193.42 / 304.11 | 0.222 / 3.555 | 14.646 / 3.126 / 2.823 |
| fanout-r8000 | CAPACITY_STAGE_FAILED | 59.989697869 / 58.996264592 | 246.80 / 191.10 / 296.66 | 0.153 / 3.441 | 13.828 / 3.218 / 2.819 |
| fanout-r7000 | CAPACITY_STAGE_PASSED | 60.000000000 / 59.010365663 | 227.11 / 175.76 / 274.12 | 0.136 / 3.288 | 10.711 / 2.121 / 1.870 |

## Interpretation

- Current cold6k fanout/Lettuce CPU is 0.169/3.508%, versus prior OUT10 cold6k 18.101/21.721%. The extra background work has disappeared in this observation, consistent with the implemented bootstrap change; already-caught-up historical consumers were also near0.17/3.6%. This is not evidence that steady-state Kafka publication became free.
- Current6k and7k completed full60-second PASS windows. The10-second8k stage retains its single-drop FAIL. Thelong8k stage retains HTTP/drop/monitor failures; it is not accepted capacity.
- All four runs have zero recorded cgroup throttling and softnet dropped; time_squeeze counts4/2/9/9. Java FDSize remains4096. These exclude observed quota throttling/FD table growth/softnet drops as this sample window explanation, not all scheduling or network latency.
- Long8k CPU and PSI are comparable to or slightly below the10-second8k window, and materially below the old8-worker8k run. There is no aggregate CPU step-up at the final failure boundary. Last complete interval13:41:01.725968–13:41:02.834150 UTC: Java235.91%, EDGE286.75%, host PSI11.019%, EDGE2.430/2.177%. Actual cutoff13:41:03.439093122 leaves a final partial interval unobserved by this difference. This cannot rule out a subsecond stall or classify a specific504.
- Binding: newRedirect SHAe805c149d0d5a414068c5768c961b9f16daeb1ad1ddf687263a0f429fb7a0f4d; PID981/startTicks1658245; containerd12e311ccb6159b2df9f110601a2062e623f221951457e1257d6e7ade4d4de0e. Four workers and all sampled application threads stay on0–7; observer own identities and start/stop affinity12–15 verified. Frozen runtime/observer/result SHA links and original diagnostics-OFF checks all pass.

## Exact windows
- fanout-r6000: 2026-09-09T13:34:21.947220716Z through 2026-09-09T13:35:21.947220716Z; original checks: [].
- fanout-r8000-peak: 2026-09-09T13:37:50.135275454Z through 2026-09-09T13:38:00.135275454Z; original checks: ["noDroppedArrivals"].
- fanout-r8000: 2026-09-09T13:40:03.449395253Z through 2026-09-09T13:41:03.439093122Z; original checks: ["clientExitedSuccessfully", "allHttpCompleteAndCorrect", "noDroppedArrivals", "noMonitorStop", "threeProducerLanesMatch", "actualKafkaOffsetsMatch", "measurementComplete", "nativeConservationVerified"].
- fanout-r7000: 2026-09-09T13:43:53.376253481Z through 2026-09-09T13:44:53.376253481Z; original checks: [].

## Boundaries
- Observer affinity proven at start and stop only; stage controller affinity is not independently in these proc targets.
- Old/current observer placements differ; whole before-after differences cannot be attributed solely to fanout.
- Only original successful formal windows count as passed. Peak10s failed1drop is not sustained8k acceptance.
- Last complete sample precedes actual cutoff; no interpolation into missing final partial interval.
- No JFR was requested: no GC pause/allocation/stack evidence. GC thread CPU is not STW duration.
- No Windows process JSONL is parsed by this proc-only lane; absence of old host-* filenames does not mean host sampling absent.
- PSI/ticks/FDSize do not establish individual HTTP504 root cause or absolute physical hardware ceiling.

Metadata/native-prefix independent review: profile-analysis-window-* separates the native504 stop from the independent WALLCLOCK_BUDGET_EXHAUSTED monitor failure. Metadata does not establish the monitor's exact trigger time. The client ended26.368124seconds after the actual formal cutoff; this post-pressure lifecycle is excluded from proc counters. This sentence is an editorial precision correction to the generated report; raw evidence is unchanged.

Evidence: profile-analysis-summary.json (all process/thread/cgroup deltas and intervals), profile-analysis-compact.json (identity/affinity checks, peaks and boundary intervals).
Analysis source SHA: bbd3bb273053c35723de0a67e780d2492e14ae47180f8d65f4a40fceb072515c
Pure-core/wrapper SHA values are recorded under reusedImplementation.
Summary JSON SHA: 576d32be4c66c8e8408e1ee00659e70ece5edece6ce210ec255445747b3f5d41
Report script SHA: c41a6cd5844e76ff37ea3ebdba015837b782eeb4afbdc9ad564e7d59da5b946a
