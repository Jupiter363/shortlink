<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { riskApi } from '../api/agentRisk.js'
import { absoluteShortUrl } from '../api/product.js'
import { array, errorMessage, object, pretty, sanitize } from '../domain/agentModel.js'
import {
  commandKey,
  formatTime,
  metric,
  newCommandId,
  normalizeCard,
  normalizeCommand,
  normalizeOverview,
  normalizePolicies,
  REVIEW_ACTIONS,
  riskLevel,
  riskTone
} from '../domain/riskModel.js'
import './agent-risk.css'

const relay = inject('relay')
relay.state.riskCommands ||= {}
const groupId = ref(String(relay.state.groupId || relay.state.groups?.[0]?.id || ''))
const groups = computed(() => relay.state.groups || [])
const groupOptions = computed(() =>
  groups.value.map((group) => ({ value: String(group.id), label: group.name }))
)
const groupName = computed(
  () => groups.value.find((group) => String(group.id) === groupId.value)?.name || '所选分组'
)
const displayShortUrl = (value) =>
  absoluteShortUrl(value) || (typeof value === 'string' ? value : '')
const overviewRiskLabel = (profile) =>
  profile.profileStatus === 'NOT_EVALUATED' ? '尚未生成风险画像' : riskLevel(profile.groupRiskLevel)
const overviewRiskTone = (profile) =>
  profile.profileStatus === 'NOT_EVALUATED' ? 'unknown' : riskTone(profile.groupRiskLevel)
const overview = ref(null)
const cards = ref([])
const loading = ref(false)
const errors = ref({})
const events = ref({ records: [], total: 0, pageNo: 1, pageSize: 10 })
const eventsLoading = ref(false)
const detailOpen = ref(false)
const selected = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const policies = ref(normalizePolicies(null))
const policiesLoading = ref(false)
const policiesError = ref('')
const review = ref({
  open: false,
  target: null,
  action: 'WATCH',
  note: '',
  busy: false,
  result: null,
  error: ''
})
const policyDialog = ref({ open: false, policy: null, card: null, reason: '', error: '' })
const mutationBusy = computed(
  () =>
    review.value.busy ||
    Object.values(relay.state.riskCommands).some((command) => command.submitting)
)
let groupController
let detailController
let eventController
let groupVersion = 0
let eventVersion = 0
let detailVersion = 0
let disposed = false

function currentCommand(policy, card = selected.value) {
  return card?.linkId && policy?.policyId
    ? relay.state.riskCommands[commandKey(card.linkId, policy.policyId)]
    : null
}
const dialogCommand = computed(() =>
  currentCommand(policyDialog.value.policy, policyDialog.value.card)
)
const loadedCommands = computed(() =>
  Object.values(relay.state.riskCommands).filter((command) => command.gid === groupId.value)
)

async function loadGroup() {
  groupController?.abort()
  const version = ++groupVersion
  const gid = groupId.value
  overview.value = null
  cards.value = []
  errors.value = {}
  if (!gid) {
    loading.value = false
    return
  }
  loading.value = true
  const controller = new AbortController()
  groupController = controller
  const responses = await Promise.allSettled([
    riskApi.overview(gid, controller.signal),
    riskApi.cards(gid, controller.signal)
  ])
  if (disposed || version !== groupVersion || controller.signal.aborted) return
  if (responses[0].status === 'fulfilled') overview.value = normalizeOverview(responses[0].value)
  else errors.value.overview = errorMessage(responses[0].reason)
  if (responses[1].status === 'fulfilled')
    cards.value = array(responses[1].value).map(normalizeCard)
  else errors.value.cards = errorMessage(responses[1].reason)
  loading.value = false
}

async function loadEvents(pageNo = 1) {
  eventController?.abort()
  const version = ++eventVersion
  const gid = groupId.value
  events.value = { records: [], total: 0, pageNo, pageSize: 10 }
  errors.value.events = ''
  if (!gid) {
    eventsLoading.value = false
    return
  }
  eventsLoading.value = true
  const controller = new AbortController()
  eventController = controller
  try {
    const response = await riskApi.events({ gid, pageNo }, controller.signal)
    if (disposed || version !== eventVersion || controller.signal.aborted) return
    events.value = {
      records: array(response?.records).map(sanitizeEvent),
      total: Number(response?.total) || 0,
      pageNo: Number(response?.pageNo) || pageNo,
      pageSize: Number(response?.pageSize) || 10
    }
  } catch (error) {
    if (!disposed && version === eventVersion && !controller.signal.aborted)
      errors.value.events = errorMessage(error)
  } finally {
    if (version === eventVersion) eventsLoading.value = false
  }
}

