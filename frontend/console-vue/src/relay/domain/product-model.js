export const BATCH_LIMIT_BYTES = 8 * 1024 * 1024
export const TERMINAL_STATES = new Set(['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED'])
export const JOB_LABELS = {
  VALIDATING: '校验中',
  READY: '等待执行',
  RUNNING: '创建中',
  SUCCEEDED: '全部完成',
  PARTIAL_SUCCESS: '部分完成',
  FAILED: '创建失败',
  CANCELLED: '已取消'
}
export const SUPPORTED_ORDERS = new Set([
  'createTime',
  'todayPv',
  'todayUv',
  'todayUip',
  'totalPv',
  'totalUv',
  'totalUip'
])

export function countOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

export function absoluteShortUrl(
  value,
  publicOrigin = import.meta.env?.VITE_SHORTLINK_PUBLIC_ORIGIN
) {
  const raw = typeof value === 'object' ? value?.fullShortUrl : value
  if (!raw || typeof raw !== 'string') return ''
  try {
    const url = new URL(raw.includes('://') ? raw : `https://${raw}`)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return ''
    // A deployment may declare HTTP for its own local host, without rewriting unrelated links.
    const candidate = publicOrigin || (typeof value === 'object' ? value.domain : '')
    if (candidate) {
      try {
        const origin = new URL(candidate)
        if (
          ['http:', 'https:'].includes(origin.protocol) &&
          !origin.username &&
          !origin.password &&
          origin.host === url.host &&
          origin.pathname === '/' &&
          !origin.search &&
          !origin.hash
        )
          url.protocol = origin.protocol
      } catch {
        /* An invalid override cannot change the persisted address. */
      }
    }
    return url.href
  } catch {
    return ''
  }
}

export function dateEpoch(value) {
  if (!value) return null
  const text = String(value)
  const parsed = new Date(
    /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?$/.test(text)
      ? `${text.replace(' ', 'T')}${text.length === 16 ? ':00' : ''}+08:00`
      : text
  ).getTime()
  return Number.isFinite(parsed) ? parsed : null
}

export function displayDate(value) {
  const epoch = dateEpoch(value)
  return epoch === null
    ? ''
    : new Intl.DateTimeFormat('sv-SE', {
        timeZone: 'Asia/Shanghai',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
      }).format(new Date(epoch))
}

export function expiryStatus(expires, now = Date.now()) {
  if (!expires) return 'normal'
  const epoch = dateEpoch(expires)
  return epoch === null
    ? 'unknown'
    : epoch <= now
      ? 'expired'
      : epoch - now <= 3 * 86400000
        ? 'expiring'
        : 'normal'
}

export function mapGroup(raw) {
  if (!raw?.gid) throw new Error('分组响应缺少标识。')
  return {
    id: String(raw.gid),
    name: String(raw.name || ''),
    sortOrder: countOrNull(raw.sortOrder),
    count: countOrNull(raw.shortLinkCount)
  }
}

export function mapLink(raw, recycled = false) {
  if (!raw || (raw.linkId == null && raw.id == null)) throw new Error('短链接响应缺少标识。')
  const address =
    raw.fullShortUrl ||
    (raw.domain && raw.shortUri ? `${raw.domain.replace(/\/$/, '')}/${raw.shortUri}` : '')
  const absolute = absoluteShortUrl(address)
  const expires = raw.validDateType === 0 ? null : raw.validDate || null
  return {
    id: String(raw.linkId ?? raw.id),
    code: raw.shortUri || (absolute ? new URL(absolute).pathname.slice(1) : ''),
    fullShortUrl: address,
    domain: raw.domain || (absolute ? new URL(absolute).host : ''),
    url: raw.originUrl || '',
    title: raw.describe || raw.title || raw.originUrl || '未填写描述',
    description: raw.describe || '',
    groupId: String(raw.gid || ''),
    created: displayDate(raw.createTime),
    expires,
    status: expiryStatus(expires),
    pv: countOrNull(raw.totalPv),
    uv: countOrNull(raw.totalUv),
    uip: countOrNull(raw.totalUip),
    todayPv: countOrNull(raw.todayPv),
    todayUv: countOrNull(raw.todayUv),
    todayUip: countOrNull(raw.todayUip),
    version: countOrNull(raw.routeVersion),
    recycled: recycled || raw.enableStatus === 1,
    favicon: raw.favicon || '',
    metadataStatus: raw.metadataStatus || null
  }
}

