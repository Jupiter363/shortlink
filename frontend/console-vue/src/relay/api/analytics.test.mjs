import assert from 'node:assert/strict'
import test from 'node:test'
import { createAnalyticsApi } from './analytics.js'

const metricsEnvelope = {
  metrics: {
    requested: {
      pv: 0,
      uv: 0,
      uip: 0,
      daily: [],
      hourStats: Array(24).fill(0),
      weekdayStats: Array(7).fill(0)
    }
  },
  items: [],
  meta: {
    snapshotId: 'metrics-snapshot',
    nextCursor: null,
    completeness: 'COMPLETE',
    dimensionQuality: {}
  }
}

const recordsEnvelope = {
  metrics: {},
  items: [],
  meta: {
    snapshotId: 'records-snapshot',
    nextCursor: null,
    completeness: 'COMPLETE',
    dimensionQuality: {}
  }
}

test('selects the exact public metrics endpoint for group and link scopes', async () => {
  const calls = []
  const api = createAnalyticsApi(async (path, options) => {
    calls.push({ path, options })
    return metricsEnvelope
  })
  await api.queryMetrics(
    { type: 'group', gid: 'g1' },
    { startDate: '2026-09-08', endDate: '2026-09-14' }
  )
  await api.queryMetrics(
    { type: 'link', gid: 'g1', fullShortUrl: 's.example/abc' },
    { startDate: '2026-09-08', endDate: '2026-09-14' }
  )
  assert.equal(calls[0].path, '/api/short-link/admin/v1/stats/group')
  assert.deepEqual(calls[0].options.query, {
    gid: 'g1',
    startDate: '2026-09-08',
    endDate: '2026-09-14'
  })
  assert.equal(calls[1].path, '/api/short-link/admin/v1/stats')
  assert.equal(calls[1].options.query.fullShortUrl, 's.example/abc')
})

test('uses snapshotId and the previous nextCursor only on access-record continuation', async () => {
  const calls = []
  const api = createAnalyticsApi(async (path, options) => {
    calls.push({ path, options })
    return recordsEnvelope
  })
  const scope = { type: 'group', gid: 'g1' }
  const range = { startDate: '2026-09-08', endDate: '2026-09-14' }
  await api.queryAccessRecords(scope, range, { current: 1, size: 20 })
  await api.queryAccessRecords(scope, range, {
    current: 2,
    size: 20,
    snapshotId: 'snapshot-1',
    cursor: 'cursor-2'
  })
  assert.equal(calls[0].path, '/api/short-link/admin/v1/stats/access-record/group')
  assert.equal(Object.hasOwn(calls[0].options.query, 'snapshotId'), true)
  assert.equal(calls[0].options.query.snapshotId, undefined)
  assert.deepEqual(calls[1].options.query, {
    gid: 'g1',
    startDate: '2026-09-08',
    endDate: '2026-09-14',
    current: 2,
    size: 20,
    snapshotId: 'snapshot-1',
    cursor: 'cursor-2'
  })
})

test('rejects random access to an uncached page before calling transport', async () => {
  let called = false
  const api = createAnalyticsApi(async () => {
    called = true
    return recordsEnvelope
  })
  await assert.rejects(
    () =>
      api.queryAccessRecords(
        { type: 'link', gid: 'g1', fullShortUrl: 's.example/abc' },
        { startDate: '2026-09-08', endDate: '2026-09-14' },
        { current: 3, size: 20 }
      ),
    /snapshotId and cursor/
  )
  assert.equal(called, false)
})

test('keeps the canonical link selector unchanged by presentation protocol rules', async () => {
  const calls = []
  const api = createAnalyticsApi(async (path, options) => {
    calls.push({ path, options })
    return metricsEnvelope
  })
  const canonical = 'https://localhost:19080/AbC9'
  await api.queryMetrics(
    { type: 'link', gid: 'g1', fullShortUrl: canonical },
    { startDate: '2026-09-08', endDate: '2026-09-14' }
  )
  assert.equal(calls[0].options.query.fullShortUrl, canonical)
})