function sanitizeEvent(event) {
  return {
    ...sanitize(event),
    gid: event.gid,
    domain: event.domain,
    shortUri: event.shortUri,
    eventId: event.eventId,
    reasonCodes: array(event.reasonCodes)
  }
}

async function refreshPolicies(append = false) {
  const card = selected.value
  if (!card?.linkId || policiesLoading.value) return
  const version = detailVersion
  const cursor = append ? policies.value.nextCursor : ''
  policiesLoading.value = true
  policiesError.value = ''
  try {
    const response = normalizePolicies(
      await riskApi.policies(card.linkId, cursor, detailController?.signal)
    )
    if (disposed || version !== detailVersion) return
    const previous = policies.value
    if (
      append &&
      response.state !== 'UNKNOWN' &&
      previous.policyRevision === response.policyRevision
    ) {
      const distinct = new Map(
        [...previous.policies, ...response.policies].map((policy) => [policy.policyId, policy])
      )
      response.policies = [...distinct.values()]
    } else if (append && response.state !== 'UNKNOWN') {
      policiesError.value = '分页期间策略版本发生变化，请刷新当前策略后重新读取。'
      policies.value = normalizePolicies(null)
      return
    }
    policies.value = response
  } catch (error) {
    if (!disposed && version === detailVersion) {
      policies.value = normalizePolicies(null)
      policiesError.value = errorMessage(error)
    }
  } finally {
    if (version === detailVersion) policiesLoading.value = false
  }
}

async function openDetail(card) {
  detailController?.abort()
  const version = ++detailVersion
  selected.value = card
  detailOpen.value = true
  detail.value = null
  detailError.value = ''
  policies.value = normalizePolicies(null)
  policiesError.value = ''
  detailLoading.value = true
  policiesLoading.value = false
  detailController = new AbortController()
  const policyTask = refreshPolicies()
  try {
    const response = await riskApi.detail(card, detailController.signal)
    if (disposed || version !== detailVersion) return
    detail.value = {
      card: response?.card ? normalizeCard(response.card) : card,
      metrics: sanitize(object(response?.metrics)),
      latestSnapshot: sanitize(object(response?.latestSnapshot)),
      recentEvents: array(response?.recentEvents).map(sanitizeEvent)
    }
  } catch (error) {
    if (!disposed && version === detailVersion) detailError.value = errorMessage(error)
  } finally {
    if (version === detailVersion) detailLoading.value = false
  }
  await policyTask
}

function closeDetail() {
  if (mutationBusy.value) return
  detailOpen.value = false
  detailController?.abort()
  detailVersion++
  policiesLoading.value = false
}

function openReview(target) {
  review.value = {
    open: true,
    target: { ...target },
    action: 'WATCH',
    note: '',
    busy: false,
    result: null,
    error: ''
  }
}
function closeReview() {
  if (!review.value.busy) review.value.open = false
}
async function submitReview() {
  if (review.value.busy || review.value.result) return
  const entry = review.value
  entry.busy = true
  entry.error = ''
  try {
    const response = await riskApi.review({
      ...entry.target,
      reviewAction: entry.action,
      reviewNote: entry.note
    })
    if (disposed || review.value !== entry) return
    if (!response?.reviewId)
      throw new Error('审核响应缺少记录编号，请刷新核验；本页不会自动重复提交。')
    entry.result = sanitize(response)
    loadGroup()
    loadEvents(events.value.pageNo)
  } catch (error) {
    if (!disposed && review.value === entry) entry.error = errorMessage(error)
  } finally {
    entry.busy = false
  }
}

