<script setup>
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { agentApi } from '../api/agentRisk.js'
import PageHeading from '../components/PageHeading.vue'
import AgentComposer from '../components/AgentComposer.vue'
import AgentReport from '../components/AgentReport.vue'
import CampaignReport from '../components/CampaignReport.vue'
import AgentResultDetails from '../components/AgentResultDetails.vue'
import AgentAnswer from '../components/AgentAnswer.vue'
import { agentHistoryEntries, buildAgentReport } from '../domain/agentWorkspace.js'
import {
  loadCampaignRecovery,
  loadCampaignSessions,
  normalizeRecoveredProgress,
  recoveredQuestion
} from '../domain/agentRecovery.js'
import {
  CAMPAIGN_CAPABILITIES,
  campaignResultState,
  campaignStatus,
  canCancelCampaignResult,
  normalizeCampaignReport,
  reportIdentity,
  sameReport
} from '../domain/campaignReport.js'
import {
  compileMessage,
  errorMessage,
  newAgentSession,
  normalizeAgentResult,
  pretty,
  sanitize
} from '../domain/agentModel.js'
import './agent-risk.css'
import './agent-workbench.css'
import './agent-report.css'
import './campaign-report.css'

const props = defineProps({ type: { type: String, default: 'campaign-analysis' } })
const relay = inject('relay')
const COPY = {
  'campaign-analysis': {
    title: '投放分析 Agent',
    role: 'navigator',
    name: '领航员 Navigator',
    intro: '从真实统计快照中，寻找下一步投放的依据。',
    placeholder: '例如：分析最近 7 天的访问趋势，列出数据依据与仍然缺失的信息。',
    presets: [
      '分析最近 7 天访问趋势和高峰时段',
      '比较地域、设备和运营商分布',
      '说明新老访客的统计口径与数据完整度'
    ]
  },
  'security-risk': {
    title: '安全风控 Agent',
    role: 'guardian',
    name: '守护员 Guardian',
    intro: '让异常判断、访问证据与策略结果可以核验。',
    placeholder: '例如：检查最近的异常访问，解释风险原因并列出证据及不确定项。',
    presets: [
      '检查异常访问并解释风险原因',
      '分析高频来源和访问失败分布',
      '汇总风险证据与当前数据缺口'
    ]
  }
}
const agentType = computed(() => (COPY[props.type] ? props.type : 'campaign-analysis'))
const copy = computed(() => COPY[agentType.value])
const session = ref(newAgentSession(agentType.value))
const activePane = ref('compose')
const resultView = ref('answer')
const assistOpen = ref(false)
const followupOpen = ref(false)
const promptError = ref('')
const detailsOpen = ref(false)
const detailsModal = ref(null)
const dialogPromptInput = ref(null)
const historyOpen = ref(false)
const historyKey = ref('current')
const remoteHistory = ref([])
const recoveredHistory = ref([])
const recoveryLoading = ref(false)
const recoveryError = ref('')
const requestHistoryLoading = ref(false)
const requestHistoryCursor = ref(null)
const historyLoading = ref(false)
const historyError = ref('')
const historyCursor = ref(null)
const historyReading = ref(false)
const historyReadEntry = ref(null)
const historySessionId = ref('')
const historySessions = ref([])
const historySessionsLoading = ref(false)
const historySessionsCursor = ref(null)
const historyAccessDenied = ref(false)
const selectedHistorySessionId = computed(() => historySessionId.value || session.value.id)
const selectedHistorySession = computed(() =>
  historySessions.value.find((entry) => entry.sessionId === selectedHistorySessionId.value)
)
const historySessionOptions = computed(() => {
  const entries = historySessions.value.map((entry) => ({
    value: entry.sessionId,
    label: `${entry.sessionId === session.value.id ? '当前工作区 · ' : ''}${new Date(entry.lastRequestAt).toLocaleString('zh-CN')} · ${entry.requestCount} 次分析 · ${entry.sessionId.slice(-6)}`
  }))
  if (!entries.some((entry) => entry.value === session.value.id))
    entries.unshift({ value: session.value.id, label: '当前工作区' })
  return entries
})
const progressLoading = ref(false)
const progressError = ref('')
const copyFallback = ref('')
const workspaceBody = ref(null)
const outputPane = ref(null)
const promptInput = ref(null)
const resultContent = ref(null)
watch(
  () => session.value.result,
  async (value, previous) => {
    // Read-only progress updates must not pull the reader back to the top of this report.
    if (
      value?.continuation &&
      value.continuation.runId === previous?.continuation?.runId &&
      value.continuation.requestId === previous?.continuation?.requestId
    )
      return
    await nextTick()
    if (resultContent.value) resultContent.value.scrollTop = 0
    if (workspaceBody.value) workspaceBody.value.scrollTop = 0
  }
)
watch([resultView, detailsOpen], async () => {
  await nextTick()
  if (detailsOpen.value) detailsModal.value?.dialog?.scrollTo({ top: 0 })
})
watch(activePane, async () => {
  await nextTick()
  if (workspaceBody.value) workspaceBody.value.scrollTop = 0
  if (activePane.value === 'output' && session.value.runState === 'RUNNING') {
    outputPane.value?.focus({ preventScroll: true })
  }
})
watch(
  [agentType, () => relay.state.agentSessions],
  () => {
    if (!relay.state.session?.loggedIn) return
    relay.state.agentSessions ||= {}
    relay.state.agentSessions[agentType.value] ||= newAgentSession(agentType.value)
    session.value = relay.state.agentSessions[agentType.value]
    activePane.value =
      session.value.result || session.value.runState === 'ERROR' ? 'output' : 'compose'
  },
  { immediate: true }
)
const running = computed(() => session.value.runState === 'RUNNING')
const reportMode = computed(() => session.value.runState !== 'READY')
const groups = computed(() => relay.state.groups || [])
const scopeOptions = computed(() => [
  { value: '', label: '按问题描述选择授权范围' },
  ...groups.value.map((group) => ({ value: String(group.id), label: group.name }))
])
const selectedGroup = computed(() =>
  groups.value.find((group) => String(group.id) === session.value.groupId)
)
const compiled = computed(() => compileMessage(session.value.prompt, selectedGroup.value))
const result = computed(() => session.value.result)
const historyEntries = computed(() => {
  if (historyAccessDenied.value) return []
  const entries =
    agentType.value !== 'campaign-analysis' || selectedHistorySessionId.value === session.value.id
      ? agentHistoryEntries(session.value)
      : []
  if (agentType.value !== 'campaign-analysis') return entries
  const local = entries.map((entry) => {
    const state = campaignResultState(entry.result)
    const prefix =
      entry.key !== 'current'
        ? '历史结果'
        : ['RUNNING', 'ERROR'].includes(session.value.runState)
          ? '上一次结果'
          : '当前结果'
    return {
      ...entry,
      label: state === 'SUCCESS' ? entry.label : `${prefix} · ${campaignStatus(state).label}`,
      referenceResult: entry.result,
      result: entry.result?.continuation || entry.result?.report?.view ? null : entry.result
    }
  })
  const requestEntries = recoveredHistory.value.filter(
    (remote) =>
      !local.some(
        (entry) =>
          entry.referenceResult?.continuation?.runId === remote.requestRef.continuation.runId &&
          entry.referenceResult?.continuation?.requestId ===
            remote.requestRef.continuation.requestId
      )
  )
  const known = [...local, ...requestEntries]
  return [
    ...known,
    ...remoteHistory.value.filter(
      (remote) =>
        !known.some(
          (entry) =>
            (entry.referenceResult || entry.result)?.report?.view &&
            sameReport((entry.referenceResult || entry.result).report.view, remote.identity)
        )
    )
  ]
})
const historyEntry = computed(
  () =>
    (historyReadEntry.value?.key === historyKey.value && historyReadEntry.value) ||
    historyEntries.value.find((entry) => entry.key === historyKey.value) ||
    historyEntries.value[0]
)
const currentEntry = computed(() =>
  agentHistoryEntries(session.value).find((entry) => entry.key === 'current')
)
const workspaceAnswerCount = computed(() => agentHistoryEntries(session.value).length)
const sessionStatus = computed(
  () =>
    ({
      READY: '等待提问',
      RUNNING: session.value.runOperation === 'CANCEL' ? '正在停止分析' : '分析中',
      WAITING: '等待统计结果',
      INCOMPLETE: '分析尚未完成',
      PARTIAL: '分析部分完成',
      NEEDS_INPUT: '需要补充信息',
      UNKNOWN: '结果状态待核实',
      FAILED: '分析未完成',
      CANCELLED: '分析已取消',
      SUPERSEDED: '分析已有新版本',
      SUCCESS: '已返回结果',
      ERROR: '上次未完成'
    })[session.value.runState] || '状态未知'
)
const health = ref({ state: 'UNKNOWN', checkedAt: '', error: '' })
let healthController
let runController
let activeRun
let disposed = false
let progressController
let historyController
let reportController
let recoveryController
let requestHistoryController
let historySessionsController
let historySessionsGeneration = 0
let recoveryGeneration = 0
let requestHistoryGeneration = 0
let progressGeneration = 0
let historyGeneration = 0
let progressPollTimer
let progressPollStep = 0
let progressPollStopped = false
let progressRetryAfter = 0
const pageVisible = ref(document.visibilityState === 'visible')
const progressPollDelays = [2000, 5000, 10000]
const progressPollTarget = computed(() => {
  const entry = session.value
  const progress = entry.result?.progress
  const continuation = entry.result?.continuation
  // A report may be published after the step outcome becomes uncertain. Keep a bounded
  // read-only watch until its first saved revision appears; never dispatch another job here.
  const awaitingReport =
    progress?.executionStatus === 'UNKNOWN' &&
    progress?.nextAction?.kind === 'WAIT' &&
    !entry.result?.report?.view
  if (
    !sameWorkspace(entry) ||
    !pageVisible.value ||
    recoveryLoading.value ||
    relay.state.agentBusy ||
    entry.runState === 'ERROR' ||
    entry.result?.reportError ||
    !continuation?.runId ||
    !continuation.requestId ||
    (!['RUNNING', 'WAITING', 'EMPTY'].includes(progress?.executionStatus) && !awaitingReport) ||
    !['WAIT', 'CONTINUE'].includes(progress?.nextAction?.kind)
  )
    return ''
  return `${entry.id}:${continuation.runId}:${continuation.requestId}`
})

