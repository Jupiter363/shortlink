const EMPTY = '—'
const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
})

function valueText(value) {
  if (value === null || value === undefined) return ''
  const text = String(value).trim()
  return /^(unknown|null|undefined|n\/a|-|—)$/i.test(text) ? '' : text
}

function recordTime(value) {
  const text = valueText(value)
  if (!text) return EMPTY
  let input = text
  if (/^\d+$/.test(text)) input = Number(text)
  // The old API returned local Shanghai wall-clock strings without an offset.
  else if (/^\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)?$/.test(text)) {
    input = `${text.length === 10 ? `${text}T00:00:00` : text.replace(' ', 'T')}+08:00`
  }
  const date = new Date(input)
  if (!Number.isFinite(date.getTime())) return EMPTY
  const parts = Object.fromEntries(dateFormatter.formatToParts(date).map(({ type, value }) => [type, value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}

function hashLabel(value) {
  const hash = valueText(value)
  if (!hash) return ''
  // The Agent may already return a short, masked digest such as "hash: 429a…061d".
  if (hash.length <= 64 && /^hash:\s*[0-9a-f]+(?:…|\.{3})[0-9a-f]+$/i.test(hash)) return hash
  const digest = hash.replace(/^hash:\s*/i, '')
  return `哈希 ${digest.slice(0, 12)}${digest.length > 12 ? '…' : ''}`
}

function maskedLegacyIp(value) {
  const ip = valueText(value)
  if (/^(?:\d{1,3}\.){3}\d{1,3}$/.test(ip)) return `${ip.split('.').slice(0, 2).join('.')}.*.*`
  if (/^[0-9a-f:]+$/i.test(ip) && ip.includes(':')) return `${ip.split(':').slice(0, 2).join(':')}:****`
  if (/^[0-9a-f.*:xX…-]+$/i.test(ip) && /[*xX…]/.test(ip)) return ip.slice(0, 48)
  return EMPTY
}

function legacyVisitor(value) {
  const type = valueText(value)
  const labels = { NEW: '新访客', OLD: '老访客', FIRST: '新访客', RETURNING: '老访客', NEWUSER: '新访客', OLDUSER: '老访客' }
  return labels[type.toUpperCase()] || (/^[新老旧]访客$/.test(type) ? type : EMPTY)
}

function statusText(value) {
  const text = valueText(value)
  if (!text) return ''
  // Persisted zero means no known HTTP result. It must not turn into a successful redirect.
  if (typeof value === 'number' || /^[+-]?\d+(?:\.\d+)?$/.test(text)) {
    const status = Number(text)
    return Number.isInteger(status) && status >= 100 && status <= 599 ? String(status) : ''
  }
  return text
}

export function toAccessRecordRow(record) {
  const row = record || {}
  const location = valueText(row.locale) || [...new Set([row.country, row.province, row.city].map(valueText).filter(Boolean))].join(' · ')
  const status = [...new Set([row.httpStatus, row.status, row.result].map(statusText).filter(Boolean))].join(' · ')
  // Return display fields only: raw IPs and full visitor identifiers never enter table cells.
  return {
    createTime: recordTime(valueText(row.occurredAt) || row.createTime),
    locale: location || EMPTY,
    device: valueText(row.device) || EMPTY,
    browser: valueText(row.browser) || EMPTY,
    os: valueText(row.os) || EMPTY,
    network: valueText(row.network) || EMPTY,
    ip: hashLabel(row.ipHash) || maskedLegacyIp(row.ip),
    uvType: hashLabel(row.visitorHash) || legacyVisitor(row.uvType),
    visitorType: legacyVisitor(row.visitorType || row.uvType),
    status: status || EMPTY,
    eventType: valueText(row.eventType) || valueText(row.kind) || EMPTY
  }
}
