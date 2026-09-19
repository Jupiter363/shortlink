---
name: decline-selection
description: Resolve a completed scope-collection step, compare every frozen member across two periods, and preserve complete observed decline selections and their evidence.
---

# Decline selection, version 2

## Inputs and execution boundary

Execute inside one fixed `decline_selection/2` SKILL step. Its `scopeArtifact` input is the named output of a direct upstream Plan dependency. The period reference, baseline and target periods, group identity and method pin are frozen INPUT values; the requested metric remains PV, UV or UIP. Do not prefill a scope reference, artifact identity or member list before the upstream scope collector publishes it.

An unfinished producer is ordinary dependency scheduling. Once the producer succeeds, resolve its exact named output from the durable step ledger. Verify the actual published ScopeArtifact, its collection and source receipts, owner, group, run, revision, producing step, expiry and current authorization. A missing or invalid successful output is an error, not an empty scope or permission to fall back to current group membership. Both comparison periods must use this same complete frozen member scope.

The approved method name, version 2, `decline-selection/2` directory and native-parsed content digest are pinned to the Run. Verify the same pin before execution and recovery. Reading this method grants neither additional tools nor query permission.

For the current shard retain the two real asynchronous LINK_METRICS child queries and their original request identities. Receive every result page and verify scope and period proofs before calculating comparisons. Reuse READY children and published LOCAL outputs; WAITING jobs, unknown submission outcomes, cancellation and capacity deferral remain durable states. Never replace an uncertain request or refresh an artifact's expiry to continue.

## Comparison and evidence

Compare the same link and requested metric in baseline and target. Preserve both observed values, the exact signed difference, periods and source evidence. UV and UIP are independently measured per-link window values; never add them across links or shards to claim group-wide distinct visitors.

Select every structurally VERIFIED result with a negative target-minus-baseline difference. Preserve the entire selection, ordered by signed difference ascending and then link ID ascending; it is not a Top K sample. A zero baseline has no percentage change. Honor all registered comparability reasons, including unequal or overlapping periods, missing observation timing, metric-version differences and unavailable evidence. Different snapshots or manifest versions for different periods do not themselves prove incompatibility or a common database snapshot.

Every conclusion remains OBSERVED_ONLY. Complete query coverage and structural comparability do not establish complete telemetry, object lifetime across both periods, causal effects or business performance. Retain UNKNOWN collection quality, approximate UV/UIP indicators, missing metrics and source limitations without upgrading quality or suppressing observed differences.

## Completeness, publication and downstream use

A complete selection requires all expected shards and both periods, every candidate row and no coverage gaps. This differs from comparability and telemetry completeness; preserve gaps and incompatible or unverified counts.

Use NO_DECLINES only when all candidates are compared without unverified or incompatible candidates and no verified negative difference exists. An explicitly authorized empty frozen scope has no query shards and never triggers a group fallback. Otherwise an empty selection means INSUFFICIENT_EVIDENCE. A partial nonempty selection must remain explicitly incomplete.

Publish `selectedEntities` and `selectionEvidence` together through the existing approved LOCAL calculation boundary, then seal their verified page index. Preserve the actual source scope, periods, predecessor chain and original expiry. Use bounded pages, not the entire member set or complete evidence in Graph state. Reconstruct progress from durable artifacts without rerunning READY calculations.

These outputs are evidence for a later explicit Plan step. This method does not diagnose causes, attribute costs or conversions, calculate ROI, or infer geographic/device explanations. Downstream dimension analysis must resolve the actual selected pair and issue real authorized joint-dimension queries over its derived selected scope; unrelated marginal distributions cannot substitute for that evidence.