function sameWorkspace(entry, type = 'campaign-analysis') {
  return (
    !disposed &&
    relay.state.session?.loggedIn &&
    session.value === entry &&
    agentType.value === type &&
    relay.state.agentSessions[type] === entry
  )
}

function archiveResult(entry) {
  if (!entry.result) return
  entry.history.push({
    result: entry.result,
    prompt: entry.lastPrompt,
    message: entry.lastMessage,
    scopeLabel: entry.lastScopeLabel,
    completedAt: entry.completedAt
  })
}

function stopReadRequests() {
  if (recoveryLoading.value && !session.value.result) session.value.recoveryChecked = false
  stopProgressPolling()
  progressGeneration += 1
  progressController?.abort()
  stopHistoryReads()
  recoveryGeneration += 1
  recoveryController?.abort()
  historySessionsGeneration += 1
  historySessionsController?.abort()
  historySessionsLoading.value = false
  recoveryLoading.value = false
  progressLoading.value = false
}

function stopHistoryReads() {
  historyGeneration += 1
  requestHistoryGeneration += 1
  historyController?.abort()
  requestHistoryController?.abort()
  reportController?.abort()
  historyReadEntry.value = null
  requestHistoryLoading.value = false
  historyLoading.value = false
  historyReading.value = false
}

function clearHistorySelection() {
  stopHistoryReads()
  historyReadEntry.value = null
  remoteHistory.value = []
  recoveredHistory.value = []
  requestHistoryCursor.value = null
  historyCursor.value = null
  historyKey.value = 'current'
}

function sameHistory(sourceSession, sourceId) {
  return sameWorkspace(sourceSession) && selectedHistorySessionId.value === sourceId
}

function handleHistoryError(error, sourceSession, sourceId) {
  if (error?.name === 'AbortError' || !sameHistory(sourceSession, sourceId)) return
  if ([401, 403].includes(error?.status) || error?.code === 'INVALID_SESSION') {
    clearHistorySelection()
    historySessionsGeneration += 1
    historySessionsController?.abort()
    historySessionsLoading.value = false
    historySessions.value = []
    historySessionsCursor.value = null
    historyAccessDenied.value = true
    copyFallback.value = ''
  }
  historyError.value = errorMessage(error)
}

function stopProgressPolling() {
  clearTimeout(progressPollTimer)
  progressPollTimer = null
}

function scheduleProgressPoll() {
  const target = progressPollTarget.value
  if (!target || progressPollStopped || progressPollTimer || progressLoading.value) return
  if (session.value.result?.progress?.executionStatus === 'UNKNOWN' && progressPollStep >= 12)
    return
  const entry = session.value
  const delay = Math.max(
    progressPollDelays[Math.min(progressPollStep, progressPollDelays.length - 1)],
    progressRetryAfter
  )
  progressPollStep += 1
  progressRetryAfter = 0
  progressPollTimer = setTimeout(() => {
    progressPollTimer = null
    if (sameWorkspace(entry) && progressPollTarget.value === target) refreshProgress(true)
  }, delay)
}

function updatePageVisibility() {
  pageVisible.value = document.visibilityState === 'visible'
}

async function checkHealth() {
  if (health.value.state === 'CHECKING') return
  healthController?.abort()
  const controller = new AbortController()
  healthController = controller
  health.value = { ...health.value, state: 'CHECKING', error: '' }
  try {
    const response = await agentApi.health(controller.signal)
    if (disposed || controller.signal.aborted) return
    health.value = {
      state: response?.status === 'OK' ? 'REACHABLE' : 'UNKNOWN',
      checkedAt: new Date().toLocaleTimeString('zh-CN'),
      error: response?.status === 'OK' ? '' : '入口返回了未识别的健康状态。'
    }
  } catch (error) {
    if (disposed || controller.signal.aborted) return
    health.value = {
      state: 'ERROR',
      checkedAt: new Date().toLocaleTimeString('zh-CN'),
      error: errorMessage(error)
    }
  }
}

