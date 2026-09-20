---
name: dimension-change
description: Compare complete province-by-device evidence for an actual sealed selection pair visible to the current source MODEL, through a non-exploring native capability CALL.
---

# Dimension change, version 3

## Native CALL inputs and authority

This version is a non-exploring capability invoked by the existing native ReactAgent. It does not start another model loop, construct a Plan step or register an executor. The server admits one real CALL with its source MODEL response and retains that CALL identity throughout waiting and continuation.

Use exactly four input ports: periods, definition, selectedEntities and selectionEvidence. The first two select frozen INPUT names already exposed to the actual exploration step. The two artifacts select genuine ARTIFACT IDs visible in this CALL's source MODEL inputs. Do not use STEP_OUTPUT bindings, hidden inputs, invented artifact IDs, URLs, SQL or executable expressions. Parameters must be an empty object.

The definition is dimension-change-definition/v3, containing periodsRef, gid, baseline, target, dimensions, filters and the pinned dimension-change/3 method. It contains no scopeRef. Each period contains its exact periodsRef, startDate, endDate and timeZone. Use the approved version and actual native-parsed content digest; this document grants no authority.

The server resolves the two actual artifacts and verifies that they are the same sealed selectedEntities/selectionEvidence pair. It checks current access, owner, run, revision, source hashes and their actual source dates. A prior CALL can have published this pair while the enclosing exploration Step is still running; do not require a fake successful producer Step or fabricate a dependency.

Read the original ScopeArtifact referenced by the pair under current authorization. Match its actual scope, group and both date windows to the frozen definition and the exploration policy's scopeRef and periodsRef. Visibility in a previous model response is not a lasting permission grant. Recheck the source MODEL, pair, original scope, method pin and current rights before each actual operation.

## Derived scope and real joint queries

Publish a genuine SelectedScopeArtifact from the exact selected members, sorted by link ID and hashed over the whole selection. Preserve the pair's selectionComplete and emptyReason. Its inherited enumeration version describes source membership, not current authorization or a common historical snapshot. Do not manufacture a ScopeArtifact, GroupMembersPage or full-group claim for this subset.

Use only the ordered joint dimensions province and device. Apply the frozen IN and IS_UNKNOWN filters exactly. Each cohort contains at most 500 selected members. Query both frozen periods for the same derived cohort through the real asynchronous DIMENSION_BREAKDOWN frozen selected-member endpoint. Its derived scopeRef intentionally differs from the original policy scope; the server verifies that derivation from the actual pair.

Every query, derived scope publication and comparison publication belongs to the real CALL action. Logical slots include the CALL identity and position but never a retry attempt or mutable query parameters. Request hashes bind the actual version, frozen arguments and derived scope. Changed arguments must conflict with the original child rather than create a replacement identity.

Wait using the durable Skill invocation and actual child receipts. Resume through the existing callback and ledger gates, preserving job IDs, requests, pages and release receipts. Reuse READY results and completed LOCAL publications without resubmitting or recalculating them. A pending observation contains the Skill CALL reference; it does not authorize another model turn before the pending work is resolved.

## Evidence and numerical limits

Verify every source page and its complete joint bucket distribution before comparison. Missing pages, a top list or TOO_LARGE cannot become complete evidence. The analysis unit is SHARD_COHORT: one exact selected cohort across two periods, not a per-link distribution, full-group aggregate or independent whole-selection total.

Preserve KNOWN values exactly and distinguish UNKNOWN from NOT_APPLICABLE. An absent counterpart bucket may mean zero observed matching clicks only after complete scope, query, filter and bucket-total verification. It does not prove zero real visits or complete collection.

Bucket PV must reconcile with that period's independent filtered-window summary and ratioDenominator. Preserve both denominators when comparing shares. A zero baseline has no relative percentage change. Use independently deduplicated UV and UIP from the actual query summaries, preserving approximation metadata; never sum them across buckets, dates, links or cohorts.

Keep conclusions OBSERVED_ONLY and preserve collection-quality UNKNOWN, missing metrics, coverage and comparability limitations. Different query snapshots do not establish a common database snapshot. Structural completeness is not evidence of causality, object lifetime or complete telemetry.

## Empty selections and publication

For a real empty selection with NO_DECLINES, publish SelectedScope and the final dimensionChanges manifest with NOT_APPLICABLE, issuing no statistics requests. For INSUFFICIENT_EVIDENCE, also issue no statistics requests and retain that disposition. Neither case proves that dimensions were unchanged. A nonempty partial selection describes only its explicit members and retains its source limits.

Publish bounded LOCAL comparison pages and a final manifest referencing their verified chain. Expiry must not exceed any source. The completed observation refers to the real Skill invocation and named dimensionChanges artifact; it does not contain bulk result rows, private model reasoning or a fabricated success claim. Skill completion does not mark the user's goal ANSWERED and does not bypass common report assembly or server assessment.