function openPolicy(policy) {
  policyDialog.value = { open: true, policy, card: { ...selected.value }, reason: '', error: '' }
}
function closePolicy() {
  if (!dialogCommand.value?.submitting) policyDialog.value.open = false
}
async function disablePolicy() {
  const dialog = policyDialog.value
  const card = dialog.card
  const policy = dialog.policy
  if (!card?.linkId || !policy?.policyId || currentCommand(policy, card)) return
  if (policies.value.state === 'UNKNOWN' || !policy.active || policy.revoked) {
    dialog.error = '策略状态未知或已非激活状态，请先刷新核验。'
    return
  }
  const id = newCommandId()
  const key = commandKey(card.linkId, policy.policyId)
  const store = relay.state.riskCommands
  store[key] = {
    commandId: id,
    policyId: policy.policyId,
    linkId: card.linkId,
    gid: String(card.gid),
    state: 'SUBMITTING',
    label: '停用请求提交中',
    propagation: 'UNKNOWN',
    tone: 'info',
    submitting: true,
    canQuery: false,
    querying: false
  }
  try {
    const response = await riskApi.disable(policy.policyId, {
      gid: card.gid,
      linkId: card.linkId,
      commandId: id,
      reason: dialog.reason
    })
    Object.assign(store[key], normalizeCommand(response, id))
  } catch (error) {
    // Transport errors and a successful envelope with an indeterminate receipt both retain the original ID.
    Object.assign(store[key], normalizeCommand(null, id), { error: errorMessage(error) })
  } finally {
    store[key].submitting = false
  }
  if (!disposed && selected.value?.linkId === card.linkId && detailOpen.value) refreshPolicies()
}

async function queryCommand(command) {
  if (command.submitting || command.querying) return
  command.querying = true
  command.error = ''
  try {
    Object.assign(
      command,
      normalizeCommand(await riskApi.command(command.commandId), command.commandId)
    )
  } catch (error) {
    Object.assign(command, normalizeCommand(null, command.commandId), {
      error: errorMessage(error)
    })
  } finally {
    command.querying = false
  }
  if (!disposed && detailOpen.value && selected.value?.linkId === command.linkId) refreshPolicies()
}

function cardTitle(card) {
  return (
    relay.state.links?.find((link) => String(link.id) === String(card.linkId))?.title ||
    displayShortUrl(card.fullShortUrl) ||
    card.shortUri ||
    '短链风险档案'
  )
}
watch(
  groupId,
  () => {
    relay.state.groupId = groupId.value
    closeDetail()
    loadGroup()
    loadEvents(1)
  },
  { immediate: true }
)
watch(groups, () => {
  if (!groupId.value && groups.value.length) groupId.value = String(groups.value[0].id)
})
onBeforeUnmount(() => {
  disposed = true
  groupController?.abort()
  detailController?.abort()
  eventController?.abort()
  groupVersion++
  detailVersion++
  eventVersion++
})
</script>