function startNewSession() {
  if (relay.state.agentBusy) return
  stopReadRequests()
  historySessionId.value = ''
  historySessions.value = []
  historySessionsCursor.value = null
  historyAccessDenied.value = false
  remoteHistory.value = []
  recoveredHistory.value = []
  recoveryError.value = ''
  requestHistoryCursor.value = null
  historyCursor.value = null
  historyError.value = ''
  progressError.value = ''
  session.value = newAgentSession(agentType.value)
  session.value.recoveryChecked = true
  relay.state.agentSessions[agentType.value] = session.value
  activePane.value = 'compose'
  resultView.value = 'answer'
  historyOpen.value = false
  historyKey.value = 'current'
  followupOpen.value = false
  detailsOpen.value = false
  promptError.value = ''
}

async function openHistory(key = 'current') {
  if (recoveryLoading.value) return
  historyKey.value = key
  historyOpen.value = true
  if (agentType.value === 'campaign-analysis') {
    const sourceSession = session.value
    const sourceId = selectedHistorySessionId.value
    await loadHistorySessions()
    if (!historyOpen.value || !sameHistory(sourceSession, sourceId)) return
    const latest = historySessions.value[0]
    if (
      sourceId === sourceSession.id &&
      !sourceSession.result &&
      !sourceSession.lastRequestKey &&
      latest &&
      !historySessions.value.some((entry) => entry.sessionId === sourceSession.id)
    ) {
      selectHistorySession(latest.sessionId)
      return
    }
    refreshSelectedHistory()
  }
}

async function loadHistorySessions(more = false) {
  if (historySessionsLoading.value || !sameWorkspace(session.value)) return
  const sourceSession = session.value
  const sourceId = selectedHistorySessionId.value
  const generation = ++historySessionsGeneration
  historySessionsController?.abort()
  const controller = new AbortController()
  historySessionsController = controller
  historySessionsLoading.value = true
  historyError.value = ''
  try {
    const page = await loadCampaignSessions(
      more ? historySessionsCursor.value : null,
      controller.signal
    )
    if (generation !== historySessionsGeneration || !sameWorkspace(sourceSession)) return
    historySessions.value = [
      ...new Map(
        (more ? [...historySessions.value, ...page.entries] : page.entries).map((entry) => [
          entry.sessionId,
          entry
        ])
      ).values()
    ]
    historySessionsCursor.value = page.nextCursor || null
    historyAccessDenied.value = false
  } catch (error) {
    if (generation === historySessionsGeneration) handleHistoryError(error, sourceSession, sourceId)
  } finally {
    if (generation === historySessionsGeneration) historySessionsLoading.value = false
  }
}

function selectHistorySession(sessionId) {
  if (!historySessionOptions.value.some((entry) => entry.value === sessionId)) return
  clearHistorySelection()
  historySessionId.value = sessionId
  historyAccessDenied.value = false
  historyError.value = ''
  refreshSelectedHistory()
}

function refreshSelectedHistory() {
  if (historyAccessDenied.value) return
  historyReadEntry.value = null
  historyError.value = ''
  loadReportHistory()
  loadRequestHistory()
}

function closeHistory() {
  historyOpen.value = false
  stopHistoryReads()
  historySessionsGeneration += 1
  historySessionsController?.abort()
  historySessionsLoading.value = false
}

function requestHistoryEntry(receipt) {
  const question = recoveredQuestion(receipt.originalQuestion, groups.value)
  return {
    key: `request-${receipt.continuation.requestId}`,
    remote: true,
    requestRef: receipt,
    label: '已保存分析请求',
    message: question.prompt,
    scopeLabel: question.scopeLabel,
    createdAt: new Date(receipt.createdAt).toLocaleString('zh-CN'),
    completedAt: '',
    result: null
  }
}

function applyRequestHistory(page, more = false) {
  const entries = page.entries.map(requestHistoryEntry)
  const combined = more ? [...recoveredHistory.value, ...entries] : entries
  recoveredHistory.value = [
    ...new Map(
      combined.map((entry) => [
        entry.key,
        {
          ...entry,
          result:
            recoveredHistory.value.find((old) => old.key === entry.key)?.result || entry.result
        }
      ])
    ).values()
  ]
  requestHistoryCursor.value = page.nextCursor || null
}

async function loadRequestHistory(more = false) {
  if (
    requestHistoryLoading.value ||
    recoveryLoading.value ||
    agentType.value !== 'campaign-analysis'
  )
    return
  const sourceSession = session.value
  const sourceId = selectedHistorySessionId.value
  const generation = ++requestHistoryGeneration
  requestHistoryController?.abort()
  const controller = new AbortController()
  requestHistoryController = controller
  requestHistoryLoading.value = true
  try {
    const page = await loadCampaignRecovery(
      {
        sessionId: sourceId,
        cursor: more ? requestHistoryCursor.value : null
      },
      controller.signal
    )
    if (generation !== requestHistoryGeneration || !sameHistory(sourceSession, sourceId)) return
    applyRequestHistory(page, more)
  } catch (error) {
    if (generation === requestHistoryGeneration) handleHistoryError(error, sourceSession, sourceId)
  } finally {
    if (generation === requestHistoryGeneration) requestHistoryLoading.value = false
  }
}

async function restoreWorkspace() {
  const entry = session.value
  if (!sameWorkspace(entry) || recoveryLoading.value || relay.state.agentBusy || entry.result)
    return
  const generation = ++recoveryGeneration
  const owner = { username: relay.state.session.username, token: relay.state.session.token }
  recoveryController?.abort()
  const controller = new AbortController()
  recoveryController = controller
  recoveryLoading.value = true
  recoveryError.value = ''
  entry.recoveryChecked = true
  let workspaceFound = false
  const isCurrent = () =>
    generation === recoveryGeneration &&
    !controller.signal.aborted &&
    sameWorkspace(entry) &&
    owner.username === relay.state.session.username &&
    owner.token === relay.state.session.token
  try {
    const page = await loadCampaignRecovery({}, controller.signal)
    if (!isCurrent()) return
    workspaceFound = true
    const latest = page.entries[0]
    if (!latest) return
    const raw = await agentApi.progress(
      { sessionId: page.sessionId, ...latest.continuation },
      controller.signal
    )
    if (!isCurrent()) return
    const response = normalizeRecoveredProgress(raw, latest)
    const question = recoveredQuestion(latest.originalQuestion, groups.value)
    entry.id = page.sessionId
    entry.prompt = question.prompt
    entry.groupId = question.groupId
    entry.lastMessage = latest.originalQuestion
    entry.lastPrompt = question.prompt
    entry.lastRequestKey = latest.requestKey
    entry.lastGroupId = question.groupId
    entry.lastScopeLabel = question.scopeLabel
    entry.result = response
    entry.runState = campaignResultState(response)
    entry.error = ''
    historySessionId.value = page.sessionId
    applyRequestHistory(page)
    activePane.value = 'output'
    loadReportHistory()
  } catch (error) {
    if (isCurrent() && error?.name !== 'AbortError') {
      // A legacy deployment may not expose recovery; its original Agent entry remains usable.
      if (workspaceFound || error?.status !== 404) recoveryError.value = errorMessage(error)
    }
  } finally {
    if (generation === recoveryGeneration) recoveryLoading.value = false
  }
}

