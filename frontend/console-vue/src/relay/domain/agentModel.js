import { toAccessRecordRow } from '../../utils/agentAccessRecords.js'
import { normalizeCampaignReport } from './campaignReport.js'

export const AGENT_TYPES = ['campaign-analysis', 'security-risk']
export const array = (value) => (Array.isArray(value) ? value : [])
export const object = (value) =>
  value && typeof value === 'object' && !Array.isArray(value) ? value : {}

export function safeText(value) {
  const text = value == null ? '' : String(value)
  return text
    .replace(/\bsk-[A-Za-z0-9_-]{12,}/g, '[已脱敏]')
    .replace(/\bBearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [已脱敏]')
    .replace(
      /([?&](?:token|api[_-]?key|secret|password|authorization)=)(?!\[已脱敏\])[^&#\s]+/gi,
      '$1[已脱敏]'
    )
    .replace(
      /((?:api[_-]?key|service[_-]?key|password|secret|token)\s*[:=]\s*["']?)(?!\[已脱敏\])[^"'\s,;\]&]+/gi,
      '$1[已脱敏]'
    )
    .replace(/\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b/g, '$1.$2.*.*')
    .replace(/(?:[A-Fa-f0-9]{0,4}:){2,}[A-Fa-f0-9]{0,4}/g, (candidate) => {
      // URL's IPv6 parser avoids masking normal clock strings such as 14:02:03.
      try {
        new URL(`https://[${candidate}]/`)
        return candidate.split(':').slice(0, 2).join(':') + ':****'
      } catch {
        return candidate
      }
    })
}

// Response diagnostics are untrusted data: redact before storing/rendering, never render HTML.
export function sanitize(value, depth = 0, seen = new WeakSet()) {
  if (depth > 12) return '[内容层级过深]'
  if (typeof value === 'string') return safeText(value)
  if (value == null || typeof value !== 'object') return value
  if (seen.has(value)) return '[重复引用]'
  seen.add(value)
  const output = Array.isArray(value) ? [] : {}
  for (const [key, child] of Object.entries(value)) {
    if (
      /password|passwd|secret|api.?key|service.?key|authorization|cookie|access.?token|refresh.?token|internal.?token|^token$|credential|private.?key|^headers$/i.test(
        key
      )
    ) {
      output[key] = '[已脱敏]'
    } else if (/^(?:ip|clientIp|remoteAddr|sourceIp)$/i.test(key)) {
      output[key] =
        typeof child === 'string' && child.includes(':')
          ? child.split(':').slice(0, 2).join(':') + ':****'
          : safeText(child)
    } else if (/^(?:visitorId|visitorHash|ipHash|username|realName|tenantId)$/i.test(key)) {
      output[key] = child == null ? null : '[已脱敏]'
    } else output[key] = sanitize(child, depth + 1, seen)
  }
  seen.delete(value)
  return output
}

export const pretty = (value) => JSON.stringify(sanitize(value), null, 2)

const analyticTypes = new Set(['comparison', 'ranking', 'dimension_breakdown'])
const dimensionLabels = {
  day: '日期',
  hour: '小时',
  weekday: '星期',
  country: '国家 / 地区',
  province: '省份',
  device: '设备',
  os: '操作系统',
  browser: '浏览器',
  isp: '运营商',
  refererDomain: '来源域名'
}
const finiteMetric = (value) => (typeof value === 'number' && Number.isFinite(value) ? value : null)
const metricFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const rateFormatter = new Intl.NumberFormat('zh-CN', {
  style: 'percent',
  maximumFractionDigits: 2
})

export function analyticNumber(value, signed = false) {
  const number = finiteMetric(value)
  if (number == null) return '—'
  return `${signed && number > 0 ? '+' : ''}${metricFormatter.format(number)}`
}

export function analyticRate(value, signed = false) {
  const number = finiteMetric(value)
  if (number == null) return '—'
  return `${signed && number > 0 ? '+' : ''}${rateFormatter.format(number)}`
}

export function analyticDimensionLabel(dimension) {
  return dimensionLabels[dimension] || safeText(dimension)
}

export function analyticDimensionValue(cell, dimension) {
  if (cell?.state === 'NOT_APPLICABLE') return '不适用'
  if (cell?.state === 'UNKNOWN') return '未知'
  if (cell?.state !== 'KNOWN' || cell.value == null || cell.value === '') return '未提供'
  if (dimension === 'hour' && /^\d{1,2}$/.test(String(cell.value))) {
    const hour = Number(cell.value)
    if (hour >= 0 && hour < 24) return `${String(hour).padStart(2, '0')}:00`
  }
  if (dimension === 'weekday') {
    const weekdays = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日']
    if (/^[1-7]$/.test(String(cell.value))) return weekdays[Number(cell.value) - 1]
  }
  return safeText(cell.value)
}

// This only paginates already returned evidence; it never changes server query scope or cursors.
export function analyticPage(value, requestedPage = 1, requestedSize = 25) {
  const rows = array(value)
  const pageSize = requestedSize === 50 ? 50 : 25
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize))
  const page = Number.isFinite(requestedPage)
    ? Math.min(pageCount, Math.max(1, Math.floor(requestedPage)))
    : 1
  const start = (page - 1) * pageSize
  return {
    rows: rows.slice(start, start + pageSize),
    total: rows.length,
    page,
    pageSize,
    pageCount,
    from: rows.length ? start + 1 : 0,
    to: Math.min(start + pageSize, rows.length)
  }
}

export function analyticQuality(value) {
  const meta = object(value)
  if (meta.availability === 'UNAVAILABLE') return { label: '数据不可用', tone: 'danger' }
  const complete = meta.completeness === 'COMPLETE'
  const partial = meta.completeness === 'PARTIAL'
  const labels = [complete ? '完整' : partial ? '部分数据' : '完整性未知']
  if (meta.freshness === 'STALE') labels.push('已陈旧')
  else if (meta.freshness !== 'FRESH') labels.push('时效未知')
  if (meta.provisional === true) labels.push('临时快照')
  if (meta.availability !== 'AVAILABLE') labels.push('可用性待核实')
  return {
    label: labels.join(' · '),
    tone:
      partial || meta.freshness === 'STALE' || meta.provisional === true
        ? 'warning'
        : complete && meta.availability === 'AVAILABLE' && meta.freshness === 'FRESH'
          ? 'success'
          : 'unknown'
  }
}

function analyticCard(value) {
  const card = { ...object(value) }
  // Server continuation references are used by the conversation, not by the evidence UI.
  delete card.continuation
  card.rows = array(card.rows)
    .filter((row) => row && typeof row === 'object' && !Array.isArray(row))
    .map((row) => ({
      ...row,
      pv: finiteMetric(row.pv),
      uv: finiteMetric(row.uv),
      uip: finiteMetric(row.uip),
      pvShare: finiteMetric(row.pvShare),
      ...(card.type === 'dimension_breakdown'
        ? { dimensions: object(row.dimensions), pvRatio: finiteMetric(row.pvRatio) }
        : {}),
      quality: object(row.quality)
    }))
  card.comparisons = array(card.comparisons)
    .filter((comparison) => ['pv', 'uv', 'uip'].includes(comparison?.metric))
    .map((comparison) => ({
      ...comparison,
      delta: finiteMetric(comparison.delta),
      rate: finiteMetric(comparison.rate),
      comparable: comparison.comparable === true,
      warnings: array(comparison.warnings).filter((warning) => typeof warning === 'string')
    }))
  card.warnings = array(card.warnings).filter((warning) => typeof warning === 'string')
  if (card.type === 'dimension_breakdown') {
    card.dimensions = [
      ...new Set(array(card.dimensions).filter((value) => typeof value === 'string'))
    ]
    card.filters = array(card.filters)
      .filter((filter) => filter && typeof filter === 'object' && !Array.isArray(filter))
      .map((filter) => ({ ...filter, values: array(filter.values) }))
  }
  return card
}

export function buildChatBody(input) {
  if (!AGENT_TYPES.includes(input.agentType)) throw new Error('请选择有效的 Agent。')
  const sessionId = String(input.sessionId || '').trim()
  const message = String(input.message || '').trim()
  const cancelling = input.agentType === 'campaign-analysis' && input.operation === 'CANCEL'
  if (!sessionId) throw new Error('会话尚未准备好，请新建会话。')
  if (!cancelling && !message) throw new Error('请输入问题。')
  if (!cancelling && message.length > 2000) throw new Error('问题与分析范围合计不能超过 2000 字。')
  if (cancelling && !input.continuation) throw new Error('停止操作需要本次分析的续接标识。')
  const requestKey = input.requestKey == null ? '' : String(input.requestKey)
  if (requestKey && !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}$/.test(requestKey))
    throw new Error('请求标识无效，请重新提交。')
  const body = {
    sessionId,
    agentType: input.agentType,
    ...(cancelling ? {} : { message }),
    ...(requestKey ? { requestKey } : {})
  }
  if (input.agentType === 'campaign-analysis') {
    if (input.clientCapabilities) body.clientCapabilities = ['campaign-response/v2']
    if (input.operation) {
      if (!['NEW', 'CONTINUE', 'PROGRESS', 'CANCEL'].includes(input.operation))
        throw new Error('分析操作无效。')
      body.operation = input.operation
    }
    if (input.continuation) {
      const { runId, requestId } = input.continuation
      if (
        ![runId, requestId].every(
          (value) => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)
        )
      )
        throw new Error('续接标识不完整，请重新读取本次分析。')
      body.continuation = { runId, requestId }
    }
    if (!cancelling && input.previousRunId) body.previousRunId = String(input.previousRunId)
  }
  return body
}

