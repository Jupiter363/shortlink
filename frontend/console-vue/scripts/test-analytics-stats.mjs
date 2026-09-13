/* eslint-env node */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../src/utils/analyticsStats.js', import.meta.url), 'utf8')
const { adaptAnalyticsStats, adaptAccessRecords, createStatsCursorPager } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
)
const response = (data) => ({ data: { success: true, data } })
const records = (number, nextCursor = null, snapshotId = 'snapshot-a') => response({
  items: [{ eventId: `event-${number}`, country: 'CN', province: '广东省', city: '深圳市', network: '电信' }],
  metrics: {}, meta: { snapshotId, nextCursor, completeness: 'PARTIAL', dimensionQuality: { country: { status: 'AVAILABLE' } } }
})
const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

test('new summary preserves cross-day distinct UV, every dimension, and original evidence', () => {
  const summary = {
    pv: 12, uv: 3, uip: 2,
    daily: [{ date: '2026-09-12', pv: 5, uv: 2, uip: 2 }, { date: '2026-09-13', pv: 7, uv: 2, uip: 2 }],
    countryStats: [{ country: 'CN', cnt: 12 }], networkStats: [{ network: '电信', cnt: 12 }],
    localeCnStats: [{ locale: '广东省', cnt: 12 }], uvTypeStats: [{ uvType: 'newUser', cnt: 1 }],
    dimensionQuality: { uvTypeStats: { status: 'PARTIAL', unknownUv: 1 } }
  }
  const meta = { completeness: 'PARTIAL', sourceCut: { partition: 1 }, dimensionQuality: { country: { status: 'AVAILABLE' } } }
  const result = adaptAnalyticsStats(response({ metrics: { requested: summary }, items: [{ linkId: 42 }], meta }))
  assert.equal(result.pv, 12)
  assert.equal(result.uv, 3)
  assert.equal(result.uip, 2)
  assert.deepEqual(result.daily, summary.daily)
  for (const field of ['countryStats', 'networkStats', 'localeCnStats', 'uvTypeStats']) assert.deepEqual(result[field], summary[field])
  assert.deepEqual(result.meta, meta)
  assert.equal(result.dimensionQuality.country.status, 'AVAILABLE')
  assert.equal(result.dimensionQuality.uvTypeStats.unknownUv, 1)
  assert.deepEqual(result.items, [{ linkId: 42 }])
})

test('explicit zero is retained and old flat responses remain compatible', () => {
  const result = adaptAnalyticsStats(response({ pv: 0, uv: 0, uip: 0, totalPv: 9, totalUv: 8, totalUip: 7, daily: [] }))
  assert.deepEqual([result.pv, result.uv, result.uip], [0, 0, 0])
  assert.equal(adaptAnalyticsStats({ totalPv: 4, totalUv: 3, totalUip: 2 }).uv, 3)
})

test('missing or failed envelopes cannot masquerade as successful zero statistics', () => {
  assert.throws(() => adaptAnalyticsStats({ data: { success: false, message: 'failure' } }), { code: 'STATS_REQUEST_FAILED' })
  assert.throws(() => adaptAnalyticsStats(response({ items: [] })), { code: 'MISSING_STATS_SUMMARY' })
  assert.throws(() => adaptAnalyticsStats(null), { code: 'INVALID_STATS_RESPONSE' })
})

test('new access envelope preserves dimensions and has no invented total', () => {
  const result = adaptAccessRecords(records(1, 'cursor-2'))
  assert.equal(result.total, null)
  assert.equal(result.hasNext, true)
  assert.equal(result.snapshotId, 'snapshot-a')
  assert.equal(result.records[0].city, '深圳市')
  assert.equal(result.records[0].network, '电信')
  assert.equal(result.meta.completeness, 'PARTIAL')
  assert.equal(result.dimensionQuality.country.status, 'AVAILABLE')
  assert.equal(adaptAccessRecords(response({ records: [], total: 31 }), { current: 2, size: 10 }).total, 31)
  assert.equal(adaptAccessRecords(response({ records: [], total: '' })).total, null)
})

