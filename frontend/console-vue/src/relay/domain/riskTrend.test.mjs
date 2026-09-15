import assert from 'node:assert/strict'
import test from 'node:test'
import { buildRiskTrendGeometry, buildRiskTrendModel } from './riskTrend.js'

const observation = (date, score, level = 'UNKNOWN') => ({ date, score, level })
const geometry = (points, size) =>
  buildRiskTrendGeometry(buildRiskTrendModel(points).datedRows, size)

test('zero is an observed zero-height bar while missing scores have no plotted value', () => {
  const chart = geometry([
    observation('2026-09-10', 0, 'LOW'),
    observation('2026-09-11', null),
    observation('2026-09-12', 40)
  ])
  const [zero, missing, positive] = chart.points
  assert.equal(zero.score, 0)
  assert.equal(zero.barHeight, 0)
  assert.equal(zero.y, chart.zero)
  assert.equal(missing.score, null)
  assert.equal(missing.y, null)
  assert.ok(positive.barHeight > 0)
  assert.deepEqual(chart.segments, [])
  assert.equal(chart.ticks[0].value, 100)
})

test('connections stop at missing calendar days, null scores and ambiguous duplicate dates', () => {
  const chart = geometry([
    observation('2026-09-10', 10),
    observation('2026-09-11', null),
    observation('2026-09-12', 20),
    observation('2026-09-14', 30),
    observation('2026-09-15', 40),
    observation('2026-09-15', 45),
    observation('2026-09-16', 50),
    observation('2026-09-17', 60)
  ])
  assert.equal(chart.points.length, 8)
  assert.deepEqual(
    chart.segments.map(({ from, to }) => [from.date, to.date]),
    [['2026-09-16', '2026-09-17']]
  )
  assert.equal(chart.points.filter((point) => point.date === '2026-09-15').length, 2)
})

test('horizontal distances follow actual calendar days, independent of returned row order', () => {
  const input = [
    observation('2026-09-14', 30),
    observation('2026-09-10', 10),
    observation('2026-09-11', 20)
  ]
  const original = structuredClone(input)
  const chart = geometry(input, { width: 320, height: 140 })
  const [first, nextDay, threeDaysLater] = chart.points
  assert.deepEqual(
    chart.points.map((point) => point.date),
    ['2026-09-10', '2026-09-11', '2026-09-14']
  )
  assert.ok(Math.abs((threeDaysLater.x - nextDay.x) / (nextDay.x - first.x) - 3) < 1e-10)
  assert.deepEqual(input, original)
})

test('scores outside the usual 0–100 range retain their values and fit the vertical axis', () => {
  const chart = geometry([
    observation('2026-09-10', -12),
    observation('2026-09-11', 0),
    observation('2026-09-12', 140, 'HIGH')
  ])
  assert.deepEqual(
    chart.points.map((point) => point.score),
    [-12, 0, 140]
  )
  assert.equal(chart.ticks[0].value, 140)
  assert.equal(chart.ticks.at(-1).value, -12)
  assert.deepEqual(
    chart.ticks.map((tick) => tick.value),
    [140, 100, 70, 40, 0, -12]
  )
  assert.equal(chart.points[0].y, chart.bottom)
  assert.equal(chart.points[2].y, chart.top)
  assert.equal(chart.points[1].barHeight, 0)
  for (const point of chart.points) {
    assert.ok(Number.isFinite(point.y))
    assert.ok(point.barHeight >= 0)
  }
})

test('the normal score domain uses actual risk boundaries instead of generic midpoint ticks', () => {
  const chart = geometry(
    [
      observation('2026-09-10', 20, 'LOW'),
      observation('2026-09-11', 50, 'MEDIUM'),
      observation('2026-09-12', 85, 'HIGH')
    ],
    { width: 180, height: 140 }
  )
  assert.deepEqual(
    chart.ticks.map((tick) => tick.value),
    [100, 70, 40, 0]
  )
  assert.ok(chart.ticks.every((tick) => tick.labelled))
  assert.deepEqual(
    chart.points.map((point) => point.level),
    ['LOW', 'MEDIUM', 'HIGH']
  )
})

test('near-boundary outliers keep their endpoints without overlapping axis labels', () => {
  const chart = geometry([observation('2026-09-10', -1), observation('2026-09-11', 101, 'HIGH')], {
    width: 180,
    height: 140
  })
  assert.deepEqual(
    chart.ticks.map((tick) => tick.value),
    [101, 100, 70, 40, 0, -1]
  )
  assert.deepEqual(
    chart.points.map((point) => point.score),
    [-1, 101]
  )
  assert.equal(chart.ticks[0].labelled, true)
  assert.equal(chart.ticks.at(-1).labelled, true)
  const labels = chart.ticks.filter((tick) => tick.labelled)
  for (let index = 1; index < labels.length; index += 1)
    assert.ok(labels[index].y - labels[index - 1].y >= 12)
})

test('invalid dates remain in the table model without being guessed or plotted', () => {
  const model = buildRiskTrendModel([
    observation('2026-09-31', 18),
    observation('2026-09-15', 20),
    observation('', null),
    observation('2026-02-29', 10),
    observation('2024-02-29', 30)
  ])
  assert.equal(model.rows.length, 5)
  assert.equal(model.invalidDateCount, 3)
  assert.equal(model.rows[0].date, '2026-09-31')
  assert.equal(model.rows[0].score, 18)
  assert.deepEqual(
    model.datedRows.map((point) => point.date),
    ['2024-02-29', '2026-09-15']
  )
  assert.equal(buildRiskTrendGeometry(model.datedRows).points.length, 2)
})

test('a single observation is centered and an empty response has no invented points', () => {
  const chart = geometry([observation('2026-09-15', 20)], { width: 480, height: 140 })
  assert.equal(chart.points[0].x, (chart.left + chart.width - 12) / 2)
  assert.deepEqual(chart.segments, [])
  const empty = geometry([])
  assert.deepEqual(empty.points, [])
  assert.deepEqual(empty.segments, [])
  assert.deepEqual(empty.dates, [])
})
