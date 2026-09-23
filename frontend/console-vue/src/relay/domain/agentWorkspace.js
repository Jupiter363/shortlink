import { array, object, pretty, safeText, sanitize } from './agentModel.js'
import { campaignResultState, campaignStatus, displayValue } from './campaignReport.js'

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
      label:
        source.result.report || source.result.progress
          ? `当前报告 · ${campaignStatus(campaignResultState(source.result)).label}`
          : source.runState === 'SUCCESS'
            ? '当前完整结果'
            : '上一次完整结果',
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
      label:
        entry.result.report || entry.result.progress
          ? `历史报告 ${index + 1} · ${campaignStatus(campaignResultState(entry.result)).label}`
          : `历史完整结果 ${index + 1}`,
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
  if (result.report?.view) return buildCampaignReport(title, source)
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

/** Complete narrative with matching visible numbers; paged previews are explicitly labelled. */
function buildCampaignReport(title, entry) {
  const view = entry.result.report.view
  const lines = [
    `# ${text(title)}`,
    '',
    `报告版本：${view.reportRef.revision}`,
    '',
    `状态：${campaignStatus(campaignResultState(entry.result)).label}`,
    '',
    `分析问题：${text(entry.message)}`,
    '',
    `分析范围：${text(entry.scopeLabel) || '以报告证据为准'}`
  ]
  const cell = (value) => displayValue(value).replaceAll('|', '\\|').replaceAll('\n', '<br>')
  function table(columns, rows) {
    if (!columns.length) return
    lines.push(
      '',
      `| ${columns.map((column) => cell(column.label)).join(' | ')} |`,
      `| ${columns.map(() => '---').join(' | ')} |`,
      ...rows.map((row) => `| ${columns.map((column) => cell(row[column.key])).join(' | ')} |`)
    )
  }
  for (const module of view.modules) {
    lines.push('', `## ${module.title}`, '', `目标状态：${campaignStatus(module.status).label}`)
    for (const block of module.blocks) {
      lines.push('', `### ${block.title}`)
      const payload = block.payload || {}
      if (block.kind === 'METRIC')
        for (const item of array(payload.items))
          lines.push(
            '',
            `- ${item.label}：${displayValue(item.value)}${item.unit || ''}${item.note ? `（${item.note}）` : ''}`
          )
      if (block.kind === 'TABLE') {
        table(array(payload.columns), array(payload.rows))
        if (payload.nextCursor || !block.completeResult)
          lines.push('', '当前为数据预览，完整明细以本版报告的数据入口为准。')
      }
      if (block.kind === 'CHART') {
        const series = array(payload.series)
        table(
          [
            { key: 'label', label: '项目' },
            ...series.map((item, index) => ({ key: String(index), label: item.name }))
          ],
          array(payload.labels).map((label, index) => ({
            label,
            ...Object.fromEntries(
              series.map((item, seriesIndex) => [String(seriesIndex), array(item.values)[index]])
            )
          }))
        )
      }
      if (block.kind === 'RESULT_LINK')
        lines.push(
          '',
          `${payload.label || '完整数据'}：${displayValue(payload.rowCount)} 条，在线按本版报告读取。`
        )
      if (block.text) lines.push('', block.text)
    }
    if (module.limitations?.length)
      lines.push('', '分析边界：', ...module.limitations.map((item) => `- ${item}`))
  }
  return lines.join('\n') + '\n'
}
