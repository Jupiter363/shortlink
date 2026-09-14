export const SHANGHAI_TIME_ZONE = 'Asia/Shanghai'

export const ANALYTICS_DIMENSIONS = Object.freeze([
  { key: 'country', label: '国家' },
  { key: 'province', label: '省份' },
  { key: 'hour', label: '24 小时' },
  { key: 'weekday', label: '星期' },
  { key: 'ip', label: '高频 IP' },
  { key: 'os', label: '操作系统' },
  { key: 'browser', label: '浏览器' },
  { key: 'device', label: '设备' },
  { key: 'isp', label: '运营商 / ISP' },
  { key: 'newvisitor', label: '新老访客' }
])

const QUALITY_STATES = new Set(['AVAILABLE', 'EMPTY', 'PARTIAL', 'UNKNOWN'])
const EMPTY_TEXT = '—'

export class AnalyticsContractError extends Error {
  constructor(code, message, details = null) {
    super(message)
    this.name = 'AnalyticsContractError'
    this.code = code
    this.details = details
  }
}

function object(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function text(value) {
  if (value === null || value === undefined) return ''
  const normalized = String(value).trim()
  return /^(unknown|null|undefined|n\/a|-|—)$/i.test(normalized) ? '' : normalized
}

export function countOrNull(value) {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean')
    return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

function ratioOrNull(value) {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean')
    return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 && parsed <= 1 ? parsed : null
}

function epochOrNull(value) {
  if (value === null || value === undefined || value === '' || typeof value === 'boolean')
    return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null
}

function quality(raw, fallback = 'UNKNOWN') {
  const source = object(raw) ? raw : {}
  const status = QUALITY_STATES.has(source.status) ? source.status : fallback
  return {
    status,
    semantic: text(source.semantic),
    reason: text(source.reason),
    knownCount: countOrNull(source.knownCount),
    unknownCount: countOrNull(source.unknownCount ?? source.unknownUv),
    eligibleCount: countOrNull(source.eligibleCount),
    coverage: ratioOrNull(source.coverage),
    historyStart: epochOrNull(source.historyStart),
    historyEnd: epochOrNull(source.historyEnd),
    earliestObservedAt: epochOrNull(source.earliestObservedAt),
    maxHistoryDays: countOrNull(source.maxHistoryDays),
    historyProof: text(source.historyProof),
    scope: text(source.scope),
    reasonCounts: object(source.reasonCounts) ? { ...source.reasonCounts } : {}
  }
}

function normalizeMeta(raw) {
  if (!object(raw))
    throw new AnalyticsContractError('INVALID_STATS_META', '统计响应缺少快照元数据。')
  const nextCursor = typeof raw.nextCursor === 'string' ? text(raw.nextCursor) : null
  const snapshotId = typeof raw.snapshotId === 'string' ? text(raw.snapshotId) : null
  if (raw.snapshotId != null && !snapshotId) {
    throw new AnalyticsContractError('INVALID_SNAPSHOT_ID', '统计响应的快照标识无效。')
  }
  if (
    raw.nextCursor != null &&
    (typeof raw.nextCursor !== 'string' || !nextCursor || nextCursor.length > 8192 || !snapshotId)
  ) {
    throw new AnalyticsContractError('INVALID_ACCESS_CURSOR', '访问记录的快照游标无效。')
  }
  const completeness = ['COMPLETE', 'PARTIAL'].includes(raw.completeness)
    ? raw.completeness
    : 'UNKNOWN'
  return {
    snapshotId,
    nextCursor,
    requestedStart: epochOrNull(raw.requestedStart),
    requestedEnd: epochOrNull(raw.requestedEnd),
    effectiveEnd: epochOrNull(raw.effectiveEnd),
    snapshotCreatedAt: epochOrNull(raw.snapshotCreatedAt),
    snapshotExpiresAt: epochOrNull(raw.snapshotExpiresAt),
    generatedAt: epochOrNull(raw.generatedAt),
    completeness,
    availability: text(raw.availability) || 'UNKNOWN',
    freshness: text(raw.freshness) || 'UNKNOWN',
    provisional: raw.provisional === true,
    businessTimezone: text(raw.businessTimezone),
    dimensionQualityWindow: text(raw.dimensionQualityWindow),
    dimensionQuality: object(raw.dimensionQuality) ? raw.dimensionQuality : {},
    collectionQuality: object(raw.collectionQuality)
      ? raw.collectionQuality
      : { status: 'UNKNOWN', reasons: [] },
    approximation: object(raw.approximation) ? raw.approximation : {},
    missingMetrics: Array.isArray(raw.missingMetrics)
      ? raw.missingMetrics.map(text).filter(Boolean)
      : []
  }
}

function categoryRows(value, field) {
  if (!Array.isArray(value)) return null
  return value.flatMap((item) => {
    if (!object(item)) return []
    const label = text(item[field])
    const count = countOrNull(item.cnt)
    if (!label || count === null) return []
    return [
      { label, count, ratio: ratioOrNull(item.ratio), approximate: item.approximate === true }
    ]
  })
}

function histogram(value, expectedLength) {
  if (!Array.isArray(value) || value.length !== expectedLength) return null
  const parsed = value.map(countOrNull)
  return parsed.some((item) => item === null) ? null : parsed
}

function dailyRows(value) {
  if (!Array.isArray(value)) return null
  const rows = value.flatMap((item) => {
    if (!object(item) || !/^\d{4}-\d{2}-\d{2}$/.test(text(item.date))) return []
    const pv = countOrNull(item.pv)
    const uv = countOrNull(item.uv)
    const uip = countOrNull(item.uip)
    if ([pv, uv, uip].some((metric) => metric === null)) return []
    return [{ date: item.date, pv, uv, uip }]
  })
  return rows.length === value.length ? rows : null
}

function normalizeSummary(raw) {
  if (!object(raw)) return null
  return {
    pv: countOrNull(raw.pv),
    uv: countOrNull(raw.uv),
    uip: countOrNull(raw.uip),
    denied: countOrNull(raw.denied),
    daily: dailyRows(raw.daily),
    hourStats: histogram(raw.hourStats, 24),
    weekdayStats: histogram(raw.weekdayStats, 7),
    browserStats: categoryRows(raw.browserStats, 'browser'),
    osStats: categoryRows(raw.osStats, 'os'),
    deviceStats: categoryRows(raw.deviceStats, 'device'),
    countryStats: categoryRows(raw.countryStats, 'country'),
    localeCnStats: categoryRows(raw.localeCnStats, 'locale'),
    networkStats: categoryRows(raw.networkStats, 'network'),
    uvTypeStats: categoryRows(raw.uvTypeStats, 'uvType'),
    topIpStats: mapTopIpRows(raw.topIpStats),
    dimensionQuality: object(raw.dimensionQuality) ? raw.dimensionQuality : {}
  }
}

function mapTopIpRows(value) {
  if (!Array.isArray(value)) return null
  return value.flatMap((item) => {
    if (!object(item)) return []
    const identifier = maskIdentifier(item.ipHash)
    const count = countOrNull(item.cnt)
    if (!identifier || count === null) return []
    return [
      {
        label: identifier,
        count,
        ratio: ratioOrNull(item.ratio),
        error: countOrNull(item.error),
        approximate: true
      }
    ]
  })
}

export function mapMetricsEnvelope(raw) {
  if (!object(raw) || !object(raw.metrics) || !Array.isArray(raw.items)) {
    throw new AnalyticsContractError('INVALID_STATS_ENVELOPE', '统计响应结构不完整。')
  }
  const summary = normalizeSummary(raw.metrics.requested)
  if (!summary) {
    throw new AnalyticsContractError('MISSING_REQUESTED_METRICS', '统计响应缺少 requested 时间窗。')
  }
  const meta = normalizeMeta(raw.meta)
  return {
    summary,
    meta,
    empty: summary.pv === 0 && summary.uv === 0 && summary.uip === 0,
    missingCoreMetrics: ['pv', 'uv', 'uip'].filter((key) => summary[key] === null)
  }
}

function derivedCategoryQuality(rows, total) {
  if (!rows || total === null) return quality(null)
  const knownCount = rows.reduce((sum, row) => sum + row.count, 0)
  const unknownCount = Math.max(0, total - knownCount)
  return {
    ...quality(
      null,
      total === 0 ? 'EMPTY' : knownCount === 0 ? 'UNKNOWN' : unknownCount ? 'PARTIAL' : 'AVAILABLE'
    ),
    knownCount,
    unknownCount,
    eligibleCount: total,
    coverage: total === 0 ? null : knownCount / total
  }
}

function rowsWithUnknown(rows, dimensionQuality, total) {
  if (!rows) return []
  const result = rows.map((row) => ({
    ...row,
    ratio: row.ratio ?? (total ? row.count / total : null)
  }))
  if (dimensionQuality.unknownCount) {
    result.push({
      label: '未知',
      count: dimensionQuality.unknownCount,
      ratio: total ? dimensionQuality.unknownCount / total : null,
      unknown: true
    })
  }
  return result
}

function explicitQuality(summary, meta, ...keys) {
  for (const dimensions of [summary.dimensionQuality, meta.dimensionQuality]) {
    for (const key of keys) if (object(dimensions?.[key])) return quality(dimensions[key])
  }
  return quality(null)
}

const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

export function dimensionView(model, key) {
  const summary = model?.summary || {}
  const meta = model?.meta || { dimensionQuality: {}, approximation: {} }
  const total = summary.pv
  let rows = []
  let dimensionQuality = quality(null)
  let note = ''
  let approximate = false

  if (key === 'country') {
    dimensionQuality = explicitQuality(summary, meta, 'countryStats', 'country')
    rows = rowsWithUnknown(summary.countryStats, dimensionQuality, total)
    note = '地域来自异步 IP 归属；无法定位的访问保持为未知。'
  } else if (key === 'province') {
    dimensionQuality = explicitQuality(summary, meta, 'localeCnStats')
    rows = rowsWithUnknown(
      summary.localeCnStats,
      dimensionQuality,
      dimensionQuality.eligibleCount ?? total
    )
    note = '省份只统计可识别的中国访问；未知国家不会并入任何省份。'
  } else if (key === 'isp') {
    dimensionQuality = explicitQuality(summary, meta, 'networkStats')
    rows = rowsWithUnknown(summary.networkStats, dimensionQuality, total)
    note = 'networkStats 表示 IP 运营商 / ISP，不能用于判断 Wi-Fi、4G 或 5G。'
  } else if (key === 'newvisitor') {
    dimensionQuality = explicitQuality(summary, meta, 'uvTypeStats')
    const labels = { newUser: '新访客', oldUser: '老访客' }
    const known = (summary.uvTypeStats || []).map((row) => ({
      ...row,
      label: labels[row.label] || row.label
    }))
    rows = rowsWithUnknown(
      known,
      dimensionQuality,
      (dimensionQuality.knownCount ?? 0) + (dimensionQuality.unknownCount ?? 0)
    )
    note = '按所选短链或授权分组在保留数据集中的首次观测划分，不代表真实用户的生命周期首次访问。'
  } else if (key === 'browser' || key === 'os' || key === 'device') {
    const sourceKey = `${key}Stats`
    const source = summary[sourceKey]
    dimensionQuality = derivedCategoryQuality(source, total)
    rows = rowsWithUnknown(source, dimensionQuality, total)
    note = '分类基于已观测请求字段；未识别值单独计入未知。'
  } else if (key === 'hour') {
    if (summary.hourStats) {
      rows = summary.hourStats.map((count, hour) => ({
        label: `${String(hour).padStart(2, '0')}:00`,
        count,
        ratio: total ? count / total : null
      }))
      dimensionQuality = derivedCategoryQuality(rows, total)
    }
    note = '小时桶使用后端声明的 Asia/Shanghai 业务时区。'
  } else if (key === 'weekday') {
    if (summary.weekdayStats) {
      rows = summary.weekdayStats.map((count, index) => ({
        label: WEEKDAYS[index],
        count,
        ratio: total ? count / total : null
      }))
      dimensionQuality = derivedCategoryQuality(rows, total)
    }
    note = '星期桶按周一至周日排列，使用 Asia/Shanghai 业务时区。'
  } else if (key === 'ip') {
    rows = summary.topIpStats || []
    approximate = true
    dimensionQuality = quality(null, rows.length ? 'AVAILABLE' : total === 0 ? 'EMPTY' : 'UNKNOWN')
    note = '这是经过脱敏的 TopK 近似结果，列表之外仍可能存在其他已识别 IP，不能视为 IP 全量分布。'
  } else {
    throw new AnalyticsContractError('UNKNOWN_DIMENSION', '不支持的统计维度。', { key })
  }

  return {
    key,
    label: ANALYTICS_DIMENSIONS.find((item) => item.key === key)?.label || key,
    rows,
    quality: dimensionQuality,
    approximate,
    note
  }
}

export function maskIdentifier(value) {
  const normalized = text(value).replace(/^hash:\s*/i, '')
  if (!normalized) return ''
  if (normalized.includes('…') || normalized.includes('***')) return normalized.slice(0, 48)
  if (normalized.length <= 10) return `${normalized.slice(0, 3)}…`
  return `${normalized.slice(0, 8)}…${normalized.slice(-4)}`
}

function formatParts(value, includeTime) {
  const epoch = epochOrNull(value)
  if (epoch === null) return EMPTY_TEXT
  const options = {
    timeZone: SHANGHAI_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }
  if (includeTime)
    Object.assign(options, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23'
    })
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', options)
      .formatToParts(new Date(epoch))
      .map(({ type, value: part }) => [type, part])
  )
  return includeTime
    ? `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
    : `${parts.year}-${parts.month}-${parts.day}`
}

export const formatShanghaiDate = (value) => formatParts(value, false)
export const formatShanghaiTime = (value) => formatParts(value, true)

function responseStatus(value) {
  const status = Number(value)
  return Number.isInteger(status) && status >= 100 && status <= 599 ? String(status) : EMPTY_TEXT
}

function visitorType(value) {
  return { newUser: '新访客', oldUser: '老访客' }[text(value)] || '未知'
}

function mapAccessItem(raw) {
  if (!object(raw))
    throw new AnalyticsContractError('INVALID_ACCESS_ITEM', '访问记录包含无效数据。')
  const occurredAt = epochOrNull(raw.occurredAt)
  const location = [
    ...new Set([raw.country, raw.province, raw.city].map(text).filter(Boolean))
  ].join(' · ')
  return {
    eventId: text(raw.eventId),
    linkId: raw.linkId == null ? '' : String(raw.linkId),
    fullShortUrl: text(raw.fullShortUrl),
    occurredAt,
    occurredAtDisplay: formatShanghaiTime(occurredAt),
    ipIdentifier: maskIdentifier(raw.ipHash) || EMPTY_TEXT,
    visitorIdentifier: maskIdentifier(raw.visitorHash) || EMPTY_TEXT,
    visitorType: visitorType(raw.uvType),
    location: location || EMPTY_TEXT,
    country: text(raw.country),
    province: text(raw.province),
    city: text(raw.city),
    browser: text(raw.browser) || EMPTY_TEXT,
    os: text(raw.os) || EMPTY_TEXT,
    device: text(raw.device) || EMPTY_TEXT,
    isp: text(raw.network) || EMPTY_TEXT,
    refererDomain: text(raw.refererDomain) || EMPTY_TEXT,
    geoStatus: text(raw.geoStatus) || 'UNKNOWN',
    eventType: text(raw.kind) || EMPTY_TEXT,
    responseStatus: responseStatus(raw.status),
    datasetEarliestObservedAt: epochOrNull(raw.historyEarliestObservedAt)
  }
}

export function mapAccessEnvelope(raw) {
  if (!object(raw) || !object(raw.metrics) || !Array.isArray(raw.items)) {
    throw new AnalyticsContractError('INVALID_ACCESS_ENVELOPE', '访问记录响应结构不完整。')
  }
  const meta = normalizeMeta(raw.meta)
  if (!meta.snapshotId)
    throw new AnalyticsContractError('MISSING_SNAPSHOT_ID', '访问记录响应缺少快照标识。')
  const records = raw.items.map(mapAccessItem)
  return {
    records,
    meta,
    snapshotId: meta.snapshotId,
    nextCursor: meta.nextCursor,
    hasNext: Boolean(meta.nextCursor),
    empty: records.length === 0,
    historyQuality: quality(meta.dimensionQuality?.uvTypeStats)
  }
}

export function validateAccessContinuation(previousPages, nextPage, sentCursor) {
  if (!Array.isArray(previousPages) || previousPages.length === 0 || !object(nextPage)) {
    throw new AnalyticsContractError('INVALID_ACCESS_CONTINUATION', '访问记录续批上下文无效。')
  }
  const snapshotId = previousPages[0]?.snapshotId
  if (!snapshotId || nextPage.snapshotId !== snapshotId) {
    throw new AnalyticsContractError(
      'SNAPSHOT_CHANGED',
      '服务端返回了不同的统计快照，请从首批重新读取。'
    )
  }
  const nextCursor = nextPage.nextCursor
  const knownCursors = new Set(previousPages.map((page) => page?.nextCursor).filter(Boolean))
  if (nextCursor && (nextCursor === sentCursor || knownCursors.has(nextCursor))) {
    throw new AnalyticsContractError(
      'REPEATED_PAGE_CURSOR',
      '访问记录游标没有前进，请从首批重新读取。'
    )
  }
  return nextPage
}

function ymdParts(date) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '')) return null
  const [year, month, day] = date.split('-').map(Number)
  const epoch = Date.UTC(year, month - 1, day)
  const parsed = new Date(epoch)
  if (
    parsed.getUTCFullYear() !== year ||
    parsed.getUTCMonth() !== month - 1 ||
    parsed.getUTCDate() !== day
  )
    return null
  return { year, month, day, epoch }
}

function ymd(epoch) {
  const date = new Date(epoch)
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`
}

