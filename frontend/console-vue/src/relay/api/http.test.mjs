import assert from 'node:assert/strict'
import test from 'node:test'

import { clearSession, setSession } from '../core/session.js'
import { ApiError, apiUrl, onSessionExpired, parseJson, request } from './http.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: (key) => (values.has(key) ? values.get(key) : null),
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key)
  }
}

function reset() {
  globalThis.localStorage = memoryStorage()
  globalThis.sessionStorage = memoryStorage()
  globalThis.document = { cookie: '' }
  globalThis.location = { origin: 'https://admin.local.test' }
  clearSession()
  onSessionExpired(null)
}

function jsonResponse(body, { status = 200, headers = {} } = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers }
  })
}

test(
  'JSON parser preserves unsafe integer IDs without changing safe integers or decimals',
  { concurrency: false },
  () => {
    assert.deepEqual(
      parseJson('{"id":9223372036854775807,"safe":42,"ratio":1.5,"text":"9223372036854775807"}'),
      {
        id: '9223372036854775807',
        safe: 42,
        ratio: 1.5,
        text: '9223372036854775807'
      }
    )
  }
)

test(
  'request sends auth headers, encodes repeated query values and unwraps success envelope',
  { concurrency: false },
  async () => {
    reset()
    setSession({ username: 'jupiter_user', token: 'opaque-token', remember: false })
    let seen
    globalThis.fetch = async (url, options) => {
      seen = { url, options }
      return new Response('{"code":"0","data":{"linkId":9223372036854775807}}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    }
    const value = await request('/api/short-link/admin/v1/page', {
      query: { gid: ['a b', '二'], empty: '' }
    })
    assert.deepEqual(value, { linkId: '9223372036854775807' })
    assert.equal(seen.url, '/api/short-link/admin/v1/page?gid=a+b&gid=%E4%BA%8C')
    assert.equal(seen.options.headers.get('Username'), 'jupiter_user')
    assert.equal(seen.options.headers.get('Token'), 'opaque-token')
    assert.equal(seen.options.credentials, 'same-origin')
    assert.equal(seen.options.redirect, 'error')
  }
)

test(
  'business failure in a 200 envelope becomes ApiError with backend code and details',
  { concurrency: false },
  async () => {
    reset()
    globalThis.fetch = async () =>
      jsonResponse({ code: 'TOO_LARGE', message: 'narrow scope', data: { limit: 500 } })
    await assert.rejects(request('/api/short-link/admin/v1/stats'), (error) => {
      assert.ok(error instanceof ApiError)
      assert.equal(error.status, 200)
      assert.equal(error.code, 'TOO_LARGE')
      assert.equal(error.message, 'narrow scope')
      assert.deepEqual(error.details, { limit: 500 })
      return true
    })
  }
)

test(
  'late 401 from an old session does not expire a newly established session',
  { concurrency: false },
  async () => {
    reset()
    setSession({ username: 'jupiter', token: 'old-token', remember: false })
    let release
    globalThis.fetch = () =>
      new Promise((resolve) => {
        release = resolve
      })
    let expirations = 0
    onSessionExpired(() => {
      expirations++
    })
    const pending = request('/api/short-link/admin/v1/group')
    setSession({ username: 'jupiter', token: 'new-token', remember: false })
    release(jsonResponse({ code: 'INVALID_SESSION', message: 'expired' }, { status: 401 }))
    await assert.rejects(pending, (error) => error instanceof ApiError && error.status === 401)
    assert.equal(expirations, 0)
  }
)

test(
  'current-session 401 invokes the expiration owner exactly once',
  { concurrency: false },
  async () => {
    reset()
    setSession({ username: 'jupiter', token: 'current-token', remember: false })
    let expirations = 0
    onSessionExpired(() => {
      expirations++
    })
    globalThis.fetch = async () =>
      jsonResponse({ code: 'INVALID_SESSION', message: 'expired' }, { status: 401 })
    await assert.rejects(request('/api/short-link/admin/v1/group'), ApiError)
    assert.equal(expirations, 1)
  }
)

test(
  'external, backslash and decoded prefix-escape paths are rejected before fetch',
  { concurrency: false },
  () => {
    reset()
    let calls = 0
    globalThis.fetch = async () => {
      calls++
      return jsonResponse({ code: '0', data: null })
    }
    for (const path of [
      'https://evil.test/api/short-link/admin/v1/group',
      '/api/short-link/../outside',
      '/api/short-link/%2e%2e/outside',
      '/api/short-link/admin\\v1/group',
      '/api/short-link/admin/v1/group\r\nX-Test: injected'
    ])
      assert.throws(
        () => apiUrl(path),
        (error) => error instanceof ApiError && error.code === 'INVALID_API_PATH'
      )
    assert.equal(calls, 0)
  }
)

test(
  'caller cancellation remains AbortError and reaches fetch signal',
  { concurrency: false },
  async () => {
    reset()
    const caller = new AbortController()
    let fetchSignal
    globalThis.fetch = (_url, options) =>
      new Promise((_resolve, reject) => {
        fetchSignal = options.signal
        options.signal.addEventListener(
          'abort',
          () => reject(new DOMException('aborted', 'AbortError')),
          { once: true }
        )
      })
    const pending = request('/api/short-link/admin/v1/group', {
      signal: caller.signal,
      timeoutMs: 1000
    })
    caller.abort('user')
    await assert.rejects(
      pending,
      (error) => error?.name === 'AbortError' && error.message === '请求已取消'
    )
    assert.equal(fetchSignal.aborted, true)
  }
)

test(
  'timeout aborts fetch and returns stable TIMEOUT ApiError',
  { concurrency: false },
  async () => {
    reset()
    globalThis.fetch = (_url, options) =>
      new Promise((_resolve, reject) => {
        options.signal.addEventListener(
          'abort',
          () => reject(new DOMException('aborted', 'AbortError')),
          { once: true }
        )
      })
    await assert.rejects(request('/api/short-link/admin/v1/group', { timeoutMs: 5 }), (error) => {
      assert.ok(error instanceof ApiError)
      assert.equal(error.code, 'TIMEOUT')
      assert.equal(error.status, 0)
      return true
    })
  }
)

test(
  'raw response body download remains covered by the request timeout',
  { concurrency: false },
  async () => {
    reset()
    globalThis.fetch = async (_url, options) => ({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'text/csv' }),
      blob: () =>
        new Promise((_resolve, reject) => {
          options.signal.addEventListener(
            'abort',
            () => reject(new DOMException('aborted', 'AbortError')),
            { once: true }
          )
        })
    })
    await assert.rejects(
      request('/api/short-link/admin/v1/batches/job/export', {
        raw: true,
        timeoutMs: 5
      }),
      (error) => {
        assert.ok(error instanceof ApiError)
        assert.equal(error.code, 'TIMEOUT')
        assert.equal(error.status, 0)
        return true
      }
    )
  }
)
