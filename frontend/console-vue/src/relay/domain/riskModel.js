import { array, object, sanitize, safeText } from './agentModel.js'

export const REVIEW_ACTIONS = [
  { value: 'WATCH', label: '关注' },
  { value: 'UNWATCH', label: '取消关注' },
  { value: 'FALSE_POSITIVE', label: '标记误报' },
  { value: 'CONFIRM_RISK', label: '确认风险' },
  { value: 'IGNORE', label: '忽略' }
]
export const numberOrNull = (value) =>
  value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
    ? null
    : Number(value)
export const metric = (value) =>
  numberOrNull(value) === null ? '—' : Number(value).toLocaleString('zh-CN')
export const riskLevel = (value) =>
  ({ LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' })[String(value).toUpperCase()] || '风险未知'
export const riskTone = (value) =>
  ({ LOW: 'success', MEDIUM: 'warning', HIGH: 'danger' })[String(value).toUpperCase()] || 'unknown'
export function formatTime(value) {
  if (value == null || value === '') return '未提供时间'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? safeText(value)
    : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

const PROFILE_STATUSES = new Set(['READY', 'NOT_EVALUATED'])
const PROFILE_LEVELS = new Set(['LOW', 'MEDIUM', 'HIGH'])
const OVERVIEW_METRICS = [
  'groupRiskScore',
  'totalShortLinksScanned',
  'highRiskCount',
  'mediumRiskCount',
  'lowRiskCount',
  'avgRiskScore',
  'maxRiskScore'
]

function profileStatus(value, scoreField, levelField) {
  const explicit = safeText(value.profileStatus).toUpperCase()
  if (PROFILE_STATUSES.has(explicit)) return explicit
  if (explicit) return 'UNKNOWN'
  return numberOrNull(value[scoreField]) !== null ||
    PROFILE_LEVELS.has(safeText(value[levelField]).toUpperCase())
    ? 'READY'
    : 'UNKNOWN'
}

function profileMetrics(value, fields, unavailable) {
  return Object.fromEntries(
    fields.map((field) => [field, unavailable ? null : numberOrNull(value[field])])
  )
}

export function normalizeCard(raw) {
  const card = object(raw)
  return {
    ...sanitize(card),
    gid: card.gid,
    domain: card.domain,
    shortUri: card.shortUri,
    fullShortUrl: safeText(
      card.fullShortUrl || [card.domain, card.shortUri].filter(Boolean).join('/')
    ),
    linkId: validLinkId(card.linkId) ? String(card.linkId) : null,
    riskScore: numberOrNull(card.riskScore),
    reasonCodes: array(card.reasonCodes).map(safeText),
    latestPolicyActions: array(card.latestPolicyActions).map(safeText),
    statsMeta: sanitize(object(card.statsMeta)),
    currentPolicy: {
      state: 'UNKNOWN',
      propagationState: 'UNKNOWN',
      ...sanitize(object(card.currentPolicy))
    },
    manualReview: sanitize(object(card.manualReview))
  }
}

export function normalizeOverview(raw) {
  const value = object(raw)
  const status = profileStatus(value, 'groupRiskScore', 'groupRiskLevel')
  const unavailable = status === 'NOT_EVALUATED'
  return {
    ...sanitize(value),
    profileStatus: status,
    ...profileMetrics(value, OVERVIEW_METRICS, unavailable),
    watchingCount: numberOrNull(value.watchingCount),
    disabledCount: numberOrNull(value.disabledCount),
    groupRiskLevel: unavailable ? 'UNKNOWN' : safeText(value.groupRiskLevel) || 'UNKNOWN',
    currentPolicyCoverage: value.currentPolicyCoverage || 'UNKNOWN',
    groupReasonCodes: unavailable ? [] : array(value.groupReasonCodes).map(safeText),
    riskTrend7d: unavailable
      ? []
      : array(value.riskTrend7d).map((point) => ({
          date: safeText(point.date),
          score: numberOrNull(point.riskScore),
          level: point.riskLevel
        })),
    topRiskShortLinks: array(value.topRiskShortLinks).map(normalizeCard),
    agentSummary: unavailable ? '' : safeText(value.agentSummary)
  }
}

export function normalizePolicies(raw) {
  const value = object(raw)
  const known = ['KNOWN_ALLOWED', 'KNOWN_RESTRICTED'].includes(value.state)
  return {
    state: known ? value.state : 'UNKNOWN',
    asOf: value.asOf,
    policyRevision: value.policyRevision,
    nextTransitionAt: value.nextTransitionAt,
    nextCursor: known && value.nextCursor ? String(value.nextCursor) : '',
    policies: known
      ? array(value.policies).map((policy) => ({
          ...sanitize(object(policy)),
          policyId: typeof policy?.policyId === 'string' ? policy.policyId : '',
          active: policy?.active === true,
          revoked: policy?.revoked === true
        }))
      : []
  }
}

export function normalizeCommand(raw, expectedId) {
  const value = object(raw)
  // A malformed or mismatched receipt cannot settle another command.
  if (!value.commandId || value.commandId !== expectedId)
    return {
      commandId: expectedId,
      state: 'UNKNOWN',
      label: '结果待核实',
      tone: 'warning',
      propagation: 'UNKNOWN',
      canQuery: true
    }
  const state = value.commandState || value.status
  if (state === 'COMMITTED')
    return {
      ...sanitize(value),
      commandId: expectedId,
      state,
      label: '停用命令已提交',
      tone: 'info',
      propagation: value.propagationState || 'UNKNOWN',
      canQuery: false
    }
  if (['CONFLICT', 'EXPIRED', 'REJECTED', 'FAILED'].includes(state))
    return {
      ...sanitize(value),
      commandId: expectedId,
      state,
      label: `命令未执行 · ${state}`,
      tone: 'danger',
      propagation: 'NOT_APPLICABLE',
      canQuery: false
    }
  return {
    ...sanitize(value),
    commandId: expectedId,
    state: 'UNKNOWN',
    label: '结果待核实',
    tone: 'warning',
    propagation: 'UNKNOWN',
    canQuery: true
  }
}

export const newCommandId = () => `relay-disable-${globalThis.crypto.randomUUID()}`
export const commandKey = (linkId, policyId) => `${linkId}:${policyId}`
export function validLinkId(value) {
  return typeof value === 'string'
    ? /^[1-9]\d*$/.test(value)
    : Number.isSafeInteger(value) && value > 0
}
export function buildDisableBody(input) {
  if (!validLinkId(input.linkId)) throw new Error('短链缺少有效 linkId，不能提交策略动作。')
  if (!/^[A-Za-z0-9:_-]{8,128}$/.test(input.commandId || '')) throw new Error('策略命令编号无效。')
  return {
    linkId: String(input.linkId),
    commandId: input.commandId,
    gid: input.gid,
    reason: String(input.reason || '').trim()
  }
}
export function buildReviewBody(input) {
  if (!REVIEW_ACTIONS.some((action) => action.value === input.reviewAction))
    throw new Error('请选择有效审核动作。')
  if (!['GROUP', 'SHORT_LINK'].includes(input.targetType) || !input.gid)
    throw new Error('审核目标不完整。')
  if (input.targetType === 'SHORT_LINK' && (!input.domain || !input.shortUri))
    throw new Error('短链审核目标不完整。')
  return {
    eventId: input.eventId || undefined,
    targetType: input.targetType,
    gid: input.gid,
    domain: input.targetType === 'SHORT_LINK' ? input.domain : undefined,
    shortUri: input.targetType === 'SHORT_LINK' ? input.shortUri : undefined,
    fullShortUrl: input.targetType === 'SHORT_LINK' ? input.fullShortUrl : undefined,
    reviewAction: input.reviewAction,
    reviewNote: String(input.reviewNote || '').trim()
  }
}
