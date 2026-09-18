import test from 'node:test'
import assert from 'node:assert/strict'
import {
  analyticDimensionLabel,
  analyticDimensionValue,
  analyticNumber,
  analyticPage,
  analyticQuality,
  analyticRate,
  buildChatBody,
  compileMessage,
  errorMessage,
  newAgentSession,
  normalizeAgentResult,
  sanitize,
  safeText
} from './agentModel.js'

test('chat DTO only carries three permitted browser fields', () => {
  const body = buildChatBody({
    sessionId: 'session-a',
    agentType: 'security-risk',
    message: '查看风险',
    username: 'spoofed',
    serviceKey: 'secret',
    authVersion: 1
  })
  assert.deepEqual(body, {
    sessionId: 'session-a',
    agentType: 'security-risk',
    message: '查看风险'
  })
})
test('chat validates message, type and the full 2000-character budget', () => {
  const base = { sessionId: 'a', agentType: 'campaign-analysis' }
  assert.throws(() => buildChatBody({ ...base, message: ' ' }))
  assert.throws(() => buildChatBody({ ...base, message: 'a'.repeat(2001) }))
  assert.throws(() => buildChatBody({ ...base, agentType: 'other', message: 'a' }))
  assert.equal(buildChatBody({ ...base, message: 'a'.repeat(2000) }).message.length, 2000)
})
test('scope compiles into server-readable gid with no Chinese punctuation suffix', () => {
  const message = compileMessage('查看最近 7 天统计', { id: 'g_123', name: '秋日投放' })
  const match = message.match(/(gid|fullShortUrl|startDate|endDate)\s*[:=：]\s*([^\s,;，；]+)/)
  assert.equal(match[2], 'g_123')
  assert.equal(compileMessage(' ', { id: 'g' }), '')
  assert.equal(compileMessage('查看已授权范围'), '查看已授权范围')
})
test('independent sessions get different IDs and independent history', () => {
  const first = newAgentSession('campaign-analysis')
  const second = newAgentSession('security-risk')
  assert.notEqual(first.id, second.id)
  first.history.push({ answer: 'test' })
  assert.equal(second.history.length, 0)
  assert.equal(second.runState, 'READY')
})
test('absent result does not invent an answer or completed tool timeline', () => {
  assert.throws(() => normalizeAgentResult(null))
  assert.throws(() => normalizeAgentResult({ cards: [] }))
  const result = normalizeAgentResult({ answer: '', cards: null, toolCalls: null })
  assert.deepEqual(result.toolCalls, [])
  assert.deepEqual(result.traceEvents, [])
  assert.equal(result.answer, '')
})
test('actual tool failure and missing status remain distinct from completion', () => {
  const result = normalizeAgentResult({
    answer: '证据部分缺失',
    toolCalls: [
      { name: 'stats', success: false, message: 'Unavailable' },
      { name: 'groups', success: true },
      { name: 'other' }
    ]
  })
  assert.deepEqual(
    result.toolCalls.map((tool) => tool.tone),
    ['danger', 'success', 'unknown']
  )
  assert.equal(result.toolCalls[2].outcome, '未提供状态')
})
test('redaction covers credentials, nested headers, IPs and identity hashes without mutating input', () => {
  const raw = {
    token: 'private-token',
    nested: {
      serviceKey: 'private-service',
      ip: '12.34.56.78',
      ipHash: 'a'.repeat(64),
      visitorHash: 'b'.repeat(64),
      headers: { Token: 'private-header' }
    },
    answer: 'Bearer abc.def https://service/x?token=private-query sk-1234567890abcdefgh',
    traceId: 'trace-readable'
  }
  const output = sanitize(raw)
  const text = JSON.stringify(output)
  for (const sensitive of [
    'private-token',
    'private-service',
    'private-header',
    'private-query',
    'abc.def',
    '1234567890abcdefgh',
    '12.34.56.78',
    'a'.repeat(64)
  ])
    assert.ok(!text.includes(sensitive))
  assert.equal(output.nested.ip, '12.34.*.*')
  assert.equal(output.traceId, 'trace-readable')
  assert.equal(raw.token, 'private-token')
})
test('access-record cards use the existing canonical privacy mapper', () => {
  const result = normalizeAgentResult({
    answer: '记录',
    cards: [
      {
        type: 'access_records',
        rows: [
          {
            ip: '12.34.56.78',
            visitorHash: 'a'.repeat(64),
            kind: 'CLICK',
            status: 429,
            network: '运营商',
            visitorType: 'NEW'
          }
        ]
      }
    ]
  })
  const row = result.cards[0].rows[0]
  assert.equal(row.ip, '12.34.*.*')
  assert.equal(row.status, '429')
  assert.equal(row.eventType, 'CLICK')
  assert.equal(row.network, '运营商')
  assert.ok(!JSON.stringify(result).includes('a'.repeat(64)))
})
test('free-form diagnostics mask IPv6 and credentials while preserving ordinary timestamps', () => {
  const text = safeText('14:02:03 IP 2001:db8:85a3::8a2e:370:7334 apiKey=private-value')
  assert.ok(text.includes('14:02:03'))
  assert.ok(!text.includes('8a2e:370:7334'))
  assert.ok(!text.includes('private-value'))
  assert.equal(safeText(text), text)
  const url = safeText('https://example.test/?token=private-url')
  assert.equal(safeText(url), url)
})
test('timeouts remain uncertain instead of triggering automatic retry advice', () => {
  assert.match(errorMessage({ code: 'TIMEOUT' }), /结果尚不确定/)
  assert.match(errorMessage({ status: 403 }), /没有/)
  assert.match(errorMessage({ status: 429, retryAfter: 12 }), /12 秒/)
})

