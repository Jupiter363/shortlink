import { analyticDimensionLabel, analyticDimensionValue } from './agentModel.js'

// Older report projections use Java Map.toString for dimension categories.
// Accept only complete state/value cells; other chart labels remain untouched.
export function campaignChartLabel(value) {
  const raw = String(value)
  const unchanged = { raw, text: raw, axisText: raw, formatted: false, hasUnknown: false }
  if (!raw.startsWith('{') || !raw.endsWith('}')) return unchanged
  const body = raw.slice(1, -1)
  const cellPattern =
    /([A-Za-z][A-Za-z0-9]*)=\{state=(KNOWN|UNKNOWN|NOT_APPLICABLE), value=([^{}]*)\}/y
  const dimensions = new Set()
  const parts = []
  let hasUnknown = false
  let offset = 0
  while (offset < body.length) {
    cellPattern.lastIndex = offset
    const match = cellPattern.exec(body)
    if (!match) return unchanged
    const [, dimension, state, cellValue] = match
    const label = analyticDimensionLabel(dimension)
    if (
      label === dimension ||
      dimensions.has(dimension) ||
      /,\s*[A-Za-z][A-Za-z0-9]*=/.test(cellValue) ||
      (state === 'KNOWN' && cellValue === 'null')
    )
      return unchanged
    dimensions.add(dimension)
    parts.push(`${label}：${analyticDimensionValue({ state, value: cellValue }, dimension)}`)
    hasUnknown ||= state === 'UNKNOWN'
    offset = cellPattern.lastIndex
    if (offset < body.length) {
      if (body.slice(offset, offset + 2) !== ', ' || offset + 2 === body.length) return unchanged
      offset += 2
    }
  }
  if (!parts.length) return unchanged
  return {
    raw,
    text: parts.join(' · '),
    axisText: parts.join('\n'),
    formatted: true,
    hasUnknown
  }
}
