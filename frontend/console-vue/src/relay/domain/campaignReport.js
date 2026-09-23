export const CAMPAIGN_CAPABILITIES = ['campaign-response/v2']
export const CAMPAIGN_BLOCK_KINDS = [
  'METRIC',
  'CHART',
  'TABLE',
  'ANALYSIS',
  'LIMITATION',
  'RECOMMENDATION',
  'RESULT_LINK'
]
const record = (value) => value && typeof value === 'object' && !Array.isArray(value)
const reference = (value) =>
  typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]*$/.test(value)
const revision = (value) => Number.isSafeInteger(value) && value > 0
const list = (value) => (Array.isArray(value) ? value : [])
const text = (value) => typeof value === 'string' && value.trim().length > 0
const count = (value) =>
  (Number.isSafeInteger(value) && value >= 0) ||
  (typeof value === 'string' && /^(0|[1-9]\d*)$/.test(value))
const number = (value) =>
  (typeof value === 'number' && Number.isFinite(value)) ||
  (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value))

function validPayload(block) {
  const payload = block.payload
  if (!record(payload)) return false
  switch (block.kind) {
    case 'METRIC':
      return (
        Array.isArray(payload.items) &&
        payload.items.length > 0 &&
        payload.items.every(
          (item) =>
            record(item) &&
            text(item.label) &&
            (number(item.value) || text(item.value)) &&
            typeof item.unit === 'string' &&
            (item.note == null || typeof item.note === 'string')
        )
      )
    case 'CHART':
      return (
        ['BAR', 'LINE'].includes(payload.chartType) &&
        Array.isArray(payload.labels) &&
        payload.labels.length > 0 &&
        payload.labels.every((label) => typeof label === 'string' || number(label)) &&
        typeof payload.unit === 'string' &&
        Array.isArray(payload.series) &&
        payload.series.length > 0 &&
        payload.series.every(
          (series) =>
            record(series) &&
            text(series.name) &&
            Array.isArray(series.values) &&
            series.values.length === payload.labels.length &&
            series.values.every((value) => value == null || number(value))
        )
      )
    case 'TABLE':
      return (
        Array.isArray(payload.columns) &&
        payload.columns.every(
          (column) => record(column) && text(column.key) && text(column.label)
        ) &&
        new Set(payload.columns.map((column) => column.key)).size === payload.columns.length &&
        Array.isArray(payload.rows) &&
        payload.rows.every(record) &&
        (!payload.rows.length || payload.columns.length > 0) &&
        (payload.nextCursor == null || text(payload.nextCursor)) &&
        count(payload.totalRows)
      )
    case 'RESULT_LINK':
      return (
        reference(payload.artifactId) &&
        block.evidenceArtifactIds.includes(payload.artifactId) &&
        text(payload.label) &&
        count(payload.rowCount) &&
        block.completeResult === true
      )
    case 'ANALYSIS':
    case 'LIMITATION':
    case 'RECOMMENDATION':
      return text(block.text)
    default:
      return false
  }
}

export function normalizeCampaignReport(view) {
  if (
    !record(view) ||
    view.schemaVersion !== 'campaign-report-view/v2' ||
    !reference(view.runId) ||
    !reference(view.planId) ||
    !revision(view.planRevision) ||
    !reference(view.reportRef?.reportId) ||
    !revision(view.reportRef?.revision) ||
    !Array.isArray(view.modules) ||
    !record(view.blocksById)
  ) {
    throw new Error('报告结构或版本无法确认，请重新读取本次报告。')
  }
  const goals = new Set()
  const used = new Set()
  const blocksById = {}
  for (const [id, block] of Object.entries(view.blocksById)) {
    if (
      !reference(id) ||
      !record(block) ||
      block.blockId !== id ||
      !CAMPAIGN_BLOCK_KINDS.includes(block.kind) ||
      !text(block.title) ||
      typeof block.completeResult !== 'boolean' ||
      (block.text != null && typeof block.text !== 'string') ||
      !Array.isArray(block.evidenceArtifactIds) ||
      !block.evidenceArtifactIds.every(reference) ||
      !validPayload(block)
    ) {
      throw new Error('报告内容类型、数据结构或标识无法确认，未将本次报告标记为完成。')
    }
    blocksById[id] = {
      ...block,
      title: String(block.title || '分析内容'),
      text: typeof block.text === 'string' ? block.text : '',
      payload: record(block.payload) ? block.payload : {},
      evidenceArtifactIds: list(block.evidenceArtifactIds)
    }
  }
  const modules = view.modules
    .map((module) => {
      if (
        !record(module) ||
        !reference(module.goalId) ||
        goals.has(module.goalId) ||
        !Array.isArray(module.blockIds) ||
        module.blockIds.some((id) => !Object.hasOwn(blocksById, id)) ||
        new Set(module.blockIds).size !== module.blockIds.length
      ) {
        throw new Error('分析目标与内容关联不完整，请重新读取本次报告。')
      }
      goals.add(module.goalId)
      module.blockIds.forEach((id) => used.add(id))
      return {
        ...module,
        title: String(module.title || '分析目标'),
        limitations: list(module.limitations),
        blocks: module.blockIds.map((id) => blocksById[id])
      }
    })
    .sort(
      (a, b) => (Number.isFinite(a.order) ? a.order : 0) - (Number.isFinite(b.order) ? b.order : 0)
    )
  if (Object.keys(blocksById).some((id) => !used.has(id))) {
    throw new Error('报告包含未关联目标的内容，请重新读取完整报告。')
  }
  return { ...view, modules, blocksById }
}