async function loadReportHistory(more = false) {
  if (historyLoading.value) return
  const sourceSession = session.value
  const sourceId = selectedHistorySessionId.value
  const generation = ++historyGeneration
  historyController?.abort()
  historyController = new AbortController()
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await agentApi.reportHistory(
      { sessionId: sourceId, cursor: more ? historyCursor.value : null },
      historyController.signal
    )
    if (generation !== historyGeneration || !sameHistory(sourceSession, sourceId)) return
    if (!Array.isArray(page?.items)) throw new Error('历史报告列表格式无法确认。')
    const entries = sanitize(page.items).map((item) => {
      if (item.sessionId !== sourceId || !item.reportRef) throw new Error('历史报告会话不一致。')
      return {
        key: `report-${item.reportRef.reportId}-${item.reportRef.revision}`,
        remote: true,
        identity: item,
        label: `已保存报告 · 第 ${item.reportRef.revision} 版`,
        message: String(item.title || '投放分析'),
        scopeLabel: '范围以此版报告证据为准',
        completedAt: '',
        result: null
      }
    })
    const combined = more ? [...remoteHistory.value, ...entries] : entries
    remoteHistory.value = [
      ...new Map(
        combined.map((entry) => [
          entry.key,
          {
            ...entry,
            result: remoteHistory.value.find((old) => old.key === entry.key)?.result || entry.result
          }
        ])
      ).values()
    ]
    historyCursor.value = page.nextCursor || null
  } catch (error) {
    if (generation === historyGeneration) handleHistoryError(error, sourceSession, sourceId)
  } finally {
    if (generation === historyGeneration) historyLoading.value = false
  }
}

async function selectHistory(entry) {
  historyKey.value = entry.key
  historyReadEntry.value = null
  reportController?.abort()
  historyReading.value = false
  if (agentType.value !== 'campaign-analysis') return
  const sourceSession = session.value
  const sourceId = selectedHistorySessionId.value
  const sourceResult = entry.referenceResult || entry.result
  const receipt =
    entry.requestRef ||
    (sourceResult?.continuation && {
      sessionId: sourceId,
      continuation: sourceResult.continuation
    })
  const identity = entry.identity || sourceResult?.report?.view
  if (!receipt && !identity) return
  const controller = new AbortController()
  reportController = controller
  historyReading.value = true
  historyError.value = ''
  const reading = { ...entry, result: null, sessionId: sourceId }
  historyReadEntry.value = reading
  try {
    if (receipt) {
      if (receipt.sessionId !== sourceId) throw new Error('分析请求的会话不一致。')
      const raw = await agentApi.progress(
        { sessionId: receipt.sessionId, ...receipt.continuation },
        controller.signal
      )
      if (controller.signal.aborted || !sameHistory(sourceSession, sourceId)) return
      const response = normalizeRecoveredProgress(raw, receipt)
      historyReadEntry.value = {
        ...reading,
        result: response,
        label: `已保存分析 · ${campaignStatus(campaignResultState(response)).label}`
      }
      return
    }
    const raw = await agentApi.report(
      { ...reportIdentity(identity), sessionId: sourceId },
      controller.signal
    )
    if (controller.signal.aborted || !sameHistory(sourceSession, sourceId)) return
    const view = normalizeCampaignReport(sanitize(raw))
    if (!sameReport(view, identity)) throw new Error('报告返回了不同版本，已停止载入。')
    const response = normalizeAgentResult({
      answer: '',
      cards: [],
      warnings: [],
      toolCalls: [],
      traceEvents: [],
      dataSources: [],
      pendingActions: [],
      report: {
        view,
        executionStatus: 'UNKNOWN',
        availability: 'PARTIAL',
        goalAssessments: view.goalAssessments,
        limitations: view.limitations
      }
    })
    historyReadEntry.value = { ...reading, result: response }
  } catch (error) {
    if (!controller.signal.aborted) handleHistoryError(error, sourceSession, sourceId)
  } finally {
    if (reportController === controller) historyReading.value = false
  }
}

async function refreshProgress(automatic = false) {
  if (progressLoading.value || relay.state.agentBusy || !session.value.result?.continuation) return
  const entry = session.value
  if (!sameWorkspace(entry)) return
  stopProgressPolling()
  if (!automatic) progressPollStopped = false
  const continuation = { ...entry.result.continuation }
  const generation = ++progressGeneration
  progressController?.abort()
  progressController = new AbortController()
  progressLoading.value = true
  progressError.value = ''
  try {
    const raw = await agentApi.progress(
      { sessionId: entry.id, ...continuation },
      progressController.signal
    )
    if (
      !sameWorkspace(entry) ||
      generation !== progressGeneration ||
      session.value !== entry ||
      agentType.value !== 'campaign-analysis'
    )
      return
    const response = normalizeAgentResult(raw)
    if (
      response.continuation?.runId !== continuation.runId ||
      response.continuation?.requestId !== continuation.requestId ||
      response.progress?.runId !== continuation.runId
    )
      throw new Error('返回进度不属于当前分析，已忽略。')
    if (
      response.report?.view &&
      (response.report.view.runId !== response.progress.runId ||
        response.report.view.planId !== response.progress.planId ||
        response.report.view.planRevision !== response.progress.planRevision)
    )
      throw new Error('报告与进度版本不一致，未替换当前内容。')
    // A new report revision is still the same analysis; saved versions remain in server history.
    entry.result = response
    entry.runState = campaignResultState(response)
    entry.completedAt = new Date().toLocaleString('zh-CN')
  } catch (error) {
    if (generation === progressGeneration && error?.name !== 'AbortError') {
      progressError.value = errorMessage(error)
      // Permanent access/identity errors need an explicit refresh; only transient reads retry.
      progressPollStopped = !(
        error?.name === 'ApiError' &&
        (['NETWORK_ERROR', 'TIMEOUT'].includes(error.code) ||
          error.status === 429 ||
          error.status >= 500)
      )
      progressRetryAfter = Number.isFinite(error?.retryAfter) ? error.retryAfter * 1000 : 0
    }
  } finally {
    if (generation === progressGeneration) {
      progressLoading.value = false
      scheduleProgressPoll()
    }
  }
}

async function focusPrompt() {
  if (reportMode.value) followupOpen.value = true
  await nextTick()
  // RModal opens its native dialog after the post-render watcher settles.
  if (reportMode.value) await nextTick()
  if (reportMode.value) dialogPromptInput.value?.focus()
  else promptInput.value?.focus()
}

function openDetails(view = 'evidence') {
  resultView.value = view
  detailsOpen.value = true
}

async function clearPrompt() {
  if (relay.state.agentBusy) return
  promptError.value = ''
  session.value.prompt = ''
  if (session.value.runState !== 'ERROR') session.value.error = ''
  await focusPrompt()
}

