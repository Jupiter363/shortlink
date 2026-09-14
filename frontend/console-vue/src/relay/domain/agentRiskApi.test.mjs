import test from 'node:test'
import assert from 'node:assert/strict'
import { agentApi, riskApi } from '../api/agentRisk.js'
import { clearSession, setSession } from '../core/session.js'

async function withTransport(run) {
  const originalFetch = globalThis.fetch
  const requests = []
  setSession({ username: 'test-user', token: 'opaque-local-test-token' })
  globalThis.fetch = async (url, options) => {
    requests.push({ url, options, body: options.body ? JSON.parse(options.body) : undefined })
    return new Response(JSON.stringify({ code: '0', data: { marker: 'real-envelope-shape' } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })
  }
  try {
    await run(requests)
  } finally {
    globalThis.fetch = originalFetch
    clearSession()
  }
}

test('Agent uses only public Admin routes, auth headers and the exact three-field DTO', async () => {
  await withTransport(async (requests) => {
    const health = await agentApi.health()
    assert.equal(health.marker, 'real-envelope-shape')
    await agentApi.chat({
      agentType: 'campaign-analysis',
      sessionId: 's-1',
      message: '读取分组',
      username: 'spoof'
    })
    assert.equal(requests[0].url, '/api/short-link/admin/v1/agent/health')
    assert.equal(requests[1].url, '/api/short-link/admin/v1/agent/chat')
    assert.equal(requests[1].options.method, 'POST')
    assert.deepEqual(requests[1].body, {
      agentType: 'campaign-analysis',
      sessionId: 's-1',
      message: '读取分组'
    })
    assert.equal(requests[1].options.headers.get('Username'), 'test-user')
    for (const entry of requests) {
      assert.ok(!entry.url.includes('/internal/'))
      assert.equal(entry.options.headers.get('X-Service-Key'), null)
    }
  })
})

test('risk reads preserve resource identity, server paging and opaque policy cursors', async () => {
  await withTransport(async (requests) => {
    await riskApi.overview('gid/a')
    await riskApi.cards('gid/a')
    await riskApi.detail({ gid: 'g', domain: 'short.example:8080', shortUri: 'AbC9' })
    await riskApi.events({
      gid: 'g',
      targetType: 'SHORT_LINK',
      domain: 'short.example:8080',
      shortUri: 'AbC9',
      pageNo: 2
    })
    await riskApi.policies('9223372036854775806', 'cursor:+/opaque')
    assert.equal(requests[0].url, '/api/short-link/admin/v1/risk/groups/gid%2Fa/overview')
    assert.equal(requests[1].url, '/api/short-link/admin/v1/risk/groups/gid%2Fa/short-links')
    const detail = new URL(requests[2].url, 'http://local.test')
    assert.equal(detail.searchParams.get('domain'), 'short.example:8080')
    assert.equal(detail.searchParams.get('shortUri'), 'AbC9')
    const events = new URL(requests[3].url, 'http://local.test')
    assert.equal(events.searchParams.get('pageNo'), '2')
    assert.equal(events.searchParams.get('pageSize'), '10')
    const policy = new URL(requests[4].url, 'http://local.test')
    assert.equal(policy.searchParams.get('linkId'), '9223372036854775806')
    assert.equal(policy.searchParams.get('cursor'), 'cursor:+/opaque')
  })
})

test('manual review, policy revocation and command lookup remain independent endpoints', async () => {
  await withTransport(async (requests) => {
    await riskApi.review({
      targetType: 'GROUP',
      gid: 'g',
      reviewAction: 'WATCH',
      reviewNote: '人工观察',
      reviewer: 'spoof'
    })
    await riskApi.disable('policy/a', {
      gid: 'g',
      linkId: '123',
      commandId: 'relay-original-command',
      reason: '核验',
      reviewer: 'spoof'
    })
    await riskApi.command('relay-original-command')
    assert.equal(requests[0].url, '/api/short-link/admin/v1/risk/reviews')
    assert.ok(!Object.hasOwn(requests[0].body, 'reviewer'))
    assert.equal(requests[1].url, '/api/short-link/admin/v1/risk/policies/policy%2Fa/disable')
    assert.equal(requests[1].body.commandId, 'relay-original-command')
    assert.ok(!Object.hasOwn(requests[1].body, 'reviewer'))
    assert.equal(requests[2].url, '/api/short-link/admin/v1/risk/commands/relay-original-command')
    assert.equal(requests[2].options.method, 'GET')
    assert.equal(requests.filter((request) => request.url.endsWith('/disable')).length, 1)
  })
})
