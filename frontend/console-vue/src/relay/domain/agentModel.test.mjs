import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildChatBody,
  compileMessage,
  errorMessage,
  newAgentSession,
  normalizeAgentResult,
  sanitize,
  safeText
} from './agentModel.js'

test('chat DTO only carries three permitted browser fields', () => {
  const body = buildChatBody({
    sessionId: 'session-a',
    agentType: 'security-risk',
    message: '查看风险',
    username: 'spoofed',
    serviceKey: 'secret',
    authVersion: 1
  })
  assert.deepEqual(body, {
    sessionId: 'session-a',
    agentType: 'security-risk',
    message: '查看风险'
  })
})
test('chat validates message, type and the full 2000-character budget', () => {
  const base = { sessionId: 'a', agentType: 'campaign-analysis' }
  assert.throws(() => buildChatBody({ ...base, message: ' ' }))
  assert.throws(() => buildChatBody({ ...base, message: 'a'.repeat(2001) }))
  assert.throws(() => buildChatBody({ ...base, agentType: 'other', message: 'a' }))
  assert.equal(buildChatBody({ ...base, message: 'a'.repeat(2000) }).message.length, 2000)
})
test('scope compiles into server-readable gid with no Chinese punctuation suffix', () => {
  const message = compileMessage('查看最近 7 天统计', { id: 'g_123', name: '秋日投放' })
  const match = message.match(/(gid|fullShortUrl|startDate|endDate)\s*[:=：]\s*([^\s,;，；]+)/)
  assert.equal(match[2], 'g_123')
  assert.equal(compileMessage(' ', { id: 'g' }), '')
  assert.equal(compileMessage('查看已授权范围'), '查看已授权范围')
})
test('independent sessions get different IDs and independent history', () => {
  const first = newAgentSession('campaign-analysis')
  const second = newAgentSession('security-risk')
  assert.notEqual(first.id, second.id)
  first.history.push({ answer: 'test' })
  assert.equal(second.history.length, 0)
  assert.equal(second.runState, 'READY')
})
test('absent result does not invent an answer or completed tool timeline', () => {
  assert.throws(() => normalizeAgentResult(null))
  assert.throws(() => normalizeAgentResult({ cards: [] }))
  const result = normalizeAgentResult({ answer: '', cards: null, toolCalls: null })
  assert.deepEqual(result.toolCalls, [])
  assert.deepEqual(result.traceEvents, [])
  assert.equal(result.answer, '')
})
test('actual tool failure and missing status remain distinct from completion', () => {
  const result = normalizeAgentResult({
    answer: '证据部分缺失',
    toolCalls: [
      { name: 'stats', success: false, message: 'Unavailable' },
      { name: 'groups', success: true },
      { name: 'other' }
    ]
  })
  assert.deepEqual(
    result.toolCalls.map((tool) => tool.tone),
    ['danger', 'success', 'unknown']
  )
  assert.equal(result.toolCalls[2].outcome, '未提供状态')
})
test('redaction covers credentials, nested headers, IPs and identity hashes without mutating input', () => {
  const raw = {
    token: 'private-token',
    nested: {
      serviceKey: 'private-service',
      ip: '12.34.56.78',
      ipHash: 'a'.repeat(64),
      visitorHash: 'b'.repeat(64),
      headers: { Token: 'private-header' }
    },
    answer: 'Bearer abc.def https://service/x?token=private-query sk-1234567890abcdefgh',
    traceId: 'trace-readable'
  }
  const output = sanitize(raw)
  const text = JSON.stringify(output)
  for (const sensitive of [
    'private-token',
    'private-service',
    'private-header',
    'private-query',
    'abc.def',
    '1234567890abcdefgh',
    '12.34.56.78',
    'a'.repeat(64)
  ])
    assert.ok(!text.includes(sensitive))
  assert.equal(output.nested.ip, '12.34.*.*')
  assert.equal(output.traceId, 'trace-readable')
  assert.equal(raw.token, 'private-token')
})
test('access-record cards use the existing canonical privacy mapper', () => {
  const result = normalizeAgentResult({
    answer: '记录',
    cards: [
      {
        type: 'access_records',
        rows: [
          {
            ip: '12.34.56.78',
            visitorHash: 'a'.repeat(64),
            kind: 'CLICK',
            status: 429,
            network: '运营商',
            visitorType: 'NEW'
          }
        ]
      }
    ]
  })
  const row = result.cards[0].rows[0]
  assert.equal(row.ip, '12.34.*.*')
  assert.equal(row.status, '429')
  assert.equal(row.eventType, 'CLICK')
  assert.equal(row.network, '运营商')
  assert.ok(!JSON.stringify(result).includes('a'.repeat(64)))
})
test('free-form diagnostics mask IPv6 and credentials while preserving ordinary timestamps', () => {
  const text = safeText('14:02:03 IP 2001:db8:85a3::8a2e:370:7334 apiKey=private-value')
  assert.ok(text.includes('14:02:03'))
  assert.ok(!text.includes('8a2e:370:7334'))
  assert.ok(!text.includes('private-value'))
  assert.equal(safeText(text), text)
  const url = safeText('https://example.test/?token=private-url')
  assert.equal(safeText(url), url)
})
test('timeouts remain uncertain instead of triggering automatic retry advice', () => {
  assert.match(errorMessage({ code: 'TIMEOUT' }), /结果尚不确定/)
  assert.match(errorMessage({ status: 403 }), /没有/)
  assert.match(errorMessage({ status: 429, retryAfter: 12 }), /12 秒/)
})