export function defaultShanghaiDateRange(now = Date.now()) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: SHANGHAI_TIME_ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    })
      .formatToParts(new Date(now))
      .map(({ type, value }) => [type, value])
  )
  const end = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day))
  return { startDate: ymd(end - 6 * 86400000), endDate: ymd(end) }
}

export function validateAnalyticsRange(startDate, endDate) {
  const start = ymdParts(startDate)
  const end = ymdParts(endDate)
  if (!start || !end)
    return { ok: false, code: 'INVALID_RANGE', message: '请选择有效的开始和结束日期。', days: null }
  const days = Math.floor((end.epoch - start.epoch) / 86400000) + 1
  if (days < 1)
    return { ok: false, code: 'INVALID_RANGE', message: '结束日期不能早于开始日期。', days }
  if (days > 7)
    return {
      ok: false,
      code: 'TOO_LARGE',
      message: '一次统计查询最多覆盖 7 天，请缩短日期范围。',
      days
    }
  return { ok: true, code: '', message: '', days }
}

function sameLink(link, id) {
  const expected = String(id || '')
  return [link?.id, link?.code, link?.fullShortUrl].some(
    (value) => value != null && String(value) === expected
  )
}

export function resolveAnalyticsScope(state, type, id) {
  if (!state || !['group', 'link'].includes(type)) {
    throw new AnalyticsContractError('INVALID_SCOPE', '请选择有效的统计范围。')
  }
  if (type === 'group') {
    const group = (state.groups || []).find((item) => String(item.id) === String(id))
    if (!group) throw new AnalyticsContractError('SCOPE_UNAVAILABLE', '当前分组不在已授权范围内。')
    const loadedLinks = (state.links || []).filter(
      (link) => String(link.groupId) === String(group.id) && !link.recycled
    )
    const count = countOrNull(group.count) ?? (loadedLinks.length ? loadedLinks.length : null)
    if (count > 500)
      throw new AnalyticsContractError('TOO_LARGE', '分组超过 500 条短链，请缩小范围。')
    return {
      type: 'group',
      id: String(group.id),
      gid: String(group.id),
      label: group.name || '未命名分组',
      count
    }
  }

  let link = (state.links || []).find((item) => sameLink(item, id) && !item.recycled)
  const remembered =
    state.analyticsScope?.type === 'link' && sameLink(state.analyticsScope, id)
      ? state.analyticsScope
      : null
  if (!link && remembered) link = remembered
  if (!link) throw new AnalyticsContractError('SCOPE_UNAVAILABLE', '当前短链不在已授权范围内。')
  const gid = text(link.groupId || remembered?.groupId || state.groupId)
  const fullShortUrl = text(link.fullShortUrl || remembered?.fullShortUrl)
  if (!gid || !fullShortUrl)
    throw new AnalyticsContractError('INCOMPLETE_LINK_SCOPE', '短链统计范围缺少 gid 或完整短链接。')
  return {
    type: 'link',
    id: String(link.id ?? remembered?.id ?? id),
    gid,
    fullShortUrl,
    code: text(link.code || remembered?.code),
    label: text(link.title || link.description || remembered?.title || link.code) || fullShortUrl,
    count: 1
  }
}

