import assert from 'node:assert/strict'
import test from 'node:test'

import {
  SESSION_KEY,
  clearSession,
  getSession,
  readRememberedUsername,
  sameSession,
  setSession
} from './session.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: (key) => (values.has(key) ? values.get(key) : null),
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key)
  }
}

function installBrowserState() {
  globalThis.localStorage = memoryStorage()
  globalThis.sessionStorage = memoryStorage()
  globalThis.document = { cookie: '' }
  clearSession()
}

test(
  'remembered login persists only in localStorage and can be read back',
  { concurrency: false },
  () => {
    installBrowserState()
    const session = setSession({ username: 'jupiter', token: 'token-remembered', remember: true })
    assert.deepEqual(session, { username: 'jupiter', token: 'token-remembered', remember: true })
    assert.deepEqual(JSON.parse(localStorage.getItem(SESSION_KEY)), {
      username: 'jupiter',
      token: 'token-remembered'
    })
    assert.equal(sessionStorage.getItem(SESSION_KEY), null)
    assert.equal(readRememberedUsername(), 'jupiter')
  }
)

test(
  'session login uses sessionStorage and clears stale legacy/local values',
  { concurrency: false },
  () => {
    installBrowserState()
    localStorage.setItem(SESSION_KEY, JSON.stringify({ username: 'old', token: 'old-token' }))
    localStorage.setItem('token', 'legacy-token')
    localStorage.setItem('username', 'legacy-user')
    const session = setSession({ username: 'current', token: 'current-token', remember: false })
    assert.deepEqual(session, { username: 'current', token: 'current-token', remember: false })
    assert.equal(localStorage.getItem(SESSION_KEY), null)
    assert.equal(localStorage.getItem('token'), null)
    assert.equal(localStorage.getItem('username'), null)
    assert.deepEqual(JSON.parse(sessionStorage.getItem(SESSION_KEY)), {
      username: 'current',
      token: 'current-token'
    })
  }
)

test(
  'logout clears volatile, persistent, session and legacy credentials',
  { concurrency: false },
  () => {
    installBrowserState()
    setSession({ username: 'jupiter', token: 'logout-token', remember: true })
    sessionStorage.setItem(SESSION_KEY, '{}')
    sessionStorage.setItem('token', 'legacy-token')
    sessionStorage.setItem('username', 'legacy-user')
    clearSession()
    assert.equal(getSession(), null)
    for (const target of [localStorage, sessionStorage]) {
      for (const key of [SESSION_KEY, 'token', 'username']) assert.equal(target.getItem(key), null)
    }
  }
)

test(
  'invalid stored payloads are ignored and session equality requires both identity fields',
  { concurrency: false },
  () => {
    installBrowserState()
    localStorage.setItem(SESSION_KEY, '{broken')
    sessionStorage.setItem(SESSION_KEY, JSON.stringify({ username: 'jupiter', token: '' }))
    assert.equal(getSession(), null)
    assert.equal(sameSession({ username: 'u', token: 't' }, { username: 'u', token: 't' }), true)
    assert.equal(sameSession({ username: 'u', token: 't' }, { username: 'u', token: 'new' }), false)
    assert.equal(sameSession(null, null), false)
  }
)

test('missing credentials never leave a partial session', { concurrency: false }, () => {
  installBrowserState()
  assert.throws(() => setSession({ username: 'jupiter', token: '' }), /有效会话/)
  assert.equal(getSession(), null)
})