export function mapPage(raw, recycled = false) {
  const total = countOrNull(raw?.total),
    current = countOrNull(raw?.current),
    size = countOrNull(raw?.size)
  if (!raw || !Array.isArray(raw.records) || total === null || !current || !size)
    throw new Error('短链接列表响应不完整。')
  return {
    records: raw.records.map((link) => mapLink(link, recycled)),
    total,
    current,
    size,
    pages: countOrNull(raw.pages) ?? Math.ceil(total / size),
    statsMeta: raw.statsMeta || null
  }
}

export function passwordError(value) {
  return typeof value !== 'string' ||
    value.length < 8 ||
    value.length > 15 ||
    value.includes('\0') ||
    new TextEncoder().encode(value).byteLength > 72
    ? '密码需要 8–15 位字符。'
    : ''
}

export function validUsername(value) {
  return /^[A-Za-z0-9_-]{3,64}$/.test(value || '')
}
export function validOriginUrl(value) {
  if (
    typeof value !== 'string' ||
    value.length > 2048 ||
    Array.from(value).some((char) => char.charCodeAt(0) <= 32)
  )
    return false
  try {
    const url = new URL(value)
    return (
      ['http:', 'https:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password
    )
  } catch {
    return false
  }
}

export function createRequestId(prefix = 'create') {
  const bytes = new Uint8Array(16)
  globalThis.crypto.getRandomValues(bytes)
  return `${prefix}-${Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')}`
}

export function prepareCreateAttempt(body, existing, nextId = createRequestId) {
  const signature = JSON.stringify({ ...body, requestId: undefined })
  if (existing?.uncertain && existing.signature !== signature)
    throw new Error('上次创建结果尚未确认。请先重试原来的内容，不要重复创建。')
  if (existing?.signature === signature) return existing
  return {
    body: existing ? { ...body, requestId: nextId('retry') } : { ...body },
    signature,
    uncertain: false
  }
}

export function createBody(form, requestId) {
  if (!validOriginUrl(form.url))
    throw new Error('请输入有效的 http:// 或 https:// 原始链接，最长 2048 个字符。')
  if (!form.groupId) throw new Error('请选择分组。')
  if (!String(form.title || '').trim() || form.title.length > 1024)
    throw new Error('描述需要 1–1024 个字符。')
  const custom = form.validity === 'custom'
  const validDate = custom
    ? `${String(form.expires || '')
        .replace('T', ' ')
        .slice(0, 16)}:00`
    : null
  if (custom && (!dateEpoch(validDate) || dateEpoch(validDate) <= Date.now()))
    throw new Error('请选择未来的有效期（北京时间）。')
  return {
    requestId,
    originUrl: form.url,
    gid: form.groupId,
    createdType: 0,
    validDateType: custom ? 1 : 0,
    validDate,
    describe: form.title.trim()
  }
}

export function lifecycleBody(link) {
  if (
    !link?.fullShortUrl ||
    !link.groupId ||
    !Number.isSafeInteger(link.version) ||
    link.version < 1
  )
    throw new Error('短链接的最新版本信息不完整，请刷新后再试。')
  return { fullShortUrl: link.fullShortUrl, gid: link.groupId, expectedVersion: link.version }
}

export function updateBody(form, link) {
  const create = createBody(form, undefined)
  return {
    ...lifecycleBody(link),
    originGid: link.groupId,
    gid: form.groupId,
    originUrl: create.originUrl,
    validDateType: create.validDateType,
    validDate: create.validDate,
    describe: create.describe
  }
}

export function lines(value) {
  if (!String(value || '').trim()) return []
  return String(value)
    .replace(/\r/g, '')
    .replace(/\n+$/, '')
    .split('\n')
    .map((line) => line.trim())
}

export function inspectBatch(form, requestId = '') {
  const urls = lines(form.urls),
    titles = lines(form.titles)
  const body = {
    requestId,
    originUrls: urls,
    describes: titles.length ? titles : urls.map(() => ''),
    gid: form.groupId,
    createdType: 0,
    validDateType: 0,
    validDate: null
  }
  const bytes = new TextEncoder().encode(JSON.stringify(body)).byteLength
  return {
    body,
    urls,
    titles,
    count: urls.length,
    bytes,
    mismatch: titles.length > 0 && titles.length !== urls.length,
    tooLarge: bytes > BATCH_LIMIT_BYTES,
    isAsync: urls.length > 500,
    invalidCount: urls.filter((url) => !validOriginUrl(url)).length,
    longDescriptions: titles.some((title) => title.length > 1024)
  }
}

export function validateBatch(info) {
  if (info.count < 2 || info.count > 50000) throw new Error('每批需要 2–50,000 行链接。')
  if (info.mismatch) throw new Error('链接与描述的行数必须逐行匹配。')
  if (info.tooLarge) throw new Error('请求体超过 8 MiB，请减少内容后再提交。')
  if (info.longDescriptions) throw new Error('每条描述最多 1024 个字符。')
  if (!info.body.gid) throw new Error('请选择分组。')
  if (!info.isAsync && info.invalidCount)
    throw new Error(`有 ${info.invalidCount} 行链接格式不正确，请修改后再提交。`)
  return info.body
}

export function mapJob(raw, previous = {}) {
  if (!raw?.jobId || !Object.hasOwn(JOB_LABELS, raw.state))
    throw new Error('批量任务响应缺少有效状态。')
  return {
    ...previous,
    id: String(raw.jobId),
    jobId: String(raw.jobId),
    kind: 'ASYNC',
    state: raw.state,
    totalRows: countOrNull(raw.totalRows),
    validRows: countOrNull(raw.validRows),
    invalidRows: countOrNull(raw.invalidRows),
    succeededRows: countOrNull(raw.succeededRows),
    failedRows: countOrNull(raw.failedRows),
    error: raw.error || '',
    rows: previous.rows || []
  }
}

export function mapBatchRow(raw) {
  if (!raw || !Number.isSafeInteger(Number(raw.row)) || Number(raw.row) < 1)
    throw new Error('批量结果行号无效。')
  const result = raw.result || {}
  return {
    row: Number(raw.row),
    state: raw.state,
    status: raw.state,
    linkId: raw.linkId == null ? null : String(raw.linkId),
    title: result.describe || '',
    url: result.originUrl || '',
    fullShortUrl: result.fullShortUrl || '',
    shortUrl: absoluteShortUrl(result.fullShortUrl),
    error: raw.error || ''
  }
}

export function appendCursorRows(previous, incoming, after, limit = 20) {
  if (!Array.isArray(incoming) || incoming.length > limit) throw new Error('批量结果分页响应无效。')
  let cursor = after
  for (const row of incoming) {
    if (row.row <= cursor) throw new Error('批量结果游标未向前推进，请重新加载。')
    cursor = row.row
  }
  const prior = new Set(previous.map((row) => row.row))
  if (incoming.some((row) => prior.has(row.row)))
    throw new Error('批量结果出现重复行，请重新加载。')
  return { rows: [...previous, ...incoming], after: cursor, hasMore: incoming.length === limit }
}

export function profileBody(username, form) {
  const body = {
    username,
    realName: String(form.realName || '').trim(),
    mail: String(form.mail || '').trim()
  }
  if (body.realName.length > 64 || body.mail.length > 254)
    throw new Error('姓名或邮箱超出长度限制。')
  if (body.mail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(body.mail))
    throw new Error('请输入有效邮箱。')
  if (form.phone?.trim()) {
    if (form.phone.includes('*')) throw new Error('请填写完整的新手机号码，不要提交脱敏号码。')
    if (!/^[+\d ()-]{5,32}$/.test(form.phone)) throw new Error('请输入有效的手机号码。')
    body.phone = form.phone.trim()
  }
  if (form.password) {
    if (passwordError(form.password)) throw new Error(passwordError(form.password))
    if (!form.currentPassword) throw new Error('修改密码需要输入当前密码。')
    body.password = form.password
    body.currentPassword = form.currentPassword
  }
  return body
}

export function isConflict(error) {
  return (
    error?.status === 409 ||
    /VERSION|CONFLICT|版本|已变化|Source group changed/i.test(
      `${error?.code || ''} ${error?.message || ''}`
    )
  )
}
export function isAborted(error) {
  return error?.name === 'AbortError' || error?.code === 'ABORTED'
}
export function errorMessage(error, fallback = '操作未完成，请稍后重试。') {
  if (error?.status === 429) return '请求较多，请稍后重试。'
  if (error?.status === 401) return '登录已失效，请重新登录。'
  if (error?.status === 403) return '当前账户没有执行此操作的权限。'
  if (error?.status === 413) return '请求内容超过大小限制，请减少内容。'
  if (error?.status >= 500) return fallback
  return error?.message || fallback
}
