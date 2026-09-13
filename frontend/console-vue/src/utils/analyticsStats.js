const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const integer = (value, fallback) => value !== '' && typeof value !== 'boolean'
  && Number.isSafeInteger(Number(value)) && Number(value) >= 0
  ? Number(value) : fallback

export class AnalyticsStatsError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'AnalyticsStatsError'
    this.code = code
  }
}

function payloadOf(response) {
  let payload = response
  for (let depth = 0; depth < 3 && object(payload); depth++) {
    if (payload.success === false) {
      throw new AnalyticsStatsError('STATS_REQUEST_FAILED', '统计暂时不可用，请稍后重试')
    }
    if (object(payload.data)) payload = payload.data
    else break
  }
  if (!object(payload)) throw new AnalyticsStatsError('INVALID_STATS_RESPONSE', '统计响应缺失，请重新加载')
  return payload
}

/** Keeps the envelope and every dimension while exposing the summary expected by the old charts. */
export function adaptAnalyticsStats(response) {
  const payload = payloadOf(response)
  const windows = object(payload.metrics) ? payload.metrics : {}
  let summary = object(windows.requested) ? windows.requested : null
  if (!summary && Object.keys(windows).length === 1) summary = Object.values(windows)[0]
  if (!summary && !('metrics' in payload) && !Array.isArray(payload.items)) summary = payload
  if (!object(summary)) {
    throw new AnalyticsStatsError('MISSING_STATS_SUMMARY', '当前统计没有可用汇总，请重新加载')
  }
  const meta = object(payload.meta) ? payload.meta : {}
  return {
    ...payload,
    ...summary,
    meta,
    metrics: windows,
    items: Array.isArray(payload.items) ? payload.items : [],
    dimensionQuality: { ...(meta.dimensionQuality || {}), ...(summary.dimensionQuality || {}) },
    pv: summary.pv ?? summary.totalPv,
    uv: summary.uv ?? summary.totalUv,
    uip: summary.uip ?? summary.totalUip,
    loading: false,
    error: ''
  }
}

/** No total is inferred from a page length, cursor, or an approximate metric. */
export function adaptAccessRecords(response, { current = 1, size = 10, pageCursor = null } = {}) {
  const payload = payloadOf(response)
  const cursorMode = Array.isArray(payload.items) || object(payload.meta)
  const records = cursorMode ? payload.items : payload.records
  if (!Array.isArray(records)) {
    throw new AnalyticsStatsError('INVALID_ACCESS_RESPONSE', '访问记录响应缺失，请重新加载')
  }
  const meta = object(payload.meta) ? payload.meta : {}
  const snapshotId = meta.snapshotId ?? payload.snapshotId ?? null
  const nextCursor = meta.nextCursor ?? payload.nextCursor ?? null
  const total = payload.total == null ? null : integer(payload.total, null)
  if (nextCursor !== null && (typeof nextCursor !== 'string' || nextCursor.length === 0
      || nextCursor.length > 8192 || typeof snapshotId !== 'string' || !snapshotId)) {
    throw new AnalyticsStatsError('INVALID_ACCESS_CURSOR', '访问记录分页信息无效，请重新加载')
  }
  return {
    ...payload,
    records,
    items: records,
    current,
    size,
    total,
    snapshotId,
    nextCursor,
    pageCursor,
    hasNext: cursorMode ? nextCursor !== null : total !== null && current * size < total,
    hasPrevious: false,
    paginationMode: cursorMode ? 'cursor' : 'offset',
    meta,
    dimensionQuality: meta.dimensionQuality || payload.dimensionQuality || {},
    loading: false,
    error: ''
  }
}

function tableEnvelope(page) {
  // Legacy ChartsInfo reads data.data.records; metadata is also available without unwrapping.
  return { ...page, data: { data: page } }
}

/** Sequential cursor navigation with bounded previous-page cache and stale-response fencing. */
export function createStatsCursorPager({ maxCachedPages = 20, maxPageSize = 100 } = {}) {
  if (!Number.isInteger(maxCachedPages) || maxCachedPages < 2 || maxCachedPages > 100
      || !Number.isInteger(maxPageSize) || maxPageSize < 1 || maxPageSize > 500) {
    throw new TypeError('Invalid statistics page budget')
  }
  let generation = 0
  let sequence = 0
  let scope = {}
  let size = 10
  let current = 1
  const pages = new Map()

  function reset(params = {}, nextSize = 10) {
    if (!Number.isInteger(nextSize) || nextSize < 1 || nextSize > maxPageSize) {
      throw new AnalyticsStatsError('INVALID_PAGE_SIZE', '访问记录每页数量不受支持')
    }
    generation++
    sequence++
    scope = { ...params }
    delete scope.snapshotId
    delete scope.cursor
    delete scope.current
    delete scope.size
    size = nextSize
    current = 1
    pages.clear()
  }

  function view(overrides = {}) {
    const page = pages.get(current) || {
      records: [], items: [], current, size, total: null, snapshotId: null,
      nextCursor: null, pageCursor: null, hasNext: false, paginationMode: 'cursor',
      meta: {}, dimensionQuality: {}, loading: false, error: ''
    }
    return tableEnvelope({ ...page, hasPrevious: pages.has(current - 1), ...overrides })
  }

  async function load(targetPage, fetchPage) {
    if (!Number.isSafeInteger(targetPage) || targetPage < 1) {
      throw new AnalyticsStatsError('INVALID_PAGE', '访问记录页码无效')
    }
    const requestGeneration = generation
    const requestSequence = ++sequence
    if (pages.has(targetPage)) {
      current = targetPage
      return view()
    }
    const previous = pages.get(current)
    const first = pages.size === 0 && targetPage === 1
    if (!first && (targetPage !== current + 1 || !previous?.hasNext)) {
      throw new AnalyticsStatsError('PAGE_NOT_CACHED', '请使用下一页或已缓存的上一页')
    }
    const cursor = first ? null : previous.nextCursor
    const snapshotId = first ? null : previous.snapshotId
    const params = { ...scope, current: targetPage, size }
    if (!first && previous.paginationMode === 'cursor') {
      if (!snapshotId || !cursor) throw new AnalyticsStatsError('MISSING_PAGE_CURSOR', '统计快照已失效，请重新加载')
      params.snapshotId = snapshotId
      params.cursor = cursor
    }
    try {
      const response = await fetchPage(params)
      if (generation !== requestGeneration || sequence !== requestSequence) return null
      const page = adaptAccessRecords(response, { current: targetPage, size, pageCursor: cursor })
      if (!first && previous.paginationMode === 'cursor' && page.snapshotId !== snapshotId) {
        throw new AnalyticsStatsError('SNAPSHOT_CHANGED', '统计快照发生变化，请重新加载')
      }
      if (page.nextCursor !== null && (page.nextCursor === cursor
          || [...pages.values()].some((known) => known.pageCursor === page.nextCursor))) {
        throw new AnalyticsStatsError('REPEATED_PAGE_CURSOR', '访问记录分页未前进，请重新加载')
      }
      pages.set(targetPage, page)
      current = targetPage
      while (pages.size > maxCachedPages) pages.delete(pages.keys().next().value)
      return view()
    } catch (error) {
      if (generation !== requestGeneration || sequence !== requestSequence) return null
      throw error
    }
  }

  return { reset, load, view }
}
