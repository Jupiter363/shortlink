---
name: dimension-change
description: Compare real province-by-device distributions across two periods for the exact entities selected by a completed upstream analysis, preserving shard cohorts, source evidence and data-quality limits.
---

# Dimension change, version 1

## Frozen inputs and dynamic evidence

Use the server-frozen definition and the actual selectedEntities and selectionEvidence outputs of the same successful upstream step. Their identities are resolved from the durable step ledger when they exist. Never replace an unfinished, failed or inaccessible upstream result with a made-up artifact ID, empty list, current group or latest conversation value.

The two source artifacts must form the real sealed pair. Verify their current authorization, payload hashes, original scope and periods. Check the approved dimension-change version and native-parsed content digest before execution and recovery; this method document grants no extra tools or permissions.

Publish a genuine SelectedScopeArtifact through the registered LOCAL calculation boundary before querying. Its member set is exactly the selected entities, ordered by link ID and hashed over the complete set. The inherited enumeration version describes the original scope's provenance; it is not a new authority enumeration or proof of current group completeness. Do not manufacture ScopeArtifact, GroupMembersPage or authority responses for this subset.

## Real joint queries

Version 1 uses the ordered joint dimensions province and device. Apply the frozen filters with their exact IN or IS_UNKNOWN semantics; do not infer geographic names, device aliases or additional filters. For each fixed cohort of at most 500 selected members, issue the original two asynchronous DIMENSION_BREAKDOWN requests against the frozen selected-member endpoint. Keep the same cohort and filters in both periods.

Use one fixed dimension_change SKILL step with real child requests. Retain original request identities across waiting, recovery and capacity deferral. Receive and validate every result page before using it; a partial prefix or top list is not a complete joint distribution. An explicit too-large result remains unavailable and must not be silently truncated. Reuse READY evidence and published LOCAL outputs without resubmitting jobs or recomputing completed pages.

## Numbers and interpretation

The analysis unit is SHARD_COHORT: each result describes the same explicitly identified shard cohort across the two periods. It does not claim independent per-link distributions or a full-group result. When the selected set spans multiple shards, do not relabel a shard summary as the entire selected cohort.

Use actual province-by-device buckets, never a combination of separate province and device rankings. Preserve KNOWN values exactly and keep UNKNOWN distinct from NOT_APPLICABLE. A missing counterpart bucket can mean zero observed matching clicks only after that period's complete query, scope, filters and bucket totals have been verified. It does not prove complete telemetry or the absence of real visits.

For each period, verify that the sum of bucket PV equals the independent summary PV and ratioDenominator. Each share uses that period's complete filtered window for that exact cohort. Preserve the two denominators when comparing shares; a zero baseline does not have a relative percentage change.

Read UV and UIP from each query's independent whole-window summary. They may use approximate distinct counting. Never sum UV or UIP across buckets, links, days or shards, and never invent a whole-selected-set distinct count from shard summaries.

All conclusions are OBSERVED_ONLY. Preserve original approximation, dimension coverage, collection-quality UNKNOWN, missing metrics and comparability limits. Different query snapshots are not a shared historical data snapshot. Observation timing, metric versions, period comparability and source provenance must remain visible; a verified page chain does not establish causality, object lifetime or complete data collection.

## Empty results, publication and continuation

For an explicitly published empty selection with NO_DECLINES, publish SelectedScope and then a traceable dimensionChanges result with evidenceDisposition NOT_APPLICABLE. Do not submit asynchronous statistics requests or claim that geography and devices did not change.

For an empty selection caused by INSUFFICIENT_EVIDENCE, also submit no statistics requests. Publish the corresponding limited evidence result and preserve that reason. A nonempty partial selection can describe only that explicit subset and must retain selectionComplete and source limitations.

Publish bounded LOCAL comparison pages and a final dimensionChanges manifest referring to the complete page chain, SelectedScope and source artifacts. The output expiry cannot exceed its inputs. Keep bulk rows outside Graph state; retain references and progress there. A successful method step or complete result artifact does not declare a user goal ANSWERED and does not bypass the common report and goal assessment boundary.
