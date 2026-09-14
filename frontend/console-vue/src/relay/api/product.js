import { request, download } from './http.js'
import {
  mapGroup,
  mapPage,
  mapLink,
  mapBatchRow,
  lifecycleBody,
  SUPPORTED_ORDERS
} from '../domain/product-model.js'

const ADMIN = '/api/short-link/admin/v1'
const USER = '/api/short-link/v1/user'

export const login = (body, options = {}) =>
  request(`${ADMIN}/user/login`, { ...options, auth: false, method: 'POST', body })
export const registerUser = (body, options = {}) =>
  request(`${ADMIN}/user`, { ...options, auth: false, method: 'POST', body })
export const hasUsername = (username, options = {}) =>
  request(`${USER}/has-username`, { ...options, auth: false, query: { username } })
export const getInitialization = (options = {}) => request(`${ADMIN}/user/initialization`, options)
export const retryInitialization = (options = {}) =>
  request(`${ADMIN}/user/initialization/retry`, { ...options, method: 'POST' })
export const getProfile = (username, options = {}) =>
  request(`${USER}/${encodeURIComponent(username)}`, options)
export const updateProfile = (body, options = {}) =>
  request(USER, { ...options, method: 'PUT', body })
export const logout = ({ username, token }, options = {}) =>
  request(`${ADMIN}/user/logout`, { ...options, method: 'DELETE', query: { username, token } })

export async function listGroups(options = {}) {
  const data = await request(`${ADMIN}/group`, options)
  if (!Array.isArray(data)) throw new Error('分组列表响应不完整。')
  return data.map(mapGroup)
}
export const createGroup = (name, options = {}) =>
  request(`${ADMIN}/group`, { ...options, method: 'POST', body: { name } })
export const renameGroup = (gid, name, options = {}) =>
  request(`${ADMIN}/group`, { ...options, method: 'PUT', body: { gid, name } })
export const deleteGroup = (gid, options = {}) =>
  request(`${ADMIN}/group`, { ...options, method: 'DELETE', query: { gid } })
export const sortGroups = (groups, options = {}) =>
  request(`${ADMIN}/sort`, {
    ...options,
    method: 'POST',
    body: groups.map((group, index) => ({ gid: group.id, sortOrder: index }))
  })

export async function listLinks(
  { gid, current = 1, size = 10, orderTag = 'createTime', statsSnapshotId, statsEnd } = {},
  options = {}
) {
  if (!SUPPORTED_ORDERS.has(orderTag)) throw new Error('不支持此排序方式。')
  return mapPage(
    await request(`${ADMIN}/page`, {
      ...options,
      query: { gid, current, size, orderTag, statsSnapshotId, statsEnd }
    })
  )
}
export const listRecycled = async ({ current = 1, size = 10 } = {}, options = {}) =>
  mapPage(
    await request(`${ADMIN}/recycle-bin/page`, { ...options, query: { current, size } }),
    true
  )
export const fetchTitle = (url, options = {}) =>
  request(`${ADMIN}/tittle`, { timeoutMs: 10000, ...options, query: { url } })
export const createLink = async (body, options = {}) =>
  mapLink(await request(`${ADMIN}/create`, { timeoutMs: 45000, ...options, method: 'POST', body }))
export const updateLink = (body, options = {}) =>
  request(`${ADMIN}/update`, { ...options, method: 'POST', body })
export const recycleLink = (link, options = {}) =>
  request(`${ADMIN}/recycle-bin/save`, { ...options, method: 'POST', body: lifecycleBody(link) })
export const restoreLink = (link, options = {}) =>
  request(`${ADMIN}/recycle-bin/recover`, { ...options, method: 'POST', body: lifecycleBody(link) })
export const deleteLink = (link, options = {}) =>
  request(`${ADMIN}/recycle-bin/remove`, { ...options, method: 'POST', body: lifecycleBody(link) })

export const createBatch = (body, options = {}) =>
  request(`${ADMIN}/create/batch`, { timeoutMs: 45000, ...options, method: 'POST', body })
export const getBatchStatus = (jobId, options = {}) =>
  request(`${ADMIN}/batches/${encodeURIComponent(jobId)}`, options)
export const getBatchRows = async (jobId, { after = 0, limit = 20 } = {}, options = {}) => {
  const rows = await request(`${ADMIN}/batches/${encodeURIComponent(jobId)}/rows`, {
    ...options,
    query: { after, limit }
  })
  if (!Array.isArray(rows)) throw new Error('批量结果响应不完整。')
  return rows.map(mapBatchRow)
}
export const cancelBatch = (jobId, options = {}) =>
  request(`${ADMIN}/batches/${encodeURIComponent(jobId)}/cancel`, { ...options, method: 'POST' })
export const exportSyncBatch = (requestId, options = {}) =>
  download(
    `${ADMIN}/create/batch/export`,
    { timeoutMs: 120000, ...options, query: { requestId } },
    'created-links.xlsx'
  )
export const exportAsyncBatch = (jobId, options = {}) =>
  download(
    `${ADMIN}/batches/${encodeURIComponent(jobId)}/export`,
    { timeoutMs: 300000, ...options },
    'batch-results.csv'
  )

export { absoluteShortUrl } from '../domain/product-model.js'
