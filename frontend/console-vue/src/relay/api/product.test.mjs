import assert from 'node:assert/strict'
import test from 'node:test'
import { logout } from './product.js'
import { clearSession, setSession } from '../core/session.js'

test('logout uses the authenticated session headers without credentials in URL or body', async () => {
  const originalFetch = globalThis.fetch
  setSession({ username: 'test-user', token: 'opaque-local-test-token' })
  let sent
  globalThis.fetch = async (url, options) => {
    sent = { url, options }
    return new Response('{"code":"0","data":null}', {
      headers: { 'Content-Type': 'application/json' }
    })
  }
  try {
    await logout({ query: { token: 'obsolete-query-token' }, body: { token: 'obsolete-body' } })
    assert.equal(sent.url, '/api/short-link/admin/v1/user/logout')
    assert.equal(sent.options.method, 'DELETE')
    assert.equal(sent.options.body, undefined)
    assert.equal(sent.options.headers.get('Username'), 'test-user')
    assert.equal(sent.options.headers.get('Token'), 'opaque-local-test-token')
  } finally {
    globalThis.fetch = originalFetch
    clearSession()
  }
})
