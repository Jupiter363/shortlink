import { toAccessRecordRow } from '../../utils/agentAccessRecords.js'

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

export function buildChatBody(input) {
  if (!AGENT_TYPES.includes(input.agentType)) throw new Error('请选择有效的 Agent。')
  const sessionId = String(input.sessionId || '').trim()
  const message = String(input.message || '').trim()
  if (!sessionId) throw new Error('会话尚未准备好，请新建会话。')
  if (!message) throw new Error('请输入问题。')
  if (message.length > 2000) throw new Error('问题与分析范围合计不能超过 2000 字。')
  return { sessionId, agentType: input.agentType, message }
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
  result.cards = array(raw.cards).map((card, index) => {
    const clean = sanitize(card)
    return {
      ...object(clean),
      key: `card-${index}`,
      title: safeText(card?.title || card?.type || '分析证据'),
      rows: card?.type === 'access_records' ? array(card.rows).map(toAccessRecordRow) : []
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