async function reusePrompt() {
  if (relay.state.agentBusy || !session.value.lastPrompt) return
  promptError.value = ''
  session.value.prompt = session.value.lastPrompt
  const previousGroup = session.value.lastGroupId || ''
  const available =
    !previousGroup || groups.value.some((group) => String(group.id) === previousGroup)
  session.value.groupId = available ? previousGroup : ''
  if (!available) relay.notify('上次的分组已不可用，请重新选择分析范围。', 'warning')
  activePane.value = reportMode.value ? 'output' : 'compose'
  await focusPrompt()
}

function onPromptKeydown(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault()
    run()
  }
}

async function copyAnswer(entry = currentEntry.value) {
  if (!entry?.result) return
  const content = entry.result.report?.view
    ? buildAgentReport(copy.value.title, entry)
    : entry.result.answer
  if (!content) return
  const sourceSession = session.value
  const sourceType = agentType.value
  const isCurrent = () => sameWorkspace(sourceSession, sourceType)
  try {
    await navigator.clipboard.writeText(content)
    if (isCurrent()) relay.notify('已复制分析回答', 'success')
  } catch {
    if (isCurrent()) copyFallback.value = content
  }
}

async function exportReport(entry = currentEntry.value) {
  if (!entry) return
  let url
  let anchor
  try {
    let report = buildAgentReport(copy.value.title, entry)
    let fileName = `${agentType.value}-${new Date().toISOString().slice(0, 10)}.md`
    if (entry.result?.report?.view) {
      const sourceSession = session.value
      const sourceId = entry.sessionId || sourceSession.id
      const response = await agentApi.reportExport({
        ...reportIdentity(entry.result.report.view),
        sessionId: sourceId
      })
      if (
        !sameWorkspace(sourceSession) ||
        (entry.sessionId &&
          (!sameHistory(sourceSession, sourceId) || historyReadEntry.value !== entry))
      )
        return
      if (
        response?.format !== 'markdown' ||
        typeof response.content !== 'string' ||
        !sameReport(entry.result.report.view, response.view)
      )
        throw new Error('导出内容与所选报告版本不一致。')
      report = response.content
      if (typeof response.fileName === 'string')
        fileName = response.fileName.replace(/[\\/\r\n]/g, '_').slice(0, 180)
    }
    url = URL.createObjectURL(new Blob([report], { type: 'text/markdown;charset=utf-8' }))
    anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    relay.notify('分析报告已导出', 'success')
  } catch (error) {
    if (entry.sessionId) handleHistoryError(error, session.value, entry.sessionId)
    relay.notify(errorMessage(error), 'warning')
  } finally {
    anchor?.remove()
    if (url) setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}

async function choosePreset(preset) {
  if (running.value || relay.state.agentBusy || recoveryLoading.value) return
  promptError.value = ''
  session.value.prompt = preset
  assistOpen.value = false
  activePane.value = reportMode.value ? 'output' : 'compose'
  await focusPrompt()
}

async function run(operation = 'NEW') {
  if (relay.state.agentBusy || running.value || recoveryLoading.value) return
  const entry = session.value
  const cancelling = operation === 'CANCEL'
  const continuing = operation === 'CONTINUE' || cancelling
  if (cancelling && !canCancelCampaignResult(entry.result)) return
  if (continuing && (!entry.result?.continuation || (!cancelling && !entry.lastRequestKey))) return
  if (!continuing && (!compiled.value || compiled.value.length > 2000)) {
    promptError.value = !compiled.value ? '请输入问题。' : '问题与分析范围合计不能超过 2000 字。'
    return
  }
  if (!continuing && entry.groupId && !selectedGroup.value) {
    promptError.value = '所选分组已不可用，请重新选择分析范围。'
    return
  }
  promptError.value = ''
  stopReadRequests()
  recoveryError.value = ''
  entry.recoveryChecked = true
  progressError.value = ''
  const type = agentType.value
  const id = crypto.randomUUID()
  const originalSessionId = entry.id
  const registeredContinuation = entry.result?.continuation
  const message = continuing ? entry.lastMessage : compiled.value
  const requestKey = continuing ? entry.lastRequestKey : id
  const continuation = continuing ? entry.result.continuation : null
  const previousRunId = continuing
    ? null
    : entry.result?.continuation?.runId || entry.result?.progress?.runId
  const submittedPrompt = continuing ? entry.lastPrompt : entry.prompt
  const submittedGroupId = continuing ? entry.lastGroupId : entry.groupId
  const submittedScopeLabel = continuing
    ? entry.lastScopeLabel
    : selectedGroup.value
      ? `分组：${selectedGroup.value.name}`
      : '按问题描述选择授权范围'
  const controller = new AbortController()
  activeRun = { id, entry }
  runController = controller
  entry.runId = id
  entry.runOperation = cancelling ? 'CANCEL' : continuing ? 'CONTINUE' : 'NEW'
  entry.runState = 'RUNNING'
  followupOpen.value = false
  detailsOpen.value = false
  activePane.value = 'output'
  resultView.value = 'answer'
  entry.error = ''
  relay.state.agentBusy = true
  try {
    const raw = await agentApi.chat(
      {
        sessionId: originalSessionId,
        agentType: type,
        ...(cancelling ? {} : { message }),
        ...(type === 'campaign-analysis'
          ? {
              requestKey,
              clientCapabilities: CAMPAIGN_CAPABILITIES,
              operation: cancelling ? 'CANCEL' : continuing ? 'CONTINUE' : 'NEW',
              ...(continuation ? { continuation } : {}),
              ...(previousRunId ? { previousRunId } : {})
            }
          : {})
      },
      controller.signal
    )
    if (
      disposed ||
      controller.signal.aborted ||
      activeRun?.id !== id ||
      entry.runId !== id ||
      agentType.value !== type ||
      relay.state.agentSessions[type] !== entry
    )
      return
    const response = normalizeAgentResult(raw)
    // Natural-language continuation/progress can enter as NEW and resolve to the original request.
    // Only the returned server identity determines whether this is a new analysis.
    const sameRegisteredRequest =
      type === 'campaign-analysis' &&
      response.sessionId === originalSessionId &&
      registeredContinuation?.runId &&
      registeredContinuation?.requestId &&
      response.continuation?.runId === registeredContinuation.runId &&
      response.continuation?.requestId === registeredContinuation.requestId
    if (continuing && !sameRegisteredRequest)
      throw new Error('返回结果不属于正在续接的分析，已保留原问题与记录。')
    if (!sameRegisteredRequest) archiveResult(entry)
    entry.result = response
    if (!sameRegisteredRequest) {
      entry.lastMessage = message
      entry.lastPrompt = submittedPrompt
      entry.lastRequestKey = requestKey
      entry.lastGroupId = submittedGroupId
      entry.lastScopeLabel = submittedScopeLabel
    }
    entry.completedAt = new Date().toLocaleString('zh-CN')
    if (typeof response.sessionId === 'string' && response.sessionId) entry.id = response.sessionId
    entry.runState = type === 'campaign-analysis' ? campaignResultState(response) : 'SUCCESS'
  } catch (error) {
    if (disposed || activeRun?.id !== id || entry.runId !== id) return
    entry.error = errorMessage(error)
    entry.runState = 'ERROR'
  } finally {
    if (activeRun?.id === id) {
      relay.state.agentBusy = false
      activeRun = null
      runController = null
    }
  }
}

function cancelWaiting() {
  if (!activeRun) return
  const entry = activeRun.entry
  entry.runId = null
  entry.runState = 'ERROR'
  entry.error = '已停止等待；后台可能仍在处理，本次迟到响应不会写入会话。请核对结果后再发起请求。'
  activeRun = null
  runController?.abort()
  relay.state.agentBusy = false
}

watch(
  () => props.type,
  () => {
    if (activeRun) cancelWaiting()
    stopReadRequests()
    historySessionId.value = ''
    historySessions.value = []
    historySessionsCursor.value = null
    historyAccessDenied.value = false
    remoteHistory.value = []
    recoveredHistory.value = []
    recoveryError.value = ''
    requestHistoryCursor.value = null
    historyCursor.value = null
    historyError.value = ''
    progressError.value = ''
    historyOpen.value = false
    followupOpen.value = false
    detailsOpen.value = false
    assistOpen.value = false
    copyFallback.value = ''
    promptError.value = ''
  }
)
watch(
  [session, progressPollTarget],
  () => {
    stopProgressPolling()
    progressGeneration += 1
    progressController?.abort()
    progressLoading.value = false
    progressPollStep = 0
    progressPollStopped = false
    progressRetryAfter = 0
    scheduleProgressPoll()
  },
  { immediate: true }
)
watch(
  [
    session,
    () => relay.state.session?.loggedIn,
    () => relay.state.session?.username,
    () => relay.state.session?.token
  ],
  ([entry, loggedIn, username, token], previous) => {
    if (
      previous &&
      (loggedIn !== previous[1] || username !== previous[2] || token !== previous[3])
    ) {
      stopReadRequests()
      historyOpen.value = false
      historySessionId.value = ''
      historySessions.value = []
      historySessionsCursor.value = null
      historyAccessDenied.value = false
      copyFallback.value = ''
      remoteHistory.value = []
      recoveredHistory.value = []
      recoveryError.value = ''
      historyError.value = ''
      historyCursor.value = null
      requestHistoryCursor.value = null
      if (!loggedIn) session.value = newAgentSession(agentType.value)
    }
    if (!loggedIn || !sameWorkspace(entry) || entry.recoveryChecked || entry.result) return
    if (entry.prompt || entry.groupId || entry.runState !== 'READY') {
      entry.recoveryChecked = true
      return
    }
    restoreWorkspace()
  },
  { immediate: true }
)
onMounted(() => {
  checkHealth()
  updatePageVisibility()
  document.addEventListener('visibilitychange', updatePageVisibility)
})
onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('visibilitychange', updatePageVisibility)
  stopReadRequests()
  healthController?.abort()
  cancelWaiting()
})
</script>