test('comparison and ranking evidence preserve sanitized rows without inventing missing counts', () => {
  const raw = {
    answer: '对比结果',
    cards: [
      {
        type: 'comparison',
        status: 'READY',
        rows: [
          {
            key: 'a/today',
            gid: 'a',
            label: '<img src=x onerror=alert(1)>',
            startDate: '2026-09-19',
            endDate: '2026-09-19',
            pv: 0,
            uv: null,
            uip: 3,
            quality: { completeness: 'PARTIAL', serviceKey: 'private-service' }
          },
          { key: 'b/yesterday', gid: 'b', pv: 12, uv: 7, uip: Infinity },
          null,
          'not a row'
        ],
        continuation: { scopes: [{ gid: 'a' }], jobs: ['hidden-job'] }
      },
      {
        type: 'ranking',
        rows: [{ rank: 1, linkId: 'link-a', pv: 9, uv: 4, uip: 2, pvShare: 0.75 }]
      }
    ]
  }
  const result = normalizeAgentResult(raw)
  assert.equal(result.cards[0].rows.length, 2)
  assert.equal(result.cards[0].rows[0].pv, 0)
  assert.equal(result.cards[0].rows[0].uv, null)
  assert.equal(result.cards[0].rows[0].label, '<img src=x onerror=alert(1)>')
  assert.equal(result.cards[0].rows[0].quality.serviceKey, '[已脱敏]')
  assert.equal(result.cards[0].rows[1].uip, null)
  assert.equal(result.cards[0].continuation, undefined)
  assert.equal(result.cards[1].rows[0].pvShare, 0.75)
  assert.equal(raw.cards[0].rows[0].quality.serviceKey, 'private-service')
  assert.ok(raw.cards[0].continuation)
})

test('comparison deltas retain server comparability and a zero-base rate stays unavailable', () => {
  const result = normalizeAgentResult({
    answer: '比较',
    cards: [
      {
        type: 'comparison',
        status: 'INCOMPLETE',
        comparisons: [
          {
            targetKey: 'today',
            baselineKey: 'yesterday',
            metric: 'pv',
            delta: 5,
            rate: null,
            comparable: false,
            warnings: ['基期为 0']
          },
          { metric: 'uv', delta: -3, rate: -0.5, comparable: true },
          { metric: 'uip', delta: NaN, rate: Infinity, comparable: 'true' },
          { metric: 'unrecognized', delta: 4 }
        ]
      }
    ]
  })
  const card = result.cards[0]
  assert.equal(card.status, 'INCOMPLETE')
  assert.deepEqual(card.rows, [])
  assert.equal(card.comparisons.length, 3)
  assert.equal(card.comparisons[0].rate, null)
  assert.equal(card.comparisons[0].comparable, false)
  assert.equal(card.comparisons[1].delta, -3)
  assert.equal(card.comparisons[1].rate, -0.5)
  assert.equal(card.comparisons[2].delta, null)
  assert.equal(card.comparisons[2].rate, null)
  assert.equal(card.comparisons[2].comparable, false)
})

