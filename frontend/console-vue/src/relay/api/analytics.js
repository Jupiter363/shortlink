import { request } from './http.js'
import { mapAccessEnvelope, mapMetricsEnvelope } from '../domain/analytics.js'

const ADMIN = '/api/short-link/admin/v1'

function scopeQuery(scope) {
  if (scope?.type === 'group') return { gid: scope.gid }
  if (scope?.type === 'link') return { gid: scope.gid, fullShortUrl: scope.fullShortUrl }
  throw new TypeError('Invalid analytics scope')
}

function rangeQuery(range) {
  if (!range?.startDate || !range?.endDate) throw new TypeError('Invalid analytics range')
  return { startDate: range.startDate, endDate: range.endDate }
}

export function createAnalyticsApi(transport = request) {
  return {
    async queryMetrics(scope, range, options = {}) {
      const path = scope.type === 'group' ? `${ADMIN}/stats/group` : `${ADMIN}/stats`
      const data = await transport(path, {
        ...options,
        method: 'GET',
        timeoutMs: options.timeoutMs || 15000,
        query: { ...scopeQuery(scope), ...rangeQuery(range) }
      })
      return mapMetricsEnvelope(data)
    },

    async queryAccessRecords(scope, range, page = {}, options = {}) {
      const path =
        scope.type === 'group'
          ? `${ADMIN}/stats/access-record/group`
          : `${ADMIN}/stats/access-record`
      const current = Number(page.current || 1)
      const size = Number(page.size || 20)
      if (!Number.isSafeInteger(current) || current < 1)
        throw new TypeError('Invalid access-record page')
      if (!Number.isSafeInteger(size) || size < 1 || size > 500)
        throw new TypeError('Invalid access-record page size')
      if (
        current > 1 &&
        (typeof page.snapshotId !== 'string' ||
          !page.snapshotId.trim() ||
          typeof page.cursor !== 'string' ||
          !page.cursor.trim() ||
          page.cursor.length > 8192)
      ) {
        throw new TypeError('Access-record continuation requires snapshotId and cursor')
      }
      const data = await transport(path, {
        ...options,
        method: 'GET',
        timeoutMs: options.timeoutMs || 15000,
        query: {
          ...scopeQuery(scope),
          ...rangeQuery(range),
          current,
          size,
          snapshotId: current > 1 ? page.snapshotId : undefined,
          cursor: current > 1 ? page.cursor : undefined
        }
      })
      return mapAccessEnvelope(data)
    }
  }
}

export const analyticsApi = createAnalyticsApi()