export function compileMessage(prompt, group) {
  const text = String(prompt || '').trim()
  if (!text) return ''
  // The server accepts an explicit gid= token terminated by whitespace or an ASCII semicolon.
  return group?.id ? `分析范围：分组「${group.name}」；gid=${group.id};\n${text}` : text
}

export function newAgentSession(type) {
  return {
    id: `relay-${type}-${globalThis.crypto.randomUUID()}`,
    prompt: '',
    groupId: '',
    runState: 'READY',
    result: null,
    error: '',
    debugOpen: false,
    runId: null,
    history: []
  }
}

export function normalizeAgentResult(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || typeof raw.answer !== 'string') {
    throw new Error('Agent 返回了无法识别的结果；未将本次响应标记为成功。')
  }
  const result = sanitize(raw)
  if (raw.report?.view) {
    try {
      result.report.view = normalizeCampaignReport(result.report.view)
      const view = result.report.view
      if (
        (result.report.reportRef &&
          (result.report.reportRef.reportId !== view.reportRef.reportId ||
            result.report.reportRef.revision !== view.reportRef.revision)) ||
        (result.progress &&
          (result.progress.runId !== view.runId ||
            result.progress.planId !== view.planId ||
            result.progress.planRevision !== view.planRevision))
      )
        throw new Error('报告与当前分析版本不一致，请重新读取。')
    } catch (error) {
      result.report.view = null
      result.reportError = error.message
    }
  }
  result.cards = array(raw.cards).map((card, index) => {
    const clean = sanitize(card)
    const normalized = analyticTypes.has(card?.type) ? analyticCard(clean) : object(clean)
    return {
      ...normalized,
      key: `card-${index}`,
      title: safeText(card?.title || card?.type || '分析证据'),
      rows:
        card?.type === 'access_records'
          ? array(card.rows).map(toAccessRecordRow)
          : analyticTypes.has(card?.type)
            ? normalized.rows
            : []
    }
  })
  result.toolCalls = array(result.toolCalls).map((tool, index) => {
    const value = object(tool)
    const success = value.success === true || value.result?.success === true
    const failed = value.success === false || value.result?.success === false
    return {
      ...value,
      key: `tool-${index}`,
      label: safeText(value.name || value.toolName || `工具 ${index + 1}`),
      outcome: success ? '已完成' : failed ? '执行失败' : safeText(value.status || '未提供状态'),
      tone: success ? 'success' : failed ? 'danger' : 'unknown'
    }
  })
  result.traceEvents = array(result.traceEvents).map((node, index) => ({
    ...object(node),
    key: `node-${index}`,
    label: safeText(node?.nodeName || node?.name || `节点 ${index + 1}`),
    status: safeText(node?.status || '未提供状态')
  }))
  result.pendingActions = array(result.pendingActions)
  result.dataSources = array(result.dataSources)
  result.warnings = array(result.warnings).map(safeText)
  return result
}

export function errorMessage(error) {
  if (error?.status === 401) return '登录状态已失效，请重新登录。'
  if (error?.status === 403) return '当前账户没有该资源的访问权限。'
  if (error?.status === 429)
    return `请求过于频繁，请${error.retryAfter ? `在 ${error.retryAfter} 秒后` : '稍后'}重试。`
  if (
    error?.status === 504 ||
    error?.code === 'TIMEOUT' ||
    /timeout|超时/i.test(error?.message || '')
  ) {
    return '请求等待超时，后台处理结果尚不确定。请先核对结果，避免重复提交动作。'
  }
  if (error?.name === 'AbortError')
    return '已停止等待；后台可能仍在处理，本次迟到响应不会写入会话。'
  return safeText(error?.message || '请求失败，请检查网络后重试。')
}