test('pending analytic evidence remains pending with empty rows and no false zero metrics', () => {
  const card = normalizeAgentResult({
    answer: '正在查询',
    cards: [{ type: 'ranking', status: 'PENDING', rows: null, warnings: ['请稍后查看结果'] }]
  }).cards[0]
  assert.equal(card.status, 'PENDING')
  assert.deepEqual(card.rows, [])
  assert.equal(card.metrics, undefined)
  assert.deepEqual(card.warnings, ['请稍后查看结果'])
})

test('analytic display distinguishes missing, zero, signed difference and fractional rates', () => {
  for (const value of [undefined, null, '', '12', Infinity, NaN]) {
    assert.equal(analyticNumber(value), '—')
    assert.equal(analyticRate(value), '—')
  }
  assert.equal(analyticNumber(0), '0')
  assert.equal(analyticNumber(1200, true), '+1,200')
  assert.equal(analyticNumber(-3, true), '-3')
  assert.equal(analyticRate(0.125, true), '+12.5%')
  assert.equal(analyticRate(-0.5, true), '-50%')
  assert.equal(analyticRate(0, true), '0%')
})

test('analytic quality does not promote partial, unknown or stale data to complete fresh evidence', () => {
  assert.equal(analyticQuality({}).tone, 'unknown')
  assert.match(analyticQuality({}).label, /完整性未知/)
  assert.equal(analyticQuality({ availability: 'UNAVAILABLE' }).tone, 'danger')
  assert.equal(
    analyticQuality({ availability: 'AVAILABLE', completeness: 'PARTIAL', freshness: 'FRESH' })
      .tone,
    'warning'
  )
  assert.equal(
    analyticQuality({ availability: 'AVAILABLE', completeness: 'COMPLETE', freshness: 'UNKNOWN' })
      .tone,
    'unknown'
  )
  assert.equal(
    analyticQuality({ availability: 'AVAILABLE', completeness: 'COMPLETE', freshness: 'STALE' })
      .tone,
    'warning'
  )
  assert.equal(
    analyticQuality({
      availability: 'AVAILABLE',
      completeness: 'COMPLETE',
      freshness: 'FRESH',
      provisional: true
    }).tone,
    'warning'
  )
  assert.equal(
    analyticQuality({ availability: 'AVAILABLE', completeness: 'COMPLETE', freshness: 'FRESH' })
      .tone,
    'success'
  )
})

test('dimension evidence retains joint buckets, all filters and independent range totals', () => {
  const raw = {
    answer: '联合维度结果',
    cards: [
      {
        type: 'dimension_breakdown',
        status: 'READY',
        gid: 'group-a',
        startDate: '2026-09-12',
        endDate: '2026-09-18',
        dimensions: ['province', 'device'],
        filters: [
          { dimension: 'device', operator: 'IN', values: ['PC', 'Mobile'] },
          { dimension: 'refererDomain', operator: 'IS_UNKNOWN' }
        ],
        metrics: { pv: 10, uv: 3, uip: 2, ratioDenominator: 10 },
        meta: {
          totalRows: 2,
          resultComplete: true,
          dimensionQualityScope: 'FILTERED_FULL_WINDOW',
          completeness: 'PARTIAL'
        },
        rows: [
          {
            dimensions: {
              province: { value: '广东省', state: 'KNOWN' },
              device: { value: 'PC', state: 'KNOWN' }
            },
            pv: 7,
            uv: 3,
            uip: 2,
            pvRatio: 0.7
          },
          {
            dimensions: {
              province: { value: null, state: 'UNKNOWN' },
              device: { value: 'Mobile', state: 'KNOWN' }
            },
            pv: 3,
            uv: 2,
            uip: 1,
            pvRatio: 0.3
          }
        ],
        continuation: { jobId: 'do-not-render' }
      }
    ]
  }
  const card = normalizeAgentResult(raw).cards[0]
  assert.deepEqual(card.dimensions, ['province', 'device'])
  assert.equal(card.rows[0].dimensions.province.value, '广东省')
  assert.equal(card.rows[0].dimensions.device.value, 'PC')
  assert.equal(card.rows[1].dimensions.province.state, 'UNKNOWN')
  assert.equal(card.rows[0].pvRatio, 0.7)
  assert.equal(card.metrics.uv, 3)
  assert.equal(
    card.rows.reduce((sum, row) => sum + row.uv, 0),
    5
  )
  assert.equal(card.filters.length, 2)
  assert.deepEqual(card.filters[0].values, ['PC', 'Mobile'])
  assert.equal(card.filters[1].operator, 'IS_UNKNOWN')
  assert.equal(card.meta.dimensionQualityScope, 'FILTERED_FULL_WINDOW')
  assert.equal(card.meta.completeness, 'PARTIAL')
  assert.equal(card.continuation, undefined)
})

