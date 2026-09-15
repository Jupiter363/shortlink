const DAY = 86_400_000
const axisFormatter = new Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 1
})

export const formatRiskTrendTick = (value) => axisFormatter.format(value)

function dayTimestamp(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)
  date.setUTCHours(0, 0, 0, 0)
  return date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
    ? date.getTime()
    : null
}

/** Preserve every returned row; only valid calendar dates can be positioned on the chart. */
export function buildRiskTrendModel(points) {
  const rows = points.map((point, index) => ({
    id: index,
    date: typeof point?.date === 'string' && point.date ? point.date : '未提供日期',
    stamp: dayTimestamp(point?.date),
    score: typeof point?.score === 'number' && Number.isFinite(point.score) ? point.score : null,
    level: point?.level
  }))
  const datedRows = rows
    .filter((point) => point.stamp !== null)
    .sort((left, right) => left.stamp - right.stamp || left.id - right.id)
  return {
    rows,
    datedRows,
    invalidDateCount: rows.length - datedRows.length,
    missingScoreCount: datedRows.filter((point) => point.score === null).length
  }
}

/** Date spacing and score positions use observed values without filling missing days or scores. */
export function buildRiskTrendGeometry(datedRows, { width = 480, height = 140 } = {}) {
  const valid = datedRows.filter((point) => point.score !== null)
  const maximum = Math.max(100, ...valid.map((point) => point.score))
  const minimum = Math.min(0, ...valid.map((point) => point.score))
  const tickValues = [...new Set([maximum, 100, 70, 40, 0, minimum])].sort((a, b) => b - a)
  const left = Math.max(
    32,
    ...tickValues.map((value) => formatRiskTrendTick(value).length * 7 + 10)
  )
  const top = 9
  const bottom = Math.max(top + 30, height - 28)
  const plotWidth = Math.max(20, width - left - 12)
  const first = datedRows[0]?.stamp || 0
  const last = datedRows[datedRows.length - 1]?.stamp ?? first
  const start = first - DAY / 2
  const duration = Math.max(DAY, last - first + DAY)
  const y = (score) => top + ((maximum - score) / (maximum - minimum)) * (bottom - top)
  const labelledPositions = [top, bottom]
  const ticks = tickValues.map((value) => {
    const position = y(value)
    const labelled =
      value === maximum ||
      value === minimum ||
      labelledPositions.every((existing) => Math.abs(existing - position) >= 12)
    if (labelled) labelledPositions.push(position)
    return { value, y: position, labelled }
  })
  const zero = y(0)
  const step = (DAY / duration) * plotWidth
  const barWidth = Math.min(32, Math.max(1, step * 0.4))
  const plotted = datedRows.map((point, index) => {
    const x = left + ((point.stamp - start) / duration) * plotWidth
    const pointY = point.score === null ? null : y(point.score)
    return {
      ...point,
      index,
      x,
      y: pointY,
      barY: pointY === null ? zero : Math.min(pointY, zero),
      barHeight: pointY === null ? 0 : Math.abs(pointY - zero),
      barWidth,
      hitWidth: Math.max(12, Math.min(step * 0.8, 48))
    }
  })
  const dateCounts = new Map()
  for (const point of plotted) dateCounts.set(point.stamp, (dateCounts.get(point.stamp) || 0) + 1)
  const segments = []
  for (let index = 1; index < plotted.length; index += 1) {
    const previous = plotted[index - 1]
    const point = plotted[index]
    if (
      previous.y !== null &&
      point.y !== null &&
      point.stamp - previous.stamp === DAY &&
      dateCounts.get(previous.stamp) === 1 &&
      dateCounts.get(point.stamp) === 1
    )
      segments.push({ from: previous, to: point })
  }
  const dates = plotted.filter(
    (point, index) => index === 0 || point.stamp !== plotted[index - 1].stamp
  )
  const dateLabelEvery = Math.max(
    1,
    Math.ceil(dates.length / Math.max(2, Math.floor(plotWidth / 50)))
  )
  return {
    width,
    height,
    left,
    top,
    bottom,
    zero,
    points: plotted,
    segments,
    ticks,
    dates: dates.filter(
      (point, index) => index % dateLabelEvery === 0 || index === dates.length - 1
    )
  }
}
