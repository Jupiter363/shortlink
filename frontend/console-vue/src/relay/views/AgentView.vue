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
const historyLoading = ref(false)
const historyError = ref('')
const historyCursor = ref(null)
const historyReading = ref(false)
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
  const entries = agentHistoryEntries(session.value)
  if (agentType.value !== 'campaign-analysis') return entries
  const local = entries.map((entry) => {
    const state = campaignResultState(entry.result)
    if (state === 'SUCCESS') return entry
    const prefix =
      entry.key !== 'current'
        ? '历史结果'
        : ['RUNNING', 'ERROR'].includes(session.value.runState)
          ? '上一次结果'
          : '当前结果'
    return {
      ...entry,
      label: `${prefix} · ${campaignStatus(state).label}`
    }
  })
  return [
    ...local,
    ...remoteHistory.value.filter(
      (remote) =>
        !local.some(
          (entry) =>
            entry.result?.report?.view && sameReport(entry.result.report.view, remote.identity)
        )
    )
  ]
})
const historyEntry = computed(
  () =>
    historyEntries.value.find((entry) => entry.key === historyKey.value) || historyEntries.value[0]
)
const currentEntry = computed(() => historyEntries.value.find((entry) => entry.key === 'current'))
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
  if (
    !sameWorkspace(entry) ||
    !pageVisible.value ||
    relay.state.agentBusy ||
    entry.runState === 'ERROR' ||
    entry.result?.reportError ||
    !continuation?.runId ||
    !continuation.requestId ||
    !['RUNNING', 'WAITING', 'EMPTY'].includes(progress?.executionStatus) ||
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
  stopProgressPolling()
  progressGeneration += 1
  historyGeneration += 1
  progressController?.abort()
  historyController?.abort()
  reportController?.abort()
  progressLoading.value = false
  historyLoading.value = false
  historyReading.value = false
}

function stopProgressPolling() {
  clearTimeout(progressPollTimer)
  progressPollTimer = null
}

function scheduleProgressPoll() {
  const target = progressPollTarget.value
  if (!target || progressPollStopped || progressPollTimer || progressLoading.value) return
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
  remoteHistory.value = []
  historyCursor.value = null
  historyError.value = ''
  progressError.value = ''
  session.value = newAgentSession(agentType.value)
  relay.state.agentSessions[agentType.value] = session.value
  activePane.value = 'compose'
  resultView.value = 'answer'
  historyOpen.value = false
  historyKey.value = 'current'
  followupOpen.value = false
  detailsOpen.value = false
  promptError.value = ''
}

function openHistory(key = 'current') {
  historyKey.value = key
  historyOpen.value = true
  if (agentType.value === 'campaign-analysis') loadReportHistory()
}

async function loadReportHistory(more = false) {
  if (historyLoading.value) return
  const sourceSession = session.value
  const sourceId = sourceSession.id
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
    if (generation !== historyGeneration || !sameWorkspace(sourceSession)) return
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
    if (generation === historyGeneration && error?.name !== 'AbortError')
      historyError.value = errorMessage(error)
  } finally {
    if (generation === historyGeneration) historyLoading.value = false
  }
}