test('dimension labels distinguish unknown, inapplicable and absent evidence without inventing values', () => {
  assert.equal(analyticDimensionLabel('refererDomain'), '来源域名')
  assert.equal(analyticDimensionValue({ state: 'UNKNOWN', value: 'not-trusted' }), '未知')
  assert.equal(analyticDimensionValue({ state: 'NOT_APPLICABLE', value: null }), '不适用')
  assert.equal(analyticDimensionValue(undefined), '未提供')
  assert.equal(analyticDimensionValue({ state: 'KNOWN', value: '' }), '未提供')
  assert.equal(analyticDimensionValue({ state: 'KNOWN', value: '9' }, 'hour'), '09:00')
  assert.equal(analyticDimensionValue({ state: 'KNOWN', value: 0 }, 'hour'), '00:00')
  assert.equal(analyticDimensionValue({ state: 'KNOWN', value: '7' }, 'weekday'), '星期日')
  assert.equal(
    analyticDimensionValue({ state: 'KNOWN', value: '<svg onload=alert(1)>' }),
    '<svg onload=alert(1)>'
  )
})

test('5000 dimension buckets use bounded local display pages and retain all server rows', () => {
  const rows = Array.from({ length: 5000 }, (_, index) => ({
    pv: index,
    dimensions: { hour: { value: `${index % 24}`, state: 'KNOWN' } }
  }))
  const card = normalizeAgentResult({
    answer: '维度',
    cards: [{ type: 'dimension_breakdown', dimensions: ['hour'], rows }]
  }).cards[0]
  const first = analyticPage(card.rows)
  const last = analyticPage(card.rows, 200)
  const fifty = analyticPage(card.rows, 100, 50)
  assert.equal(card.rows.length, 5000)
  assert.equal(first.rows.length, 25)
  assert.equal(first.pageCount, 200)
  assert.equal(first.rows[0].pv, 0)
  assert.equal(last.rows.length, 25)
  assert.equal(last.from, 4976)
  assert.equal(last.to, 5000)
  assert.equal(fifty.rows.length, 50)
  assert.equal(fifty.pageCount, 100)
  assert.equal(fifty.rows[49].pv, 4999)
  assert.equal(analyticPage(card.rows, Infinity, 5000).rows.length, 25)
  assert.equal(analyticPage(card.rows, -4).page, 1)
  assert.equal(analyticPage(card.rows, 500).page, 200)
  assert.equal(analyticPage([], 3).from, 0)
  assert.equal(analyticPage([], 3).to, 0)
})

test('pending dimension results keep the selected scope and filters without fabricating counts', () => {
  const card = normalizeAgentResult({
    answer: '等待查询',
    cards: [
      {
        type: 'dimension_breakdown',
        status: 'PENDING',
        gid: 'group-a',
        startDate: '2026-08-01',
        endDate: '2026-08-31',
        dimensions: ['province', 'device'],
        filters: [{ dimension: 'province', operator: 'IS_UNKNOWN' }],
        rows: null
      }
    ]
  }).cards[0]
  assert.equal(card.status, 'PENDING')
  assert.equal(card.gid, 'group-a')
  assert.equal(card.startDate, '2026-08-01')
  assert.deepEqual(card.dimensions, ['province', 'device'])
  assert.equal(card.filters[0].operator, 'IS_UNKNOWN')
  assert.equal(card.metrics, undefined)
  assert.deepEqual(card.rows, [])
})