export function reportIdentity(view) {
  return {
    reportId: view.reportRef.reportId,
    revision: view.reportRef.revision,
    runId: view.runId,
    planId: view.planId,
    planRevision: view.planRevision
  }
}

export function sameReport(left, right) {
  if (!left?.reportRef || !right?.reportRef) return false
  const a = reportIdentity(left),
    b = reportIdentity(right)
  return Object.keys(a).every((key) => a[key] === b[key])
}

export function campaignResultState(result) {
  const report = result?.report
  const action = result?.progress?.nextAction?.kind
  if (action === 'NEEDS_INPUT') return 'NEEDS_INPUT'
  const execution = result?.progress?.executionStatus || report?.executionStatus
  if (['FAILED', 'CANCELLED', 'SUPERSEDED'].includes(execution)) return execution
  if (['RUNNING', 'WAITING', 'EMPTY'].includes(execution)) return 'WAITING'
  if (execution === 'UNKNOWN') return 'UNKNOWN'
  if (report) {
    if (result.reportError) return 'UNKNOWN'
    if (report.availability === 'PARTIAL') return 'PARTIAL'
    if (
      report.availability === 'COMPLETE' &&
      execution === 'SUCCEEDED' &&
      list(report.goalAssessments).length > 0 &&
      list(report.goalAssessments).every((goal) => goal.status === 'ANSWERED')
    )
      return 'SUCCESS'
    return 'UNKNOWN'
  }
  if (result?.progress) return 'UNKNOWN'
  const cards = list(result?.cards).filter((card) =>
    ['comparison', 'ranking', 'dimension_breakdown'].includes(card?.type)
  )
  if (cards.some((card) => card.status === 'PENDING')) return 'WAITING'
  if (cards.some((card) => card.status === 'INCOMPLETE')) return 'INCOMPLETE'
  return 'SUCCESS'
}

export function campaignStatus(value) {
  const status = {
    ANSWERED: ['目标已回答', 'success'],
    SUCCEEDED: ['已完成', 'success'],
    SUCCESS: ['已完成', 'success'],
    COMPLETE: ['完整报告', 'success'],
    PARTIAL: ['部分完成', 'warning'],
    INCOMPLETE: ['尚未完成', 'warning'],
    NEEDS_INPUT: ['需要补充信息', 'warning'],
    WAITING: ['等待结果', 'info'],
    RUNNING: ['正在分析', 'info'],
    PENDING: ['等待执行', 'info'],
    EMPTY: ['等待执行', 'info'],
    BLOCKED: ['暂时受阻', 'warning'],
    UNANSWERED: ['尚未回答', 'warning'],
    UNAVAILABLE: ['暂不可用', 'warning'],
    UNSUPPORTED: ['暂不支持', 'warning'],
    FAILED: ['执行失败', 'danger'],
    CANCELLED: ['已取消', 'unknown'],
    SUPERSEDED: ['已有新版本', 'unknown'],
    EXECUTED: ['执行结束，结论待核验', 'info'],
    UNKNOWN: ['状态待核实', 'unknown']
  }[value] || ['状态待核实', 'unknown']
  return { label: status[0], tone: status[1] }
}

export function canCancelCampaignResult(result) {
  const progress = result?.progress
  return Boolean(
    result?.continuation?.requestId &&
    result.continuation.runId === progress?.runId &&
    reference(progress?.planId) &&
    revision(progress?.planRevision) &&
    ['RUNNING', 'WAITING', 'UNKNOWN', 'BLOCKED', 'FAILED'].includes(progress.executionStatus)
  )
}

/** Pair only adjacent data/text blocks; never move an explanation away from its report order. */
export function reportRows(blocks) {
  const visual = new Set(['METRIC', 'CHART', 'TABLE', 'RESULT_LINK'])
  const narrative = new Set(['ANALYSIS', 'LIMITATION', 'RECOMMENDATION'])
  const rows = []
  for (let index = 0; index < blocks.length; index += 1) {
    const block = blocks[index],
      next = blocks[index + 1]
    if (
      next &&
      ((visual.has(block.kind) && narrative.has(next.kind)) ||
        (narrative.has(block.kind) && visual.has(next.kind)))
    ) {
      rows.push({
        key: block.blockId,
        blocks: [block, next],
        paired: true,
        textFirst: narrative.has(block.kind)
      })
      index += 1
    } else rows.push({ key: block.blockId, blocks: [block], paired: false })
  }
  return rows
}

export function displayValue(value) {
  if (value == null || value === '') return '—'
  if (typeof value === 'number')
    return Number.isFinite(value)
      ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value)
      : '—'
  if (typeof value === 'string' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}
