import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildDisableBody,
  buildReviewBody,
  normalizeCard,
  normalizeCommand,
  normalizeOverview,
  normalizePolicies,
  newCommandId,
  metric,
  validLinkId
} from './riskModel.js'

test('UNKNOWN policy response cannot be interpreted as no restrictions', () => {
  const value = normalizePolicies({
    state: 'UNKNOWN',
    policies: [{ policyId: 'p', active: true }],
    nextCursor: 'x'
  })
  assert.equal(value.state, 'UNKNOWN')
  assert.deepEqual(value.policies, [])
  assert.equal(value.nextCursor, '')
  assert.equal(normalizePolicies(null).state, 'UNKNOWN')
})
test('known policy facts preserve lifecycle and cursor rather than assume all active', () => {
  const value = normalizePolicies({
    state: 'KNOWN_RESTRICTED',
    asOf: 123,
    policyRevision: 8,
    nextCursor: 'cursor-a',
    policies: [
      { policyId: 'p', active: true, revoked: false },
      { policyId: 'q', active: false, revoked: true }
    ]
  })
  assert.equal(value.nextCursor, 'cursor-a')
  assert.equal(value.policies[0].active, true)
  assert.equal(value.policies[1].revoked, true)
})
test('COMMITTED is separate from propagation and never claims globally applied', () => {
  const value = normalizeCommand(
    { commandId: 'command-a', commandState: 'COMMITTED', propagationState: 'PENDING' },
    'command-a'
  )
  assert.equal(value.state, 'COMMITTED')
  assert.equal(value.propagation, 'PENDING')
  assert.equal(value.tone, 'info')
  assert.equal(value.canQuery, false)
  assert.ok(!value.label.includes('全部'))
})
test('pending, missing, mismatched and future receipts stay unknown with original command ID', () => {
  for (const response of [
    null,
    {},
    { commandId: 'other', commandState: 'COMMITTED' },
    { commandId: 'cmd-a', commandState: 'PENDING_CONFIRMATION' },
    { commandId: 'cmd-a', commandState: 'NEW_FUTURE_STATE' }
  ]) {
    const value = normalizeCommand(response, 'cmd-a')
    assert.equal(value.commandId, 'cmd-a')
    assert.equal(value.state, 'UNKNOWN')
    assert.equal(value.canQuery, true)
    assert.equal(value.propagation, 'UNKNOWN')
  }
})
test('explicit command conflicts are not displayed as successfully executed', () => {
  for (const status of ['CONFLICT', 'EXPIRED', 'FAILED']) {
    const value = normalizeCommand({ commandId: 'cmd', commandState: status }, 'cmd')
    assert.equal(value.tone, 'danger')
    assert.equal(value.canQuery, false)
  }
})
test('overview preserves top-cards-only coverage and null group disable count', () => {
  const value = normalizeOverview({
    disabledCount: null,
    currentPolicyCoverage: 'TOP_CARDS_ONLY',
    groupRiskScore: null
  })
  assert.equal(value.disabledCount, null)
  assert.equal(metric(value.disabledCount), '—')
  assert.equal(value.currentPolicyCoverage, 'TOP_CARDS_ONLY')
  assert.equal(metric(value.groupRiskScore), '—')
  assert.equal(metric(0), '0')
})
test('card does not synthesize a safe risk or effective policy from missing facts', () => {
  const value = normalizeCard({ domain: 's.example', shortUri: 'abc' })
  assert.equal(value.riskScore, null)
  assert.equal(value.currentPolicy.state, 'UNKNOWN')
  assert.equal(value.linkId, null)
  assert.deepEqual(value.reasonCodes, [])
})
test('large numeric identifiers retain precision and unsafe JS numbers are rejected', () => {
  assert.equal(validLinkId('9223372036854775806'), true)
  assert.equal(normalizeCard({ linkId: '9223372036854775806' }).linkId, '9223372036854775806')
  assert.equal(validLinkId(Number('9223372036854775806')), false)
  assert.equal(validLinkId('0'), false)
})
test('disable only sends a valid stable command ID and authorized resource fields', () => {
  const commandId = newCommandId()
  const input = {
    commandId,
    linkId: '9223372036854775806',
    gid: 'g',
    reason: '核实误报',
    reviewer: 'spoofed',
    policyStatus: 'ACTIVE'
  }
  assert.deepEqual(buildDisableBody(input), {
    commandId,
    linkId: '9223372036854775806',
    gid: 'g',
    reason: '核实误报'
  })
  assert.throws(() => buildDisableBody({ commandId: 'short', linkId: 1 }))
  assert.throws(() => buildDisableBody({ commandId, linkId: '0' }))
})
test('review whitelist cannot invoke a policy action or spoof a reviewer', () => {
  const base = {
    targetType: 'SHORT_LINK',
    gid: 'g',
    domain: 's.example',
    shortUri: 'abc',
    reviewAction: 'FALSE_POSITIVE',
    reviewer: 'spoofed'
  }
  assert.ok(!Object.hasOwn(buildReviewBody(base), 'reviewer'))
  assert.throws(() => buildReviewBody({ ...base, reviewAction: 'DISABLE' }))
  assert.throws(() => buildReviewBody({ ...base, domain: '' }))
  const group = buildReviewBody({ ...base, targetType: 'GROUP' })
  assert.equal(group.domain, undefined)
  assert.equal(group.shortUri, undefined)
})