<template>
  <section
    class="ar-view ar-agent-view operation-page"
    :class="{ 'ap-report-mode': reportMode }"
    :aria-labelledby="`agent-heading-${agentType}`"
  >
    <PageHeading class="ar-page-head">
      <div>
        <p class="ar-kicker">
          JUPITER RELAY / {{ agentType === 'security-risk' ? '守护台' : '领航台' }}
        </p>
        <h1 :id="`agent-heading-${agentType}`">{{ copy.title }}</h1>
        <p>{{ copy.intro }}</p>
      </div>
    </PageHeading>
    <div class="aw-toolbar">
      <div class="aw-toolbar-state">
        <span :data-state="session.runState" class="aw-state-dot" aria-hidden="true" /><strong>{{
          recoveryLoading ? '正在恢复最近分析' : sessionStatus
        }}</strong
        ><span v-if="!reportMode">{{ selectedGroup?.name || '范围由问题指定' }}</span>
      </div>
      <div class="aw-toolbar-actions">
        <RButton kind="secondary" :disabled="relay.state.agentBusy" @click="startNewSession"
          ><RIcon name="pencil" :size="16" />新会话</RButton
        >
        <RButton kind="secondary" :disabled="recoveryLoading" @click="openHistory()"
          ><RIcon name="clock" :size="16" />历史分析<span class="aw-count">{{
            workspaceAnswerCount
          }}</span></RButton
        >
        <RButton kind="text" @click="assistOpen = true">使用说明</RButton>
      </div>
    </div>
    <div v-if="recoveryError" class="cr-progress" role="alert">
      <div>
        <strong>暂时无法恢复之前的分析</strong>
        <p>{{ recoveryError }}</p>
      </div>
      <RButton
        kind="secondary"
        :disabled="recoveryLoading || relay.state.agentBusy"
        @click="restoreWorkspace"
        >重新读取</RButton
      >
    </div>
    <div
      v-if="!reportMode"
      class="ar-pane-switch view-switch"
      role="group"
      aria-label="Agent 工作区视图"
    >
      <RButton
        kind="secondary"
        :aria-pressed="activePane === 'compose'"
        aria-controls="agent-composer"
        @click="activePane = 'compose'"
        >提问设置</RButton
      >
      <RButton
        kind="secondary"
        :aria-pressed="activePane === 'output'"
        aria-controls="agent-output"
        @click="activePane = 'output'"
        >分析结果</RButton
      >
    </div>
    <div
      ref="workspaceBody"
      class="ar-agent-body operation-body"
      role="region"
      aria-label="Agent 工作区"
      tabindex="0"
    >
      <div class="ar-agent-layout">
        <aside
          v-if="!reportMode && !recoveryLoading"
          id="agent-composer"
          class="ar-panel ar-composer"
          :class="{ 'ar-pane-active': activePane === 'compose' }"
          aria-label="提问设置"
          tabindex="0"
        >
          <AgentComposer
            ref="promptInput"
            :session="session"
            :copy="copy"
            :agent-type="agentType"
            :scope-options="scopeOptions"
            :running="running"
            :busy="relay.state.agentBusy || recoveryLoading"
            :compiled-length="compiled.length"
            :answer-count="workspaceAnswerCount"
            :input-error="promptError"
            @update:prompt="session.prompt = $event"
            @update:groupId="session.groupId = $event"
            @clear="clearPrompt"
            @reuse="reusePrompt"
            @submit="run"
            @stop="cancelWaiting"
            @preset="choosePreset"
            @keydown="onPromptKeydown"
          />
        </aside>
        <section
          id="agent-output"
          ref="outputPane"
          class="ar-output"
          :class="{
            'ar-pane-active': activePane === 'output',
            'ar-output-intro': !result || running
          }"
          aria-label="分析结果"
          tabindex="0"
          aria-live="polite"
          :aria-busy="running || recoveryLoading"
        >
          <div
            ref="resultContent"
            class="ar-result-content"
            role="region"
            tabindex="0"
            aria-label="当前分析内容"
          >
            <section v-if="recoveryLoading" class="ar-panel ar-empty" role="status">
              <RRobot :role="copy.role" expression="waiting" :size="64" />
              <h2>正在找回最近的分析</h2>
              <p>读取已保存的问题与当前进度，分析会保留原来的会话。</p>
            </section>
            <section
              v-else-if="session.runState === 'READY'"
              class="ar-panel ar-empty ar-suggestions"
            >
              <div class="ar-suggestion-intro">
                <RRobot :role="copy.role" :size="64" />
                <div>
                  <h2>先选一个分析方向</h2>
                  <p>选择示例填入问题，确认范围后开始分析。</p>
                </div>
              </div>
              <div class="ar-prompt-options">
                <button
                  v-for="(preset, index) in copy.presets"
                  :key="preset"
                  type="button"
                  @click="choosePreset(preset)"
                >
                  <span class="ar-prompt-icon"
                    ><RIcon
                      :name="
                        index === 0
                          ? agentType === 'security-risk'
                            ? 'shield'
                            : 'chart'
                          : index === 1
                            ? 'globe'
                            : 'database'
                      "
                      :size="24"
                  /></span>
                  <span>{{ preset }}</span>
                  <RIcon name="arrow-right" :size="18" />
                </button>
              </div>
              <p class="ar-caption">也可以直接输入问题。证据不完整时，回答会保留缺口与不确定项。</p>
            </section>
            <section v-else-if="running" class="ar-panel ar-empty" role="status">
              <RRobot :role="copy.role" expression="waiting" :size="96" /><RBadge tone="info"
                >RUNNING</RBadge
              >
              <h2>
                {{
                  session.runOperation === 'CANCEL' ? '正在停止本次分析' : '正在等待完整分析结果'
                }}
              </h2>
              <p v-if="session.runOperation === 'CANCEL'">
                正在确认停止状态；已提交的统计任务可能仍在后台完成。
              </p>
              <p v-else>
                请求期间暂不能切换 Agent 或新建会话；工具与 Graph 轨迹将在响应完成后展示。
              </p>
              <RButton kind="secondary" @click="cancelWaiting">停止等待</RButton>
            </section>
            <section
              v-else-if="session.runState === 'ERROR'"
              class="ar-panel ar-empty"
              :class="{ 'ar-result-error': result }"
              role="alert"
            >
              <RRobot v-if="!result" :role="copy.role" expression="recovery" :size="96" />
              <h2>本次分析未收到完整结果</h2>
              <p>{{ session.error }}</p>
              <RButton
                v-if="agentType === 'security-risk'"
                kind="secondary"
                @click="relay.go('/home/risk-center')"
                >先到风险中心核验</RButton
              >
              <RButton kind="secondary" :disabled="relay.state.agentBusy" @click="focusPrompt"
                >调整问题</RButton
              >
              <p class="ar-caption">问题已保留，确认范围后再重新提交。</p>
            </section>
            <CampaignReport
              v-if="
                result &&
                !running &&
                agentType === 'campaign-analysis' &&
                (result.report?.view || result.progress || result.reportError)
              "
              :result="result"
              :entry="currentEntry"
              :session-id="session.id"
              :busy="relay.state.agentBusy"
              :refreshing="progressLoading"
              :refresh-error="progressError"
              @followup="focusPrompt"
              @copy="copyAnswer()"
              @export="exportReport()"
              @details="openDetails"
              @refresh="refreshProgress"
              @continue="run('CONTINUE')"
              @cancel="run('CANCEL')"
            />
            <AgentReport
              v-else-if="result && !running"
              :result="result"
              :entry="currentEntry"
              :agent-type="agentType"
              :busy="relay.state.agentBusy"
              @followup="focusPrompt"
              @copy="copyAnswer()"
              @export="exportReport()"
              @details="openDetails"
            />
          </div>
        </section>
      </div>
    </div>
    <RModal
      :open="followupOpen"
      title="继续分析"
      :description="copy.title"
      :width="660"
      @close="followupOpen = false"
    >
      <div class="ar-composer ap-followup-composer">
        <AgentComposer
          ref="dialogPromptInput"
          :session="session"
          :copy="copy"
          :agent-type="agentType"
          :scope-options="scopeOptions"
          :running="running"
          :busy="relay.state.agentBusy || recoveryLoading"
          :compiled-length="compiled.length"
          :answer-count="workspaceAnswerCount"
          :input-error="promptError"
          @update:prompt="session.prompt = $event"
          @update:groupId="session.groupId = $event"
          @clear="clearPrompt"
          @reuse="reusePrompt"
          @submit="run"
          @stop="cancelWaiting"
          @preset="choosePreset"
          @keydown="onPromptKeydown"
        />
      </div>
    </RModal>
    <RModal
      ref="detailsModal"
      :open="detailsOpen"
      title="分析依据与执行记录"
      :description="currentEntry?.scopeLabel || copy.title"
      :width="1080"
      drawer
      @close="detailsOpen = false"
    >
      <div class="ap-detail-tabs" role="group" aria-label="分析详情内容">
        <RButton
          v-for="item in [
            { value: 'evidence', label: '证据与动作' },
            { value: 'sources', label: '来源说明' },
            { value: 'trace', label: '执行轨迹' }
          ]"
          :key="item.value"
          kind="secondary"
          :aria-pressed="resultView === item.value"
          @click="resultView = item.value"
          >{{ item.label }}</RButton
        >
      </div>
      <AgentResultDetails
        v-if="result"
        :result="result"
        :view="resultView"
        @navigate-risk="relay.go('/home/risk-center')"
      />
    </RModal>
    <RModal
      :open="assistOpen"
      title="会话与使用说明"
      :description="copy.title"
      :width="680"
      @close="assistOpen = false"
    >
      <div class="aw-assistance-content">
        <div v-if="session.runState !== 'READY'" class="ar-presets">
          <RButton
            v-for="preset in copy.presets"
            :key="preset"
            kind="text"
            :disabled="running"
            @click="choosePreset(preset)"
            >{{ preset }}</RButton
          >
        </div>
        <p class="ar-caption">
          范围会写入问题文本；服务端按当前登录账户校验数据权限。两个 Agent
          的会话独立保留，含范围最多 2000 字。
        </p>
        <p class="ar-caption">
          单次请求最多等待 60
          秒；投放分析可通过“刷新进度”读取后台结果，通过“推进本次分析”继续原任务。
        </p>
        <p v-if="agentType === 'security-risk'" class="ar-caption">
          安全 Agent 可能依据服务端策略自动限流；人工审核和现有策略管理在风险中心进行。
        </p>
        <div class="ar-actions">
          <RBadge
            :tone="
              health.state === 'REACHABLE'
                ? 'success'
                : health.state === 'ERROR'
                  ? 'danger'
                  : 'unknown'
            "
            >{{
              {
                REACHABLE: 'HTTP 入口可达',
                CHECKING: '检查入口中',
                ERROR: '入口检查失败',
                UNKNOWN: '入口状态未知'
              }[health.state]
            }}</RBadge
          ><RButton
            kind="text"
            :disabled="health.state === 'CHECKING' || running"
            @click="checkHealth"
            >检查入口</RButton
          >
        </div>
        <p class="ar-caption">
          {{ health.checkedAt ? `${health.checkedAt} 检查 · ` : '' }}入口状态只表示 HTTP
          可达性，不表示 Graph、工具或模型分别就绪。{{ health.error }}
        </p>
        <p class="ar-caption">当前会话</p>
        <code class="ar-break">{{ session.id }}</code>

        <p v-if="agentType === 'campaign-analysis'" class="ar-caption">
          页面临时记录只保留在当前会话。投放分析可在历史分析中切换已保存会话，每次查看和导出均读取当前有权访问的报告版本。
        </p>
        <p v-else class="ar-caption">页面临时记录只保留在当前会话，刷新或退出登录后不保留。</p>
        <p class="ar-caption">“停止等待”只结束前端等待，后台可能仍在处理，不会自动重试。</p>
      </div>
    </RModal>
    <RModal
      :open="historyOpen"
      title="历史分析"
      :description="
        copy.title +
        (agentType === 'campaign-analysis' ? ' · 已保存会话与分析记录' : ' · 当前会话的分析记录')
      "
      :width="1000"
      drawer
      @close="closeHistory"
    >
      <div v-if="agentType === 'campaign-analysis'" class="aw-history-session-picker">
        <label for="campaign-history-session">查看会话</label>
        <select
          id="campaign-history-session"
          :value="selectedHistorySessionId"
          :disabled="historySessionsLoading || historyAccessDenied"
          @change="selectHistorySession($event.target.value)"
        >
          <option v-for="entry in historySessionOptions" :key="entry.value" :value="entry.value">
            {{ entry.label }}
          </option>
        </select>
        <RButton
          v-if="historySessionsCursor"
          kind="text"
          :disabled="historySessionsLoading"
          @click="loadHistorySessions(true)"
          >更早会话</RButton
        >
        <p v-if="selectedHistorySession">
          首次提问 {{ new Date(selectedHistorySession.firstRequestAt).toLocaleString('zh-CN') }} ·
          共 {{ selectedHistorySession.requestCount }} 次分析
        </p>
        <p v-else>从最近会话中选择，再查看原问题与分析结果。</p>
      </div>
      <div v-if="agentType === 'campaign-analysis'" class="cr-history-toolbar">
        <p v-if="historySessionsLoading || historyLoading || requestHistoryLoading" role="status">
          正在读取已保存分析…
        </p>
        <p v-else-if="historyError" class="cr-inline-error" role="alert">{{ historyError }}</p>
        <p v-else>分析请求与已保存报告一起列出，选择后读取结果。</p>
        <RButton
          kind="text"
          :disabled="historySessionsLoading || historyLoading || requestHistoryLoading"
          @click="openHistory(historyKey)"
          >刷新列表</RButton
        >
        <RButton
          v-if="requestHistoryCursor"
          kind="secondary"
          :disabled="requestHistoryLoading"
          @click="loadRequestHistory(true)"
          >更多分析</RButton
        >
        <RButton
          v-if="historyCursor"
          kind="secondary"
          :disabled="historyLoading"
          @click="loadReportHistory(true)"
          >更多报告</RButton
        >
      </div>
      <div v-if="historyEntries.length" class="aw-history-layout">
        <nav class="aw-history-list" aria-label="选择历史分析">
          <button
            v-for="entry in historyEntries"
            :key="entry.key"
            type="button"
            :aria-pressed="historyEntry?.key === entry.key"
            @click="selectHistory(entry)"
          >
            <span>{{ entry.label }}</span
            ><strong>{{ entry.message || '未保留问题文本' }}</strong
            ><time>{{
              entry.completedAt ||
              (entry.createdAt ? `提交于 ${entry.createdAt}` : '未提供完成时间')
            }}</time>
          </button>
        </nav>
        <article v-if="historyEntry" class="aw-history-detail">
          <p v-if="historyReading" role="status">正在读取所选版本…</p>
          <CampaignReport
            v-if="
              historyEntry.result &&
              (historyEntry.result.report?.view || historyEntry.result.progress)
            "
            :key="historyEntry.key"
            :result="historyEntry.result"
            :entry="historyEntry"
            :session-id="selectedHistorySessionId"
            historical
            @copy="copyAnswer(historyEntry)"
            @export="exportReport(historyEntry)"
          />
          <template v-else-if="historyEntry.result">
            <header>
              <div>
                <h3>{{ historyEntry.label }}</h3>
                <p>{{ historyEntry.completedAt }}</p>
              </div>
              <div class="aw-history-actions">
                <RButton
                  kind="secondary"
                  :disabled="!historyEntry.result.answer"
                  @click="copyAnswer(historyEntry)"
                  >复制回答</RButton
                ><RButton kind="secondary" @click="exportReport(historyEntry)">导出报告</RButton>
              </div>
            </header>
            <p class="ar-caption">
              {{ historyEntry.scopeLabel || '未单独记录分析范围，请核对原始问题。' }}
            </p>
            <p class="aw-history-question">{{ historyEntry.message || '未保留问题文本' }}</p>
            <AgentAnswer :text="historyEntry.result.answer || '本次响应没有回答正文。'" />
            <p
              v-for="(warning, index) in historyEntry.result.warnings"
              :key="index"
              class="ar-alert ar-alert-warning"
            >
              {{ warning }}
            </p>
            <details class="ar-details">
              <summary>查看证据与执行记录</summary>
              <pre class="ar-json">{{ pretty(historyEntry.result) }}</pre>
            </details>
          </template>
          <RButton
            v-else-if="!historyReading"
            kind="secondary"
            @click="selectHistory(historyEntry)"
            >{{ historyEntry.requestRef ? '读取此项分析' : '读取此版报告' }}</RButton
          >
        </article>
      </div>
      <div v-else class="aw-history-empty">
        <RIcon name="clock" :size="36" />
        <h3>{{ historyAccessDenied ? '此会话当前不可读取' : '当前所选会话还没有分析记录' }}</h3>
        <p>
          {{
            historyAccessDenied
              ? '请重新确认登录与访问权限，再刷新列表。'
              : agentType === 'campaign-analysis'
                ? '选择已保存会话，可以复查原问题、回答与证据。'
                : '收到分析结果后，可以在这里复查回答与证据。'
          }}
        </p>
        <p class="ar-caption">
          {{
            agentType === 'campaign-analysis'
              ? '已登记的分析会从服务端重新读取，内容以当前授权与保留期限为准。'
              : '记录不会跨新会话、页面刷新或退出登录保留。'
          }}
        </p>
        <RButton kind="secondary" @click="closeHistory">返回工作区</RButton>
      </div>
    </RModal>
    <RModal
      :open="Boolean(copyFallback)"
      title="复制分析回答"
      :width="720"
      @close="copyFallback = ''"
    >
      <p class="ar-caption">浏览器未允许自动复制，可选中文本后手动复制。</p>
      <textarea
        class="aw-copy-fallback"
        aria-label="待复制的分析回答"
        :value="copyFallback"
        readonly
        rows="10"
      />
    </RModal>
  </section>
</template>

<style scoped>
.aw-history-session-picker {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px 12px;
  align-items: center;
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--canvas);
}
.aw-history-session-picker label {
  color: var(--text);
  font-size: 13px;
  font-weight: 700;
}
.aw-history-session-picker select {
  width: 100%;
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  color: var(--text);
  background: var(--surface);
  font: inherit;
  font-size: 13px;
}
.aw-history-session-picker select:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}
.aw-history-session-picker p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}
@media (max-width: 600px) {
  .aw-history-session-picker {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .aw-history-session-picker label {
    grid-column: 1 / -1;
  }
}
</style>
