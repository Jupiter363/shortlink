export function createRequestId(prefix = 'console') {
  const suffix = window.crypto?.randomUUID
    ? window.crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
  return `${prefix}-${suffix}`
}

export function responseErrorMessage(error, fallback = '请求失败，请稍后重试') {
  const data = error?.response?.data
  if (typeof data === 'string' && data.trim()) return data
  return data?.message || error?.message || fallback
}
