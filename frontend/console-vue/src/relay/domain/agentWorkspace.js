import { array, object, pretty, safeText, sanitize } from './agentModel.js'

function freezeTree(value) {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) freezeTree(child)
    Object.freeze(value)
  }
  return value
}

const text = (value) => safeText(value).trim()
const preferredMessage = (prompt, message) => text(prompt) || text(message)
const hasResult = (value) => value && typeof value === 'object' && !Array.isArray(value)

/** Snapshots are independent of session state and cannot mutate its completed results. */
export function agentHistoryEntries(session) {
  const source = object(session)
  const entries = []
  if (hasResult(source.result)) {
    entries.push({
      key: 'current',
      label: source.runState === 'SUCCESS' ? '当前完整结果' : '上一次完整结果',
      result: sanitize(source.result),
      message: preferredMessage(source.lastPrompt, source.lastMessage),
      scopeLabel: text(source.lastScopeLabel),
      completedAt: text(source.completedAt)
    })
  }
  const history = array(source.history)
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const entry = object(history[index])
    if (!hasResult(entry.result)) continue
    entries.push({
      key: `history-${index}`,
      label: `历史完整结果 ${index + 1}`,
      result: sanitize(entry.result),
      message: preferredMessage(entry.prompt, entry.message),
      scopeLabel: text(entry.scopeLabel),
      completedAt: text(entry.completedAt)
    })
  }
  return freezeTree(entries)
}

const internalKeys = new Set(['session', 'sessionid', 'runid', 'controller', 'raw'])

function withoutInternals(value) {
  if (Array.isArray(value)) return value.map(withoutInternals)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => !internalKeys.has(key.replace(/[_-]/g, '').toLowerCase()))
      .map(([key, child]) => [key, withoutInternals(child)])
  )
}

function fencedJson(value) {
  const json = pretty(withoutInternals(sanitize(value)))
  const runs = json.match(/`+/g) || []
  const fence = '`'.repeat(Math.max(4, ...runs.map((run) => run.length + 1)))
  return `${fence}json\n${json}\n${fence}`
}

function hasContent(value) {
  if (value == null) return false
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'object') return Object.keys(value).length > 0
  return typeof value === 'string' ? value.trim().length > 0 : true
}

/** Export only human-visible report fields; session and execution internals never enter the report. */
export function buildAgentReport(title, entry) {
  const source = object(entry)
  const result = object(source.result)
  const lines = [
    `# ${text(title).replace(/[\r\n]+/g, ' ') || 'Agent 分析报告'}`,
    '',
    `> ${text(source.label) || '完整结果'}`,
    '',
    `完成时间：${text(source.completedAt) || '未提供完成时间'}`,
    '',
    `分析范围：${text(source.scopeLabel) || '未单独记录，请核对原始问题'}`,
    '',
    '## 分析问题',
    '',
    text(source.message) || '未提供原始问题。',
    '',
    '## 分析结论',
    '',
    typeof result.answer === 'string' && text(result.answer)
      ? text(result.answer)
      : '未提供分析正文。'
  ]
  const sections = [
    ['cards', '分析证据'],
    ['warnings', '提示'],
    ['dataSources', '数据来源'],
    ['toolCalls', '工具调用'],
    ['traceEvents', '执行轨迹'],
    ['pendingActions', '待处理事项']
  ]
  for (const [field, label] of sections) {
    const value = result[field]
    lines.push('', `## ${label}`, '', hasContent(value) ? fencedJson(value) : `未提供${label}。`)
  }
  return lines.join('\n') + '\n'
}