async function selectHistory(entry) {
  historyKey.value = entry.key
  if (!entry.remote || entry.result) return
  const sourceSession = session.value
  reportController?.abort()
  const controller = new AbortController()
  reportController = controller
  historyReading.value = true
  historyError.value = ''
  try {
    const raw = await agentApi.report(
      { ...reportIdentity(entry.identity), sessionId: sourceSession.id },
      controller.signal
    )
    if (controller.signal.aborted || !sameWorkspace(sourceSession)) return
    const view = normalizeCampaignReport(sanitize(raw))
    if (!sameReport(view, entry.identity)) throw new Error('报告返回了不同版本，已停止载入。')
    const target = remoteHistory.value.find(
      (item) => item.key === entry.key && sameReport(item.identity, view)
    )
    if (!target) return
    target.result = normalizeAgentResult({
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
  } catch (error) {
    if (!controller.signal.aborted && session.value === sourceSession)
      historyError.value = errorMessage(error)
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
    if (entry.result.report?.view && !sameReport(entry.result.report.view, response.report?.view))
      archiveResult(entry)
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
      const response = await agentApi.reportExport({
        ...reportIdentity(entry.result.report.view),
        sessionId: sourceSession.id
      })
      if (!sameWorkspace(sourceSession)) return
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
    relay.notify(errorMessage(error), 'warning')
  } finally {
    anchor?.remove()
    if (url) setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}

async function choosePreset(preset) {
  if (running.value || relay.state.agentBusy) return
  promptError.value = ''
  session.value.prompt = preset
  assistOpen.value = false
  activePane.value = reportMode.value ? 'output' : 'compose'
  await focusPrompt()
}

async function run(operation = 'NEW') {
  if (relay.state.agentBusy || running.value) return
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
  progressError.value = ''
  const type = agentType.value
  const id = crypto.randomUUID()
  const originalSessionId = entry.id
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
    if (
      !continuing ||
      (entry.result?.report?.view && !sameReport(entry.result.report.view, response.report?.view))
    )
      archiveResult(entry)
    entry.result = response
    entry.lastMessage = message
    entry.lastPrompt = submittedPrompt
    entry.lastRequestKey = requestKey
    entry.lastGroupId = submittedGroupId
    entry.lastScopeLabel = submittedScopeLabel
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
    remoteHistory.value = []
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
          sessionStatus
        }}</strong
        ><span v-if="!reportMode">{{ selectedGroup?.name || '范围由问题指定' }}</span>
      </div>
      <div class="aw-toolbar-actions">
        <RButton kind="secondary" :disabled="relay.state.agentBusy" @click="startNewSession"
          ><RIcon name="pencil" :size="16" />新会话</RButton
        >
        <RButton kind="secondary" @click="openHistory()"
          ><RIcon name="clock" :size="16" />历史分析<span class="aw-count">{{
            historyEntries.length
          }}</span></RButton
        >
        <RButton kind="text" @click="assistOpen = true">使用说明</RButton>
      </div>
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
          v-if="!reportMode"
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
            :busy="relay.state.agentBusy"
            :compiled-length="compiled.length"
            :answer-count="historyEntries.length"
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
          :aria-busy="running"
        >
          <div
            ref="resultContent"
            class="ar-result-content"
            role="region"
            tabindex="0"
            aria-label="当前分析内容"
          >
            <section v-if="session.runState === 'READY'" class="ar-panel ar-empty ar-suggestions">
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
          :busy="relay.state.agentBusy"
          :compiled-length="compiled.length"
          :answer-count="historyEntries.length"
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

        <p class="ar-caption">
          页面临时记录只保留在当前会话。投放分析中已发布的报告可从本会话的已保存报告读取，每次查看和导出均固定报告版本。
        </p>
        <p class="ar-caption">“停止等待”只结束前端等待，后台可能仍在处理，不会自动重试。</p>
      </div>
    </RModal>
    <RModal
      :open="historyOpen"
      title="历史分析"
      :description="copy.title + ' · 当前会话的分析记录'"
      :width="1000"
      drawer
      @close="historyOpen = false"
    >
      <div v-if="agentType === 'campaign-analysis'" class="cr-history-toolbar">
        <p v-if="historyLoading" role="status">正在读取已保存报告…</p>
        <p v-else-if="historyError" class="cr-inline-error" role="alert">{{ historyError }}</p>
        <p v-else>本地响应与已保存报告一起列出，选择后读取固定版本。</p>
        <RButton kind="text" :disabled="historyLoading" @click="loadReportHistory()"
          >刷新列表</RButton
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
            ><time>{{ entry.completedAt || '未提供完成时间' }}</time>
          </button>
        </nav>
        <article v-if="historyEntry" class="aw-history-detail">
          <p v-if="historyReading" role="status">正在读取所选版本…</p>
          <CampaignReport
            v-if="historyEntry.result?.report?.view"
            :key="historyEntry.key"
            :result="historyEntry.result"
            :entry="historyEntry"
            :session-id="session.id"
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
          <RButton v-else-if="!historyReading" kind="secondary" @click="selectHistory(historyEntry)"
            >读取此版报告</RButton
          >
        </article>
      </div>
      <div v-else class="aw-history-empty">
        <RIcon name="clock" :size="36" />
        <h3>当前会话还没有分析记录</h3>
        <p>收到分析结果后，可以在这里复查回答与证据。</p>
        <p class="ar-caption">记录不会跨新会话、页面刷新或退出登录保留。</p>
        <RButton kind="secondary" @click="historyOpen = false">返回工作区</RButton>
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
