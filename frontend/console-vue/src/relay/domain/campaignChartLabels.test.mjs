import test from 'node:test'
import assert from 'node:assert/strict'
import { campaignChartLabel } from './campaignChartLabels.js'

test('real UNKNOWN province label remains unknown, with its original fields available', () => {
  const raw = '{device={state=KNOWN, value=Desktop}, province={state=UNKNOWN, value=null}}'
  assert.deepEqual(campaignChartLabel(raw), {
    raw,
    text: '设备：Desktop · 省份：未知',
    axisText: '设备：Desktop\n省份：未知',
    formatted: true,
    hasUnknown: true
  })
})

test('category order, known values and zero-valued hour are preserved', () => {
  const labels = [
    '{province={state=KNOWN, value=广东省}, device={state=KNOWN, value=Mobile}}',
    '{hour={state=KNOWN, value=0}}',
    '{device={state=NOT_APPLICABLE, value=null}}'
  ]
  assert.deepEqual(
    labels.map((label) => campaignChartLabel(label).text),
    ['省份：广东省 · 设备：Mobile', '小时：00:00', '设备：不适用']
  )
  assert.ok(labels.every((label) => !campaignChartLabel(label).hasUnknown))
})

test('ordinary trend and ranking chart labels are unchanged', () => {
  for (const value of ['2026-09-13', '短链 20003', 0, 'UNKNOWN', '{campaign=summer}', '{}']) {
    assert.deepEqual(campaignChartLabel(value), {
      raw: String(value),
      text: String(value),
      axisText: String(value),
      formatted: false,
      hasUnknown: false
    })
  }
})

test('partial, ambiguous or extended map labels fall back to their full original text', () => {
  for (const raw of [
    '{province={state=UNKNOWN, value=null}, other=tail}',
    '{province={state=UNKNOWN, value=null}, }',
    '{province={state=UNKNOWN, value=null}, province={state=KNOWN, value=广东省}}',
    '{province={state=NEW_STATE, value=null}}',
    '{province={state=KNOWN, value=null}}',
    '{province={state=KNOWN, value=广东省, extra=field}}',
    '{province={value=广东省, state=KNOWN}}',
    '{custom={state=UNKNOWN, value=null}}',
    '{province={state=UNKNOWN, value=null}} trailing'
  ]) {
    const label = campaignChartLabel(raw)
    assert.equal(label.text, raw)
    assert.equal(label.formatted, false)
  }
})

test('commas in a known dimension value and UNKNOWN state semantics are retained', () => {
  assert.equal(
    campaignChartLabel('{province={state=KNOWN, value=Washington, DC}}').text,
    '省份：Washington, DC'
  )
  const label = campaignChartLabel('{province={state=UNKNOWN, value=untrusted}}')
  assert.equal(label.text, '省份：未知')
  assert.equal(label.hasUnknown, true)
  assert.match(label.raw, /untrusted/)
})