<template>
  <section class="ar-view" aria-labelledby="risk-heading">
    <header class="ar-page-head">
      <div>
        <p class="ar-kicker">JUPITER RELAY / 风险中继台</p>
        <h1 id="risk-heading">风险中心</h1>
        <p>查看风险事实、记录人工结论，独立核验策略执行。</p>
      </div>
      <div class="ar-actions">
        <RSelect
          v-model="groupId"
          label="分组"
          :options="groupOptions"
          :disabled="mutationBusy"
        /><RButton
          kind="secondary"
          :disabled="loading || !groupId || mutationBusy"
          @click="
            () => {
              loadGroup()
              loadEvents(1)
            }
          "
          >刷新</RButton
        >
      </div>
    </header>
    <div v-if="!groupId" class="ar-panel ar-empty">
      <RRobot role="guardian" :size="136" />
      <h2>先创建一个短链分组</h2>
      <p>风险档案会在真实访问与异步统计链路产出数据后出现。</p>
      <RButton @click="relay.go('/home/space')">前往短链空间</RButton>
    </div>
    <template v-else>
      <p class="ar-notice">
        人工审核不会自动撤销策略。自动限流、审核记录和现有策略停用是三条独立链路；UNKNOWN
        表示待核实。
      </p>
      <p v-if="errors.overview" class="ar-alert ar-alert-danger" role="alert">
        概览：{{ errors.overview }}
      </p>
      <div v-if="loading" class="ar-panel ar-loading" role="status">
        正在读取风险概览和短链档案…
      </div>
      <template v-if="overview">
        <section class="ar-risk-summary ar-panel">
          <div class="ar-risk-hero">
            <RRobot role="guardian" :size="120" />
            <div>
              <RBadge :tone="overviewRiskTone(overview)">{{ overviewRiskLabel(overview) }}</RBadge>
              <h2>{{ groupName }}</h2>
              <p>
                风险分数 <strong class="ar-score">{{ metric(overview.groupRiskScore) }}</strong>
              </p>
            </div>
          </div>
          <div class="ar-metrics">
            <div>
              <span>已扫描短链</span><strong>{{ metric(overview.totalShortLinksScanned) }}</strong>
            </div>
            <div>
              <span>高风险</span><strong>{{ metric(overview.highRiskCount) }}</strong>
            </div>
            <div>
              <span>中风险</span><strong>{{ metric(overview.mediumRiskCount) }}</strong>
            </div>
            <div>
              <span>低风险</span><strong>{{ metric(overview.lowRiskCount) }}</strong>
            </div>
            <div>
              <span>正在关注</span><strong>{{ metric(overview.watchingCount) }}</strong>
            </div>
            <div>
              <span>全组策略停用数</span><strong>{{ metric(overview.disabledCount) }}</strong>
            </div>
          </div>
          <p class="ar-caption">
            当前策略覆盖：{{
              overview.currentPolicyCoverage === 'TOP_CARDS_ONLY'
                ? '仅头部卡片；不能推断全分组停用总数'
                : overview.currentPolicyCoverage
            }}。卡片策略为观测快照，操作前请读取当前策略。
          </p>
          <div class="ar-actions">
            <span v-for="reason in overview.groupReasonCodes" :key="reason" class="ar-reason">{{
              reason
            }}</span
            ><RButton
              kind="secondary"
              :disabled="mutationBusy"
              @click="openReview({ targetType: 'GROUP', gid: groupId })"
              >记录分组审核</RButton
            >
          </div>
        </section>
        <section class="ar-panel">
          <header class="ar-section-head">
            <div>
              <h2>最近 7 天风险趋势</h2>
              <p>使用风险画像返回的日期与分数；缺失日期不补零。</p>
            </div>
          </header>
          <div
            v-if="overview.riskTrend7d.length"
            class="ar-trend"
            role="list"
            aria-label="最近七天风险分数"
          >
            <div v-for="point in overview.riskTrend7d" :key="point.date" role="listitem">
              <strong>{{ metric(point.score) }}</strong>
              <div class="ar-trend-track">
                <span
                  v-if="point.score !== null"
                  :style="{ height: Math.max(2, Math.min(100, point.score)) + '%' }"
                ></span>
              </div>
              <time>{{ point.date }}</time
              ><small>{{ riskLevel(point.level) }}</small>
            </div>
          </div>
          <p v-else class="ar-muted">
            {{
              overview.profileStatus === 'NOT_EVALUATED'
                ? '尚未生成风险画像，暂无风险趋势数据。'
                : '暂无风险趋势数据。'
            }}
          </p>
          <p v-if="overview.agentSummary" class="ar-answer-text">{{ overview.agentSummary }}</p>
        </section>
      </template>
      <section class="ar-panel">
        <header class="ar-section-head">
          <div>
            <h2>短链风险档案</h2>
            <p>按服务端风险顺序返回，最多 500 条；无档案不表示没有风险。</p>
          </div>
          <RBadge tone="info">{{ cards.length }} 条已返回</RBadge>
        </header>
        <p v-if="errors.cards" class="ar-alert ar-alert-danger" role="alert">{{ errors.cards }}</p>
        <div v-else-if="!loading && !cards.length" class="ar-empty ar-empty-compact">
          <RIcon name="shield" :size="44" />
          <h3>
            {{
              overview?.profileStatus === 'NOT_EVALUATED' ? '尚未生成风险画像' : '还没有风险档案'
            }}
          </h3>
          <p>等待真实访问与风险画像产出，或前往安全 Agent 发起分析。</p>
          <RButton kind="secondary" @click="relay.go('/home/agent/security-risk')"
            >打开安全风控 Agent</RButton
          >
        </div>
        <div class="ar-risk-grid">
          <article
            v-for="card in cards"
            :key="`${card.gid}:${card.domain}:${card.shortUri}`"
            class="ar-risk-card"
          >
            <header>
              <h3>{{ cardTitle(card) }}</h3>
              <RBadge :tone="riskTone(card.riskLevel)"
                >{{ riskLevel(card.riskLevel) }} · {{ metric(card.riskScore) }}</RBadge
              >
            </header>
            <p class="ar-break">{{ displayShortUrl(card.fullShortUrl) }}</p>
            <div class="ar-card-metrics">
              <span
                >2 小时 PV <b>{{ metric(card.pv2h) }}</b></span
              ><span
                >2 小时 UV <b>{{ metric(card.uv2h) }}</b></span
              ><span
                >7 天 PV <b>{{ metric(card.pv7d) }}</b></span
              ><span
                >7 天 UV <b>{{ metric(card.uv7d) }}</b></span
              >
            </div>
            <p>
              <RBadge tone="unknown">关注：{{ card.watchStatus || '未提供' }}</RBadge>
            </p>
            <div class="ar-reasons">
              <span v-for="reason in card.reasonCodes" :key="reason" class="ar-reason">{{
                reason
              }}</span
              ><span v-if="!card.reasonCodes.length" class="ar-muted">未提供原因码</span>
            </div>
            <p class="ar-caption">画像窗口：{{ formatTime(card.profileWindowEnd) }}</p>
            <p class="ar-caption">
              历史动作：{{ card.latestPolicyActions.join(' · ') || '未提供' }}
            </p>
            <details class="ar-details">
              <summary>数据完整度</summary>
              <pre class="ar-json">{{ pretty(card.statsMeta) }}</pre>
            </details>
            <RButton kind="secondary" :disabled="mutationBusy" @click="openDetail(card)"
              >查看证据与当前策略</RButton
            >
          </article>
        </div>
      </section>
      <section v-if="loadedCommands.length" class="ar-panel">
        <h2>本次登录的策略命令</h2>
        <p class="ar-caption">保留原 commandId；等待超时后只查询原命令，不重复发送停用动作。</p>
        <article v-for="command in loadedCommands" :key="command.commandId" class="ar-command">
          <div>
            <RBadge :tone="command.tone">{{ command.label }}</RBadge>
            <p class="ar-break">{{ command.commandId }}</p>
            <small>传播：{{ command.propagation }} · 策略 {{ command.policyId }}</small>
            <p v-if="command.error" class="ar-text-danger">{{ command.error }}</p>
          </div>
          <RButton
            v-if="command.canQuery"
            kind="secondary"
            :loading="command.querying"
            @click="queryCommand(command)"
            >查询原命令</RButton
          >
        </article>
      </section>
      <section class="ar-panel">
        <header class="ar-section-head">
          <div>
            <h2>风险事件</h2>
            <p>事件证据与当时的建议，不等同于当前策略。</p>
          </div>
        </header>
        <p v-if="errors.events" class="ar-alert ar-alert-danger" role="alert">
          {{ errors.events }}
        </p>
        <p v-if="eventsLoading" role="status">正在读取风险事件…</p>
        <div v-else class="ar-table-scroll">
          <table>
            <thead>
              <tr>
                <th>时间 / 目标</th>
                <th>风险</th>
                <th>原因与证据</th>
                <th>人工审核</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in events.records" :key="event.eventId">
                <td>
                  {{ formatTime(event.eventTime)
                  }}<small>{{ displayShortUrl(event.fullShortUrl) || '分组事件' }}</small>
                </td>
                <td>
                  <RBadge :tone="riskTone(event.riskLevel)"
                    >{{ riskLevel(event.riskLevel) }} · {{ metric(event.riskScore) }}</RBadge
                  >
                </td>
                <td>
                  <span v-for="reason in event.reasonCodes" :key="reason" class="ar-reason">{{
                    reason
                  }}</span>
                  <details class="ar-details">
                    <summary>脱敏事件证据</summary>
                    <pre class="ar-json">{{ pretty(event) }}</pre>
                  </details>
                </td>
                <td>
                  <RButton kind="text" :disabled="mutationBusy" @click="openReview(event)"
                    >记录审核</RButton
                  >
                </td>
              </tr>
              <tr v-if="!events.records.length && !errors.events">
                <td colspan="4">暂无风险事件。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="ar-pagination">
          <span>共 {{ metric(events.total) }} 条 · 第 {{ events.pageNo }} 页</span
          ><RButton
            kind="text"
            :disabled="eventsLoading || events.pageNo <= 1"
            @click="loadEvents(events.pageNo - 1)"
            >上一页</RButton
          ><RButton
            kind="text"
            :disabled="eventsLoading || events.pageNo * events.pageSize >= events.total"
            @click="loadEvents(events.pageNo + 1)"
            >下一页</RButton
          >
        </div>
      </section>
    </template>
    <RModal :open="detailOpen" title="风险证据与当前策略" drawer @close="closeDetail">
      <div class="ar-detail">
        <h2>{{ selected && cardTitle(selected) }}</h2>
        <p class="ar-break">{{ displayShortUrl(selected?.fullShortUrl) }}</p>
        <p v-if="detailLoading" role="status">正在读取详情…</p>
        <p v-if="detailError" class="ar-alert ar-alert-danger" role="alert">{{ detailError }}</p>
        <template v-if="detail"
          ><RBadge :tone="riskTone(detail.card.riskLevel)"
            >{{ riskLevel(detail.card.riskLevel) }} · {{ metric(detail.card.riskScore) }}</RBadge
          >
          <div v-if="detail.card.reasonCodes.length" class="ar-reasons">
            <span v-for="reason in detail.card.reasonCodes" :key="reason" class="ar-reason">{{
              reason
            }}</span>
          </div>
          <p class="ar-answer-text">{{ detail.card.latestAgentSummary || '未提供分析摘要。' }}</p>
          <div class="ar-card-metrics">
            <span
              >2 小时 PV <b>{{ metric(detail.card.pv2h) }}</b></span
            ><span
              >2 小时 UV <b>{{ metric(detail.card.uv2h) }}</b></span
            ><span
              >24 小时 PV <b>{{ metric(detail.card.pv24h) }}</b></span
            ><span
              >24 小时 UV <b>{{ metric(detail.card.uv24h) }}</b></span
            ><span
              >7 天 PV <b>{{ metric(detail.card.pv7d) }}</b></span
            ><span
              >7 天 UV <b>{{ metric(detail.card.uv7d) }}</b></span
            >
          </div>
          <details class="ar-details">
            <summary>指标与数据完整度</summary>
            <pre class="ar-json">{{
              pretty({ metrics: detail.metrics, statsMeta: detail.card.statsMeta })
            }}</pre>
          </details>
          <details class="ar-details">
            <summary>历史风险快照（不能代表当前策略）</summary>
            <pre class="ar-json">{{ pretty(detail.latestSnapshot) }}</pre>
          </details>
          <h3>人工审核记录</h3>
          <pre v-if="Object.keys(detail.card.manualReview).length" class="ar-json">{{
            pretty(detail.card.manualReview)
          }}</pre>
          <p v-else class="ar-muted">暂无人工审核记录。</p>
          <RButton
            kind="secondary"
            :disabled="mutationBusy"
            @click="openReview({ ...selected, targetType: 'SHORT_LINK' })"
            >记录人工审核</RButton
          >
        </template>
        <section class="ar-policy-section">
          <header class="ar-section-head">
            <h3>当前策略</h3>
            <RButton
              kind="text"
              :disabled="!selected?.linkId || mutationBusy"
              :loading="policiesLoading"
              @click="refreshPolicies()"
              >刷新当前策略</RButton
            >
          </header>
          <p v-if="!selected?.linkId" class="ar-alert ar-alert-warning">
            档案缺少有效 linkId，无法读取或操作当前策略。
          </p>
          <p v-else-if="policies.state === 'UNKNOWN'" class="ar-alert ar-alert-warning">
            当前策略 UNKNOWN，尚不能确认是否有限制；空数组不代表没有策略。
          </p>
          <p v-else class="ar-caption">
            {{ policies.state === 'KNOWN_RESTRICTED' ? '观测到有效限制' : '观测时无有效限制' }} ·
            {{ formatTime(policies.asOf) }} · revision
            {{ policies.policyRevision }}。此结果是读取时的事实。
          </p>
          <p v-if="policiesError" class="ar-alert ar-alert-danger" role="alert">
            {{ policiesError }}
          </p>
          <article
            v-for="policy in policies.policies"
            :key="policy.policyId"
            class="ar-evidence-card"
          >
            <header class="ar-section-head">
              <h4>{{ policy.action }}</h4>
              <RBadge :tone="policy.revoked ? 'unknown' : policy.active ? 'warning' : 'info'">{{
                policy.revoked ? '已撤销' : policy.active ? '读取时激活' : '读取时非激活'
              }}</RBadge>
            </header>
            <p class="ar-break">{{ policy.policyId }}</p>
            <p class="ar-caption">
              {{ formatTime(policy.effectiveFrom) }} — {{ formatTime(policy.effectiveUntil) }}
            </p>
            <RButton v-if="currentCommand(policy)" kind="secondary" @click="openPolicy(policy)"
              >查看原命令结果</RButton
            ><RButton
              v-else
              kind="danger"
              :disabled="
                !policy.active || policy.revoked || mutationBusy || policies.state === 'UNKNOWN'
              "
              @click="openPolicy(policy)"
              >停用此策略</RButton
            >
          </article>
          <p
            v-if="policies.state !== 'UNKNOWN' && !policies.policies.length && !policiesLoading"
            class="ar-muted"
          >
            本次读取没有返回策略记录。
          </p>
          <RButton
            v-if="policies.nextCursor"
            kind="secondary"
            :disabled="policiesLoading"
            @click="refreshPolicies(true)"
            >加载更多策略</RButton
          >
        </section>
        <details v-if="detail?.recentEvents.length" class="ar-details">
          <summary>最近事件证据（{{ detail.recentEvents.length }}）</summary>
          <pre class="ar-json">{{ pretty(detail.recentEvents) }}</pre>
        </details>
      </div>
    </RModal>
    <RModal :open="review.open" title="记录人工审核" @close="closeReview"
      ><div class="ar-form-stack">
        <p class="ar-notice">审核只记录判断，不会激活、撤销或停用跳转策略。</p>
        <p class="ar-break">
          目标：{{ displayShortUrl(review.target?.fullShortUrl) || groupName }}
        </p>
        <template v-if="!review.result"
          ><RSelect
            v-model="review.action"
            label="审核结论"
            :options="REVIEW_ACTIONS"
            :disabled="review.busy"
          /><RTextarea
            v-model="review.note"
            label="审核说明"
            :maxlength="1000"
            :disabled="review.busy"
          />
          <p v-if="review.error" class="ar-alert ar-alert-danger" role="alert">
            {{ review.error }}
          </p></template
        >
        <template v-else
          ><RBadge tone="success">审核记录已保存</RBadge>
          <pre class="ar-json">{{ pretty(review.result) }}</pre>
        </template>
      </div>
      <template #footer
        ><RButton kind="secondary" :disabled="review.busy" @click="closeReview">{{
          review.result ? '完成' : '取消'
        }}</RButton
        ><RButton v-if="!review.result" :loading="review.busy" @click="submitReview"
          >保存审核记录</RButton
        ></template
      ></RModal
    >
    <RModal :open="policyDialog.open" title="停用现有策略" @close="closePolicy"
      ><div class="ar-form-stack">
        <p class="ar-break">{{ displayShortUrl(policyDialog.card?.fullShortUrl) }}</p>
        <p class="ar-break">策略：{{ policyDialog.policy?.policyId }}</p>
        <template v-if="!dialogCommand"
          ><p class="ar-alert ar-alert-warning">
            将提交该策略的撤销命令。命令提交与节点传播分别核验，不会改变人工审核结论。
          </p>
          <RTextarea v-model="policyDialog.reason" label="停用原因" :maxlength="1000" />
          <p v-if="policyDialog.error" class="ar-alert ar-alert-danger" role="alert">
            {{ policyDialog.error }}
          </p></template
        >
        <template v-else
          ><RBadge :tone="dialogCommand.tone">{{ dialogCommand.label }}</RBadge>
          <p class="ar-break">commandId：{{ dialogCommand.commandId }}</p>
          <p>传播状态：{{ dialogCommand.propagation }}</p>
          <p class="ar-caption">
            COMMITTED 表示命令已提交；PENDING / UNKNOWN / NOT_OBSERVED 均不能证明所有节点已同步。
          </p>
          <p v-if="dialogCommand.error" class="ar-alert ar-alert-warning">
            {{ dialogCommand.error }}
          </p></template
        >
      </div>
      <template #footer
        ><RButton kind="secondary" :disabled="dialogCommand?.submitting" @click="closePolicy"
          >关闭</RButton
        ><RButton v-if="!dialogCommand" kind="danger" @click="disablePolicy">确认停用此策略</RButton
        ><RButton
          v-else-if="dialogCommand.canQuery"
          :loading="dialogCommand.querying"
          @click="queryCommand(dialogCommand)"
          >查询原命令</RButton
        ></template
      ></RModal
    >
  </section>
</template>
