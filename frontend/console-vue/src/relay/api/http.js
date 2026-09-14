import { getSession, sameSession } from '../core/session.js'

export class ApiError extends Error {
  constructor(
    message,
    { status = 0, code = 'NETWORK_ERROR', details = null, retryAfter = null } = {}
  ) {
    super(message)
    this.name = 'ApiError'
    Object.assign(this, { status, code, details, retryAfter })
  }
}

let sessionExpired = null
export function onSessionExpired(handler) {
  sessionExpired = handler
}

// Route IDs, command versions and job cursors can exceed Number.MAX_SAFE_INTEGER.
export function parseJson(text) {
  return JSON.parse(
    text.replace(/"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g, (token) => {
      if (token.startsWith('"') || /[.eE]/.test(token)) return token
      const integer = BigInt(token)
      return integer > BigInt(Number.MAX_SAFE_INTEGER) || integer < BigInt(Number.MIN_SAFE_INTEGER)
        ? `"${token}"`
        : token
    })
  )
}

export function apiUrl(path, query) {
  if (typeof path !== 'string' || !path.startsWith('/api/short-link/') || /[\r\n\\]/.test(path)) {
    throw new ApiError('接口地址无效', { code: 'INVALID_API_PATH' })
  }
  const url = new URL(path, globalThis.location?.origin || 'http://localhost')
  if (!url.pathname.startsWith('/api/short-link/'))
    throw new ApiError('接口地址无效', { code: 'INVALID_API_PATH' })
  for (const [key, value] of Object.entries(query || {})) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) value.forEach((item) => url.searchParams.append(key, String(item)))
    else url.searchParams.set(key, String(value))
  }
  return url.pathname + url.search
}

function retrySeconds(value) {
  if (!value) return null
  const seconds = Number(value)
  if (Number.isFinite(seconds)) return Math.max(0, Math.ceil(seconds))
  const date = Date.parse(value)
  return Number.isFinite(date) ? Math.max(0, Math.ceil((date - Date.now()) / 1000)) : null
}

function messageFor(status) {
  return (
    {
      401: '登录已过期，请重新登录',
      403: '当前账户无权访问这项内容',
      404: '请求的内容已不存在',
      409: '内容已变化，请刷新后重新确认',
      413: '请求内容过大，请减少后重试',
      429: '请求较多，请稍后重试',
      502: '服务暂时不可用，请稍后重试',
      503: '服务暂时不可用，请稍后重试',
      504: '等待响应超时，请先核实原操作结果'
    }[status] || '请求未完成，请稍后重试'
  )
}

export async function request(path, options = {}) {
  const {
    method = 'GET',
    query,
    body,
    signal,
    timeoutMs = 15000,
    raw = false,
    headers: extraHeaders,
    auth = true
  } = options
  const url = apiUrl(path, query)
  const startedSession = auth ? getSession() : null
  const controller = new AbortController()
  let timedOut = false
  const abort = () => controller.abort(signal?.reason)
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  try {
    const headers = new Headers(extraHeaders || {})
    headers.set('Accept', raw ? '*/*' : 'application/json')
    if (startedSession) {
      headers.set('Token', startedSession.token)
      headers.set('Username', startedSession.username)
    }
    if (body !== undefined) headers.set('Content-Type', 'application/json')
    const response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error'
    })
    if (raw && response.ok && !response.headers.get('Content-Type')?.includes('json')) {
      // Keep cancellation and timeout active until the entire file has arrived.
      const blob = await response.blob()
      return { headers: response.headers, blob: async () => blob }
    }
    const text = await response.text()
    let envelope
    try {
      envelope = text ? parseJson(text) : null
    } catch {
      throw new ApiError(messageFor(response.status), {
        status: response.status,
        code: 'INVALID_RESPONSE'
      })
    }
    const isEnvelope = envelope && typeof envelope === 'object' && Object.hasOwn(envelope, 'code')
    const failed = !response.ok || (isEnvelope && String(envelope.code) !== '0')
    if (failed) {
      const code = String(envelope?.code || response.status)
      if (
        (response.status === 401 || code === 'INVALID_SESSION') &&
        startedSession &&
        sameSession(startedSession, getSession())
      )
        sessionExpired?.()
      const safeMessage =
        typeof envelope?.message === 'string' && envelope.message.length < 800
          ? envelope.message
          : messageFor(response.status)
      throw new ApiError(safeMessage || messageFor(response.status), {
        status: response.status,
        code,
        details: envelope?.data ?? null,
        retryAfter: retrySeconds(response.headers.get('Retry-After'))
      })
    }
    if (raw)
      throw new ApiError('导出接口没有返回文件，请核对任务状态后重试', {
        status: response.status,
        code: 'INVALID_EXPORT_RESPONSE'
      })
    return isEnvelope ? envelope.data : envelope
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (signal?.aborted) throw new DOMException('请求已取消', 'AbortError')
    if (timedOut)
      throw new ApiError('等待响应超时，请先核实原操作结果', { status: 0, code: 'TIMEOUT' })
    if (error?.name === 'AbortError') throw error
    throw new ApiError('网络连接失败，请检查网络后重试')
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

export async function download(path, options = {}, filename = 'download') {
  const response = await request(path, {
    ...options,
    raw: true,
    timeoutMs: options.timeoutMs || 60000
  })
  const disposition = response.headers.get('Content-Disposition') || ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  let name = filename
  try {
    name = encoded ? decodeURIComponent(encoded) : plain || filename
  } catch {
    /* Use the local fallback name for malformed headers. */
  }
  name = [...name]
    .map((char) => (char.charCodeAt(0) < 32 || '/\\'.includes(char) ? '_' : char))
    .join('')
    .slice(0, 180)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 5000)
  return { filename: name, size: blob.size }
}

export function createRequestId(prefix = 'relay') {
  return `${prefix}-${globalThis.crypto.randomUUID()}`
}