test('next page sends snapshot and cursor while previous page uses its bounded cache', async () => {
  const pager = createStatsCursorPager()
  const requests = []
  const fetch = async (params) => { requests.push(params); return records(params.current, params.current === 1 ? 'cursor-2' : null) }
  pager.reset({ gid: 'g1', fullShortUrl: 'short.example/a', enableStatus: 0, startDate: 'd1', endDate: 'd2' }, 10)
  const first = await pager.load(1, fetch)
  assert.equal(first.data.data.current, 1)
  assert.equal(first.data.data.hasPrevious, false)
  assert.equal('snapshotId' in requests[0], false)
  const second = await pager.load(2, fetch)
  assert.equal(requests[1].snapshotId, 'snapshot-a')
  assert.equal(requests[1].cursor, 'cursor-2')
  assert.equal(requests[1].enableStatus, 0)
  assert.equal(second.data.data.pageCursor, 'cursor-2')
  assert.equal(second.data.data.hasPrevious, true)
  assert.equal(second.data.data.hasNext, false)
  const previous = await pager.load(1, fetch)
  assert.equal(previous.data.data.records[0].eventId, 'event-1')
  assert.equal(requests.length, 2)
  assert.equal(previous.data.data.total, null)
})

test('random jumps are rejected instead of silently requerying the first page', async () => {
  const pager = createStatsCursorPager()
  pager.reset({ gid: 'g1' }, 10)
  await assert.rejects(pager.load(2, () => assert.fail('must not fetch')), { code: 'PAGE_NOT_CACHED' })
  await pager.load(1, async () => records(1, 'cursor-2'))
  await assert.rejects(pager.load(3, () => assert.fail('must not fetch')), { code: 'PAGE_NOT_CACHED' })
})

test('cache remains bounded and evicted previous pages are unavailable', async () => {
  const pager = createStatsCursorPager({ maxCachedPages: 2 })
  pager.reset({ gid: 'g1' }, 1)
  for (let page = 1; page <= 3; page++) await pager.load(page, async () => records(page, `cursor-${page + 1}`))
  assert.equal(pager.view().data.data.hasPrevious, true)
  await pager.load(2, () => assert.fail('cached page must not fetch'))
  assert.equal(pager.view().data.data.hasPrevious, false)
  await assert.rejects(pager.load(1, () => assert.fail('evicted page must not fetch')), { code: 'PAGE_NOT_CACHED' })
})

test('scope and size reset discard old cursors, old successes, and old failures', async () => {
  const pager = createStatsCursorPager()
  const old = deferred()
  pager.reset({ gid: 'old', startDate: 'old-date' }, 10)
  const pending = pager.load(1, () => old.promise)
  pager.reset({ gid: 'new', startDate: 'new-date', snapshotId: 'should-not-reuse', cursor: 'old-cursor' }, 20)
  const fresh = await pager.load(1, async (params) => {
    assert.deepEqual(params, { gid: 'new', startDate: 'new-date', current: 1, size: 20 })
    return records('new', null, 'snapshot-new')
  })
  old.resolve(records('old'))
  assert.equal(await pending, null)
  assert.equal(pager.view().data.data.records[0].eventId, fresh.data.data.records[0].eventId)
  const abandoned = deferred()
  pager.reset({ gid: 'old-again' })
  const failure = pager.load(1, () => abandoned.promise)
  pager.reset({ gid: 'final' })
  abandoned.reject(new Error('old request failed'))
  assert.equal(await failure, null)
})

test('late next-page response cannot overwrite a cached previous-page choice', async () => {
  const pager = createStatsCursorPager()
  pager.reset({ gid: 'g1' })
  await pager.load(1, async () => records(1, 'cursor-2'))
  const slow = deferred()
  const pending = pager.load(2, () => slow.promise)
  await pager.load(1, () => assert.fail('cached page must not fetch'))
  slow.resolve(records(2, null))
  assert.equal(await pending, null)
  assert.equal(pager.view().data.data.current, 1)
})

test('changed snapshots and nonadvancing cursors fail without replacing the visible page', async () => {
  const pager = createStatsCursorPager()
  pager.reset({ gid: 'g1' })
  await pager.load(1, async () => records(1, 'cursor-2'))
  await assert.rejects(pager.load(2, async () => records(2, null, 'changed-snapshot')), { code: 'SNAPSHOT_CHANGED' })
  assert.equal(pager.view().data.data.current, 1)
  await assert.rejects(pager.load(2, async () => records(2, 'cursor-2')), { code: 'REPEATED_PAGE_CURSOR' })
  assert.equal(pager.view().data.data.current, 1)
})

test('page parameters are copied before asynchronous loading and invalid budgets fail', async () => {
  const pager = createStatsCursorPager()
  const params = { gid: 'fixed', startDate: 'original-date' }
  pager.reset(params, 10)
  params.gid = 'mutated'
  await pager.load(1, async (request) => {
    assert.equal(request.gid, 'fixed')
    assert.equal(request.startDate, 'original-date')
    return records(1)
  })
  assert.throws(() => pager.reset({}, 501), { code: 'INVALID_PAGE_SIZE' })
  assert.throws(() => createStatsCursorPager({ maxCachedPages: 1000 }), TypeError)
})
