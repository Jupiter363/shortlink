import { request } from '../api/http.js'
import { normalizeAgentResult } from './agentModel.js'

const reference = (value, maximum = 96) =>
  typeof value === 'string' &&
  value.length <= maximum &&
  /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)
const timestamp = (value) => typeof value === 'string' && Number.isFinite(Date.parse(value))
const cursorValid = (value) =>
  value == null || (typeof value === 'string' && value.length > 0 && value.length <= 8192)

/** Session discovery contains only current-owner references; bodies are read after selection. */
export async function loadCampaignSessions(cursor, signal) {
  const page = await request('/api/short-link/admin/v1/agent/campaign/sessions', {
    query: { cursor, size: 20 },
    signal,
    timeoutMs: 20000
  })
  if (
    page?.schemaVersion !== 'campaign-session-list/v1' ||
    !Array.isArray(page.entries) ||
    page.entries.length > 50 ||
    !cursorValid(page.nextCursor)
  )
    throw new Error('无法确认历史会话列表，请重新读取。')
  const seen = new Set()
  for (const entry of page.entries) {
    if (
      !reference(entry?.sessionId) ||
      !timestamp(entry.firstRequestAt) ||
      !timestamp(entry.lastRequestAt) ||
      Date.parse(entry.firstRequestAt) > Date.parse(entry.lastRequestAt) ||
      !Number.isSafeInteger(entry.requestCount) ||
      entry.requestCount < 1 ||
      seen.has(entry.sessionId)
    )
      throw new Error('历史会话的标识或时间不完整，已停止载入。')
    seen.add(entry.sessionId)
  }
  return page
}

/** Receipts are read-only references, never cached reports or authority grants. */
export async function loadCampaignRecovery({ sessionId, cursor } = {}, signal) {
  const page = await request('/api/short-link/admin/v1/agent/campaign/workspace', {
    query: { sessionId, cursor, size: 20 },
    signal,
    timeoutMs: 20000
  })
  if (
    page?.schemaVersion !== 'campaign-session-recovery/v1' ||
    (page.sessionId !== null && !reference(page.sessionId)) ||
    (sessionId && page.sessionId !== sessionId) ||
    !Array.isArray(page.entries) ||
    page.entries.length > 50 ||
    !cursorValid(page.nextCursor) ||
    (page.sessionId === null && (page.entries.length || page.nextCursor))
  )
    throw new Error('无法确认恢复记录的会话信息，请重新读取。')
  const seen = new Set()
  for (const entry of page.entries) {
    if (
      entry?.sessionId !== page.sessionId ||
      !reference(entry.requestKey, 256) ||
      typeof entry.originalQuestion !== 'string' ||
      !entry.originalQuestion.trim() ||
      !reference(entry.continuation?.runId) ||
      !reference(entry.continuation?.requestId) ||
      !timestamp(entry.createdAt) ||
      !timestamp(entry.expiresAt) ||
      seen.has(entry.continuation.requestId)
    )
      throw new Error('分析请求的恢复引用不完整，已停止载入。')
    seen.add(entry.continuation.requestId)
  }
  return page
}

export function normalizeRecoveredProgress(raw, receipt) {
  const result = normalizeAgentResult(raw)
  if (
    result.sessionId !== receipt.sessionId ||
    result.continuation?.runId !== receipt.continuation.runId ||
    result.continuation?.requestId !== receipt.continuation.requestId ||
    result.progress?.runId !== receipt.continuation.runId
  )
    throw new Error('返回进度不属于所选分析请求，已忽略。')
  return result
}

/** Only recognize our own compiled prefix; select a group only from the current authorized list. */
export function recoveredQuestion(originalQuestion, groups = []) {
  const compiled = /^分析范围：分组「([^\r\n]*)」；gid=([A-Za-z0-9_-]{1,64});\n([\s\S]*)$/.exec(
    originalQuestion
  )
  if (!compiled)
    return { prompt: originalQuestion, groupId: '', scopeLabel: '范围以原问题与当前授权为准' }
  const group = groups.find((item) => String(item.id) === compiled[2])
  return {
    prompt: compiled[3],
    groupId: group ? String(group.id) : '',
    scopeLabel: group ? `分组：${group.name}` : '原分组当前不可用，请重新选择分析范围'
  }
}
