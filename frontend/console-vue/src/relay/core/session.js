const KEY = 'shortlink.session.v1'
let volatileSession = null

function storage(name) {
  try {
    return globalThis[name]
  } catch {
    return null
  }
}

export function getSession() {
  if (volatileSession) return { ...volatileSession }
  for (const [name, remember] of [
    ['localStorage', true],
    ['sessionStorage', false]
  ]) {
    try {
      const value = JSON.parse(storage(name)?.getItem(KEY) || 'null')
      if (
        value &&
        typeof value.token === 'string' &&
        value.token &&
        typeof value.username === 'string' &&
        value.username
      ) {
        return { token: value.token, username: value.username, remember }
      }
    } catch {
      /* Unreadable persisted data is not a valid session. */
    }
  }
  return null
}

export function clearSession() {
  volatileSession = null
  for (const name of ['localStorage', 'sessionStorage']) {
    try {
      const target = storage(name)
      for (const key of [KEY, 'token', 'username']) target?.removeItem(key)
    } catch {
      /* Memory is already cleared even if storage is unavailable. */
    }
  }
  // Retire the old console cookies as well, so a reload cannot resurrect an old login.
  if (typeof document !== 'undefined') {
    document.cookie = 'token=; Max-Age=0; Path=/; SameSite=Lax'
    document.cookie = 'username=; Max-Age=0; Path=/; SameSite=Lax'
  }
}

export function setSession({ username, token, remember = false }) {
  clearSession()
  if (!username || !token) throw new Error('登录响应缺少有效会话')
  const value = { username: String(username), token: String(token) }
  volatileSession = { ...value, remember: Boolean(remember) }
  try {
    storage(remember ? 'localStorage' : 'sessionStorage')?.setItem(KEY, JSON.stringify(value))
  } catch {
    /* Keep an in-memory login in restrictive browsers. */
  }
  return { ...volatileSession }
}

export function readRememberedUsername() {
  return getSession()?.username || ''
}

export function sameSession(left, right) {
  return Boolean(left?.token && left.token === right?.token && left.username === right?.username)
}

export const SESSION_KEY = KEY
