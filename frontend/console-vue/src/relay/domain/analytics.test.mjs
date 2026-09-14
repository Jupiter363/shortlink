import assert from 'node:assert/strict'
import test from 'node:test'
import {
  AnalyticsContractError,
  classifyAnalyticsError,
  defaultShanghaiDateRange,
  dimensionView,
  mapAccessEnvelope,
  mapMetricsEnvelope,
  resolveAnalyticsScope,
  validateAccessContinuation,
  validateAnalyticsRange
} from './analytics.js'

const rawSummary = {
  pv: 10,
  uv: 7,
  uip: 6,
  denied: 1,
  daily: [{ date: '2026-09-14', pv: 10, uv: 7, uip: 6 }],
  hourStats: Array.from({ length: 24 }, (_, index) => (index === 14 ? 10 : 0)),
  weekdayStats: [10, 0, 0, 0, 0, 0, 0],
  countryStats: [{ country: 'CN', cnt: 8, ratio: 0.8 }],
  localeCnStats: [{ locale: '广东省', cnt: 6, ratio: 0.6 }],
  networkStats: [{ network: '中国电信', cnt: 7, ratio: 0.7 }],
  browserStats: [{ browser: 'Chrome', cnt: 9, ratio: 0.9 }],
  osStats: [{ os: 'Android', cnt: 9, ratio: 0.9 }],
  deviceStats: [{ device: 'Mobile', cnt: 10, ratio: 1 }],
  topIpStats: [
    { ipHash: '0123456789abcdef0123456789abcdef', cnt: 4, error: 1, ratio: 0.4, approximate: true }
  ],
  uvTypeStats: [
    { uvType: 'newUser', cnt: 3, ratio: 0.5 },
    { uvType: 'oldUser', cnt: 3, ratio: 0.5 }
  ],
  dimensionQuality: {
    countryStats: {
      status: 'PARTIAL',
      knownCount: 8,
      unknownCount: 2,
      eligibleCount: 10,
      coverage: 0.8,
      semantic: 'COUNTRY'
    },
    localeCnStats: {
      status: 'PARTIAL',
      knownCount: 6,
      unknownCount: 2,
      eligibleCount: 8,
      coverage: 0.75,
      semantic: 'CN_PROVINCE'
    },
    networkStats: {
      status: 'PARTIAL',
      knownCount: 7,
      unknownCount: 3,
      eligibleCount: 10,
      coverage: 0.7,
      semantic: 'ISP'
    },
    uvTypeStats: {
      status: 'PARTIAL',
      knownCount: 6,
      unknownUv: 1,
      coverage: 6 / 7,
      historyStart: Date.parse('2026-08-16T00:00:00+08:00'),
      historyEnd: Date.parse('2026-09-15T00:00:00+08:00')
    }
  }
}

function envelope(overrides = {}) {
  return {
    metrics: { requested: rawSummary },
    items: [],
    meta: {
      snapshotId: 'snapshot-1',
      nextCursor: null,
      completeness: 'PARTIAL',
      generatedAt: Date.parse('2026-09-14T14:32:00+08:00'),
      businessTimezone: 'Asia/Shanghai',
      dimensionQuality: rawSummary.dimensionQuality,
      approximation: { topIpStats: { type: 'APPROXIMATE' } },
      collectionQuality: { status: 'UNKNOWN', reasons: ['PRODUCER_ROSTER_NOT_CONFIGURED'] },
      ...overrides
    }
  }
}

test('maps only the requested online window and preserves missing fields instead of inventing zeroes', () => {
  const model = mapMetricsEnvelope(envelope())
  assert.equal(model.summary.pv, 10)
  assert.equal(model.meta.completeness, 'PARTIAL')
  assert.equal(model.summary.daily[0].date, '2026-09-14')

  const missing = mapMetricsEnvelope({
    ...envelope(),
    metrics: { requested: { pv: 0, uv: 0, uip: 0 } }
  })
  assert.equal(missing.summary.browserStats, null)
  assert.equal(dimensionView(missing, 'browser').quality.status, 'UNKNOWN')
})

test('keeps unknown geography and visitor history separate and labels ISP semantics', () => {
  const model = mapMetricsEnvelope(envelope())
  const country = dimensionView(model, 'country')
  assert.deepEqual(
    country.rows.map(({ label, count }) => [label, count]),
    [
      ['CN', 8],
      ['未知', 2]
    ]
  )
  const visitor = dimensionView(model, 'newvisitor')
  assert.deepEqual(
    visitor.rows.map(({ label, count }) => [label, count]),
    [
      ['新访客', 3],
      ['老访客', 3],
      ['未知', 1]
    ]
  )
  assert.match(dimensionView(model, 'isp').note, /运营商 \/ ISP/)
})

