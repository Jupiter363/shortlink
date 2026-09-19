---
name: decline-selection
description: Compare each member of a frozen short-link scope across two periods and preserve complete, evidence-backed decline selections for later analysis.
---

# Decline selection, version 1

## Inputs and execution boundary

Use the server-frozen scope reference, ScopeArtifact, two-period definition and requested metric (PV, UV or UIP). The ScopeArtifact must already have been published by the real scope collector. This method does not collect the scope itself, expand the current group, accept a replacement member list, or infer scope from conversation text.

The approved method name, version, directory and native-parsed content digest are pinned to the Run. Verify that pin before execution and recovery. Reading this method does not grant access to additional tools or authorize a query.

Execute inside one fixed `decline_selection` SKILL step. For the current scope shard, retain the two real asynchronous LINK_METRICS child queries and their original request identities. Receive all result pages, verify their frozen scope and period proofs, then publish deterministic comparison and selection artifacts. Reuse existing READY children and published local outputs. WAITING, unknown submission outcomes, cancellation and capacity deferral remain durable runtime states; none permits an untracked replacement request.

## Comparison and evidence

Compare the same link and requested metric in the baseline and target periods. Retain both observed values, the exact signed difference, period references and source evidence. UV and UIP are per-link window values; never sum them across links or shards to claim a group-wide distinct count.

Select every structurally VERIFIED result whose target minus baseline is negative. Preserve the complete selection and order it by signed difference ascending, then link ID ascending. Do not turn this selection into a Top K sample. A zero baseline has no percentage change; do not manufacture an infinite or zero rate.

Honor the comparability result produced by the registered calculation. Different period snapshot IDs or manifest versions alone do not make a comparison incompatible, but they do not prove a common data base. Unequal or overlapping periods, missing observation timing, metric-version differences and absent evidence retain their explicit comparability reasons.

All conclusions in this version are OBSERVED_ONLY. Query coverage and structural comparability do not prove complete telemetry, object lifetime across both periods, causal effects or business performance. Preserve collection-quality UNKNOWN, approximate UV/UIP information and missing metrics even when query coverage is complete and a row is structurally VERIFIED. Do not upgrade quality or hide observed differences to simplify the report.

## Completeness and empty outcomes

A structurally complete selection requires every expected scope shard in both periods, all candidate comparison rows and no coverage gaps. It is distinct from every row being comparable and from telemetry completeness. Preserve gaps and unverified or incompatible counts in the evidence.

Use NO_DECLINES only when the complete candidate set has been compared with no unverified or incompatible candidates and no negative verified differences. This includes an explicitly authorized empty frozen scope, which has zero shards and must not trigger a current-group fallback. Otherwise an empty selection means INSUFFICIENT_EVIDENCE. A partial nonempty selection must retain its incompleteness; do not present it as all declining links.

Publish the two named outputs, selectedEntities and selectionEvidence, together through the approved local calculation boundary. Their expiry cannot exceed their source artifacts. The evidence must retain the source page chain, scope identity and period bindings; a summary of counts is not a substitute for the selected entity set.

## Later analysis

The selected entities are reusable evidence for another explicit Plan step. Decline selection itself does not diagnose causes, attribute conversions or costs, calculate ROI, or claim device, geography or source explanations. Any such drilldown must issue a real authorized query over the selected entity scope, with the required joint dimensions, filters and periods. Marginal rankings from separate queries cannot be combined into an invented joint distribution or causal explanation.