export function classifyAnalyticsError(error) {
  const code = text(error?.code) || 'UNKNOWN'
  const message = text(error?.message)
  const details = object(error?.details) ? JSON.stringify(error.details) : text(error?.details)
  const fingerprint = `${code} ${message} ${details}`.toUpperCase()
  if (error?.name === 'AbortError') return { kind: 'cancelled', retryable: false, message: '' }
  if (
    fingerprint.includes('SNAPSHOT_EXPIRED') ||
    fingerprint.includes('SNAPSHOT_CHANGED') ||
    fingerprint.includes('REPEATED_PAGE_CURSOR') ||
    fingerprint.includes('QUERY_SCOPE_CHANGED')
  ) {
    return {
      kind: 'snapshot-expired',
      retryable: true,
      message: '统计快照已失效或授权范围已变化，请从首批重新读取。'
    }
  }
  if (fingerprint.includes('TOO_LARGE')) {
    return {
      kind: 'too-large',
      retryable: false,
      message: '统计范围超过在线查询上限，请缩短日期或缩小分组。'
    }
  }
  if (Number(error?.status) === 403)
    return { kind: 'permission', retryable: false, message: '当前账户无权读取这个统计范围。' }
  if (Number(error?.status) === 401)
    return { kind: 'unauthenticated', retryable: false, message: '登录状态已失效，请重新登录。' }
  if (Number(error?.status) === 429)
    return {
      kind: 'rate-limit',
      retryable: true,
      message: error?.retryAfter
        ? `统计请求较多，请在 ${error.retryAfter} 秒后重试。`
        : '统计请求较多，请稍后重试。'
    }
  if (code === 'TIMEOUT')
    return { kind: 'timeout', retryable: true, message: '统计请求超时，请重试。' }
  if (error instanceof AnalyticsContractError)
    return { kind: 'contract', retryable: true, message: error.message }
  return {
    kind: 'service',
    retryable: true,
    message: message || '统计服务暂时不可用，请稍后重试。'
  }
}

export function formatCount(value) {
  return value === null || value === undefined ? EMPTY_TEXT : Number(value).toLocaleString('zh-CN')
}

export function formatRatio(value) {
  return value === null || value === undefined ? EMPTY_TEXT : `${(Number(value) * 100).toFixed(1)}%`
}