test('masks TopK and access-record hashes and never exposes a raw visitor first-seen claim', () => {
  const model = mapMetricsEnvelope(envelope())
  const ip = dimensionView(model, 'ip')
  assert.equal(ip.rows[0].label, '01234567…cdef')
  assert.equal(ip.approximate, true)

  const rawIp = 'a'.repeat(64)
  const rawVisitor = 'b'.repeat(64)
  const page = mapAccessEnvelope({
    metrics: {},
    items: [
      {
        eventId: 'event-1',
        linkId: '90071992547409930',
        occurredAt: Date.parse('2026-09-14T14:30:00+08:00'),
        ipHash: rawIp,
        visitorHash: rawVisitor,
        uvType: 'newUser',
        kind: 'CLICK',
        status: 302,
        country: 'CN',
        province: '广东省',
        city: '广州市',
        network: '中国电信',
        browser: 'Chrome',
        os: 'Android',
        device: 'Mobile',
        historyEarliestObservedAt: Date.parse('2026-08-16T00:00:00+08:00')
      }
    ],
    meta: {
      snapshotId: 'snapshot-1',
      nextCursor: 'cursor-2',
      completeness: 'COMPLETE',
      dimensionQuality: {}
    }
  })
  assert.equal(page.records[0].visitorType, '新访客')
  assert.equal(page.records[0].responseStatus, '302')
  assert.equal(page.records[0].occurredAtDisplay, '2026-09-14 14:30:00')
  assert.ok(!JSON.stringify(page).includes(rawIp))
  assert.ok(!JSON.stringify(page).includes(rawVisitor))
  assert.equal(Object.hasOwn(page.records[0], 'visitorFirstSeenAt'), false)
})

test('rejects malformed requested windows and continuation cursors', () => {
  assert.throws(
    () => mapMetricsEnvelope({ metrics: {}, items: [], meta: {} }),
    AnalyticsContractError
  )
  assert.throws(
    () =>
      mapAccessEnvelope({
        metrics: {},
        items: [],
        meta: { nextCursor: 'cursor-without-snapshot' }
      }),
    /快照游标/
  )
  assert.throws(
    () =>
      mapAccessEnvelope({ metrics: {}, items: [], meta: { snapshotId: 123, nextCursor: null } }),
    /快照标识/
  )
})

test('uses inclusive Shanghai calendar dates and enforces the seven-day boundary', () => {
  assert.deepEqual(defaultShanghaiDateRange(Date.parse('2026-09-14T00:00:00Z')), {
    startDate: '2026-09-08',
    endDate: '2026-09-14'
  })
  assert.equal(validateAnalyticsRange('2026-09-08', '2026-09-14').days, 7)
  assert.equal(validateAnalyticsRange('2026-09-08', '2026-09-15').code, 'TOO_LARGE')
  assert.equal(validateAnalyticsRange('2026-09-14', '2026-09-13').ok, false)
})

test('resolves only canonical authorized scope and blocks groups above 500 links', () => {
  const state = {
    groupId: 'g1',
    groups: [
      { id: 'g1', name: '投放组', count: 2 },
      { id: 'large', name: '大组', count: 501 }
    ],
    links: [{ id: 'l1', code: 'abc', title: '入口', groupId: 'g1', fullShortUrl: 's.example/abc' }]
  }
  assert.deepEqual(resolveAnalyticsScope(state, 'group', 'g1'), {
    type: 'group',
    id: 'g1',
    gid: 'g1',
    label: '投放组',
    count: 2
  })
  assert.equal(resolveAnalyticsScope(state, 'link', 'l1').fullShortUrl, 's.example/abc')
  assert.throws(
    () => resolveAnalyticsScope(state, 'group', 'large'),
    (error) => error.code === 'TOO_LARGE'
  )
  assert.throws(
    () => resolveAnalyticsScope(state, 'link', 'missing'),
    (error) => error.code === 'SCOPE_UNAVAILABLE'
  )
})

test('recognizes snapshot expiry even when the Admin boundary wraps the internal code', () => {
  const wrapped = classifyAnalyticsError({
    code: 'C000001',
    message: 'Analytics query unavailable: SNAPSHOT_EXPIRED'
  })
  assert.equal(wrapped.kind, 'snapshot-expired')
  assert.equal(wrapped.retryable, true)
})

test('keeps a fixed snapshot and rejects repeated continuation cursors', () => {
  const first = { snapshotId: 'snapshot-1', nextCursor: 'cursor-2' }
  const second = { snapshotId: 'snapshot-1', nextCursor: 'cursor-3' }
  assert.equal(validateAccessContinuation([first], second, 'cursor-2'), second)
  assert.throws(
    () =>
      validateAccessContinuation(
        [first],
        { snapshotId: 'snapshot-2', nextCursor: null },
        'cursor-2'
      ),
    (error) => error.code === 'SNAPSHOT_CHANGED'
  )
  assert.throws(
    () =>
      validateAccessContinuation(
        [first],
        { snapshotId: 'snapshot-1', nextCursor: 'cursor-2' },
        'cursor-2'
      ),
    (error) => error.code === 'REPEATED_PAGE_CURSOR'
  )
  assert.equal(classifyAnalyticsError({ code: 'REPEATED_PAGE_CURSOR' }).kind, 'snapshot-expired')
})
