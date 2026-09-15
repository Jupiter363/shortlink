<script setup>
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { riskApi } from '../api/agentRisk.js'
import PageHeading from '../components/PageHeading.vue'
import AnalyticsMethodHint from '../components/AnalyticsMethodHint.vue'
import RiskTrendChart from '../components/RiskTrendChart.vue'
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
import './risk-dashboard.css'

const relay = inject('relay')
const listPanel = ref('')
const infoOpen = ref(false)
const eventDetail = ref(null)
const searchText = ref('')
const levelFilter = ref('ALL')
const eventPreview = ref({ records: [], total: null, loading: false, error: '' })
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
const pendingCommands = computed(() => loadedCommands.value.filter((command) => command.canQuery))
const priorityCards = computed(() =>
  (overview.value?.topRiskShortLinks?.length
    ? overview.value.topRiskShortLinks
    : cards.value
  ).slice(0, 3)
)
const filteredCards = computed(() => {
  const query = searchText.value.trim().toLocaleLowerCase()
  return cards.value.filter(
    (card) =>
      (levelFilter.value === 'ALL' ||
        (['LOW', 'MEDIUM', 'HIGH'].includes(String(card.riskLevel).toUpperCase())
          ? String(card.riskLevel).toUpperCase()
          : 'UNKNOWN') === levelFilter.value) &&
      (!query ||
        [
          cardTitle(card),
          card.fullShortUrl,
          ...card.reasonCodes,
          ...card.reasonCodes.map(riskReasonLabel)
        ]
          .join(' ')
          .toLocaleLowerCase()
          .includes(query))
  )
})
const distribution = computed(() =>
  [
    { key: 'highRiskCount', label: '高风险', color: '#c44949' },
    { key: 'mediumRiskCount', label: '中风险', color: '#be8426' },
    { key: 'lowRiskCount', label: '低风险', color: '#2d8471' }
  ].map((item) => ({ ...item, count: overview.value?.[item.key] ?? null }))
)
const scorePosition = computed(() => {
  const score = overview.value?.groupRiskScore
  return typeof score === 'number' && score >= 0 && score <= 100 ? score : null
})
const situationNote = computed(() => {
  if (!overview.value || overview.value.profileStatus === 'NOT_EVALUATED')
    return '等待画像产出后，再判断分组风险。'
  if (overview.value.groupReasonCodes.length) return reasonSummary(overview.value.groupReasonCodes)
  return overview.value.groupRiskLevel === 'LOW'
    ? '低风险画像，历史事件仍可追溯。'
    : '结合短链证据与事件记录进一步核查。'
})
const listPanelTitle = computed(
  () =>
    ({ cards: '短链风险档案', events: '短链风险事件', commands: '本次登录的策略回执' })[
      listPanel.value
    ] || ''
)
const coverageLabel = computed(() =>
  overview.value?.currentPolicyCoverage === 'TOP_CARDS_ONLY'
    ? '仅覆盖头部短链档案'
    : '策略覆盖范围待核实'
)

function refreshDashboard() {
  loadGroup()
  loadEvents(1)
}
function openList(panel) {
  listPanel.value = panel
}
function closeList() {
  if (!mutationBusy.value) listPanel.value = ''
}
function showEvent(event) {
  eventDetail.value = event
}
function clearArchiveFilters() {
  searchText.value = ''
  levelFilter.value = 'ALL'
}
const riskReasonLabel = (value) =>
  ({
    TRAFFIC_SPIKE: '访问量突增',
    IP_CONCENTRATION: 'IP 访问集中',
    HIGH_REPEAT_VISIT: '重复访问偏高',
    PEAK_HOUR_BURST: '高峰时段突发访问',
    DEVICE_CONCENTRATION: '设备类型集中',
    REGION_CONCENTRATION: '访问地域集中',
    BROWSER_CONCENTRATION: '浏览器类型集中'
  })[value] || value
const reasonSummary = (values) => values.map(riskReasonLabel).join(' · ')
const watchStatusLabel = (value) =>
  ({ WATCHING: '关注中', NONE: '未关注' })[value] || value || '未提供'
const eventTargetLabel = (event) =>
  event.shortUri ? `短链 · ${event.shortUri}` : displayShortUrl(event.fullShortUrl) || '短链事件'

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
  if (pageNo === 1)
    eventPreview.value = { records: [], total: null, loading: Boolean(gid), error: '' }
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
    if (pageNo === 1)
      eventPreview.value = {
        records: events.value.records.slice(0, 3),
        total: events.value.total,
        loading: false,
        error: ''
      }
  } catch (error) {
    if (!disposed && version === eventVersion && !controller.signal.aborted) {
      errors.value.events = errorMessage(error)
      if (pageNo === 1) eventPreview.value.error = errors.value.events
    }
  } finally {
    if (version === eventVersion) {
      eventsLoading.value = false
      if (pageNo === 1) eventPreview.value.loading = false
    }
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
    (card.shortUri ? `短链 · ${card.shortUri}` : displayShortUrl(card.fullShortUrl)) ||
    '短链风险档案'
  )
}
watch(
  groupId,
  () => {
    relay.state.groupId = groupId.value
    listPanel.value = ''
    eventDetail.value = null
    clearArchiveFilters()
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
  <section class="ar-view risk-dashboard-page operation-page" aria-labelledby="risk-heading">
    <PageHeading class="ar-page-head">
      <div>
        <p class="ar-kicker">JUPITER RELAY / 风险中继台</p>
        <h1 id="risk-heading">风险中心</h1>
        <p>查看风险事实、记录人工结论，独立核验策略执行。</p>
      </div>
    </PageHeading>
    <div class="risk-context-bar">
      <div class="risk-context-select">
        <RSelect v-model="groupId" label="分组" :options="groupOptions" :disabled="mutationBusy" />
      </div>
      <div class="risk-context-actions">
        <RButton
          kind="secondary"
          :disabled="loading || eventsLoading || !groupId || mutationBusy"
          @click="refreshDashboard"
          >{{ loading || eventsLoading ? '读取中…' : '刷新' }}</RButton
        >

        <RButton kind="text" @click="infoOpen = true">数据说明</RButton>
      </div>
    </div>
    <div
      class="risk-dashboard-body operation-body"
      role="region"
      aria-label="风险仪表盘"
      tabindex="0"
    >
      <div v-if="!groupId" class="ar-panel ar-empty">
        <RRobot role="guardian" :size="120" />
        <h2>先创建一个短链分组</h2>
        <p>风险档案会在真实访问与异步统计产出数据后出现。</p>
        <RButton @click="relay.go('/home/space')">前往短链空间</RButton>
      </div>
      <template v-else>
        <p v-if="errors.overview" class="risk-inline-error" role="alert">
          风险画像读取失败：{{ errors.overview }}
        </p>
        <p v-if="loading" class="risk-sr-only" role="status">正在读取风险画像与短链档案…</p>
        <section class="risk-situation" aria-label="分组风险态势" :aria-busy="loading">
          <div
            class="risk-status-console"
            :data-tone="overview ? overviewRiskTone(overview) : 'unknown'"
          >
            <div class="risk-console-heading">
              <RIcon name="shield" :size="18" />
              <span>风险态势</span>
              <AnalyticsMethodHint label="风险态势：评分口径">
                <strong>分组风险画像</strong>
                <p>
                  评分来自服务端综合画像，不是短链平均分。0–39 分为低风险、40–69 分为中风险、70–100
                  分为高风险。低风险不等于没有风险；未评估时不显示零分。
                </p>
              </AnalyticsMethodHint>
            </div>
            <div class="risk-console-verdict">
              <span class="risk-status-dot" aria-hidden="true" />
              <h2>{{ overview ? overviewRiskLabel(overview) : '等待风险画像' }}</h2>
            </div>
            <div class="risk-console-score">
              <strong>{{ metric(overview?.groupRiskScore) }}</strong
              ><span>风险分数<small>/ 100</small></span>
            </div>
            <div class="risk-score-ruler" aria-hidden="true">
              <div class="risk-score-bands"><span /><span /><span /></div>
              <i v-if="scorePosition !== null" :style="{ left: `${scorePosition}%` }" />
              <div class="risk-score-ticks">
                <span>0</span><span>40</span><span>70</span><span>100</span>
              </div>
            </div>
            <p class="risk-console-note">{{ situationNote }}</p>
          </div>
          <div class="risk-situation-detail">
            <header class="risk-panel-header">
              <div class="risk-panel-title">
                <h2 id="risk-trend-title">近 7 天风险轨迹</h2>
                <AnalyticsMethodHint label="风险轨迹：统计口径"
                  ><strong>按画像日期追踪评分</strong>
                  <p>
                    分数是风险画像评分，不是事件数量。仅展示返回的日期；缺失日期不补零，零分在基线显示。
                  </p></AnalyticsMethodHint
                >
              </div>
              <span class="risk-scan-caption"
                >已扫描 <strong>{{ metric(overview?.totalShortLinksScanned) }}</strong> 条短链</span
              >
            </header>
            <div class="risk-trajectory">
              <RiskTrendChart
                :points="overview?.riskTrend7d || []"
                :unavailable="overview?.profileStatus === 'NOT_EVALUATED'"
              />
            </div>
            <div class="risk-severity-strip" aria-label="画像中的短链风险等级数量">
              <div
                v-for="item in distribution"
                :key="item.key"
                :style="{ '--severity-color': item.color }"
              >
                <span><i aria-hidden="true" />{{ item.label }}</span
                ><strong>{{ metric(item.count) }}<small> 条</small></strong>
              </div>
            </div>
          </div>
        </section>

        <div class="risk-investigation-grid">
          <section
            class="risk-investigation-panel risk-targets-panel"
            aria-labelledby="risk-priority-title"
          >
            <header class="risk-panel-header">
              <div class="risk-panel-title">
                <h2 id="risk-priority-title">短链核查</h2>
                <AnalyticsMethodHint label="短链核查：列表范围"
                  ><strong>优先核查服务端头部档案</strong>
                  <p>
                    按服务端顺序预览最多 3 条，可能包含低风险短链。完整档案最多返回 500
                    条；头部档案和列表数量不能推断全组总数。风险原因属于画像，当前策略需进入详情读取。
                  </p></AnalyticsMethodHint
                >
              </div>
              <button type="button" class="risk-panel-action" @click="openList('cards')">
                全部档案 <span aria-hidden="true">↗</span>
              </button>
            </header>
            <p class="risk-panel-lead">从风险档案定位短链，进入详情核查证据与当前策略。</p>
            <div class="risk-target-list" tabindex="0" aria-label="短链核查列表">
              <p v-if="loading" class="risk-muted" role="status">正在读取档案…</p>
              <p
                v-else-if="errors.cards && !priorityCards.length"
                class="risk-inline-error"
                role="alert"
              >
                {{ errors.cards }}
              </p>
              <ol v-else-if="priorityCards.length" class="risk-targets">
                <li
                  v-for="card in priorityCards"
                  :key="`${card.gid}:${card.domain}:${card.shortUri}`"
                >
                  <button
                    type="button"
                    class="risk-target"
                    :disabled="mutationBusy"
                    @click="openDetail(card)"
                  >
                    <span class="risk-target-score" :data-tone="riskTone(card.riskLevel)"
                      ><strong>{{ metric(card.riskScore) }}</strong
                      ><small>{{ riskLevel(card.riskLevel) }}</small></span
                    >
                    <span class="risk-target-body"
                      ><strong class="risk-target-name">{{ cardTitle(card) }}</strong>
                      <span class="risk-target-facts"
                        >2 小时 PV {{ metric(card.pv2h) }} <span>·</span> UV {{ metric(card.uv2h) }}
                        <span>·</span> {{ watchStatusLabel(card.watchStatus) }}</span
                      >
                      <span class="risk-target-reason">{{
                        reasonSummary(card.reasonCodes) || '暂未提供风险原因'
                      }}</span>
                    </span>
                    <span class="risk-target-open">核查<span aria-hidden="true">↗</span></span>
                  </button>
                </li>
              </ol>
              <div v-else class="risk-panel-empty">
                <RIcon name="shield" :size="28" /><strong>尚无可核查的档案</strong
                ><span>等待风险画像产出</span>
              </div>
            </div>
            <footer class="risk-targets-footer">
              <span
                >画像平均分 <strong>{{ metric(overview?.avgRiskScore) }}</strong></span
              ><span
                >最高分 <strong>{{ metric(overview?.maxRiskScore) }}</strong></span
              ><span>头部档案预览</span>
            </footer>
          </section>

          <section
            class="risk-investigation-panel risk-timeline-panel"
            aria-labelledby="risk-events-title"
          >
            <header class="risk-panel-header">
              <div class="risk-panel-title">
                <h2 id="risk-events-title">事件时间线</h2>
                <AnalyticsMethodHint label="事件时间线：查询范围"
                  ><strong>短链事件的历史证据</strong>
                  <p>
                    预览当前分组最近的短链风险事件，不包含分组事件。这里的评分、风险原因及建议均反映事件发生时的状态，不等于当前画像或当前生效策略。
                  </p></AnalyticsMethodHint
                >
              </div>
              <button type="button" class="risk-panel-action" @click="openList('events')">
                全部事件 <span aria-hidden="true">↗</span>
              </button>
            </header>
            <p class="risk-panel-lead">保留发生时的风险信号，逐条追溯证据。</p>
            <div class="risk-timeline-scroll" tabindex="0" aria-label="近期短链事件时间线">
              <p v-if="eventPreview.loading" class="risk-muted" role="status">正在读取事件…</p>
              <p v-else-if="eventPreview.error" class="risk-inline-error" role="alert">
                {{ eventPreview.error }}
              </p>
              <ol v-else-if="eventPreview.records.length" class="risk-timeline">
                <li
                  v-for="event in eventPreview.records"
                  :key="event.eventId"
                  :data-tone="riskTone(event.riskLevel)"
                >
                  <button type="button" class="risk-timeline-event" @click="showEvent(event)">
                    <span class="risk-timeline-top"
                      ><time>{{ formatTime(event.eventTime) }}</time
                      ><span class="risk-event-level"
                        >{{ riskLevel(event.riskLevel) }} · {{ metric(event.riskScore) }}</span
                      ></span
                    >
                    <strong>{{ reasonSummary(event.reasonCodes) || '风险事件' }}</strong>
                    <span class="risk-event-target"
                      >{{ eventTargetLabel(event) }}<span aria-hidden="true">↗</span></span
                    >
                  </button>
                </li>
              </ol>
              <div v-else class="risk-panel-empty">
                <RIcon name="shield" :size="28" /><strong>暂无短链风险事件</strong
                ><span>当前查询未返回事件记录</span>
              </div>
            </div>
          </section>
        </div>

        <section class="risk-action-rail" aria-label="人工研判与执行核验">
          <div class="risk-action-station">
            <span class="risk-station-icon"><RIcon name="shield" :size="20" /></span>
            <div class="risk-station-body">
              <h2>深入研判</h2>
              <p>结合访问证据分析异常原因</p>
              <button
                type="button"
                class="risk-panel-action"
                @click="relay.go('/home/agent/security-risk')"
              >
                打开风控 Agent <span aria-hidden="true">↗</span>
              </button>
            </div>
          </div>
          <div class="risk-action-station">
            <span class="risk-station-icon"><RIcon name="pencil" :size="20" /></span>
            <div class="risk-station-body">
              <div class="risk-station-heading">
                <h2>人工审核</h2>
                <AnalyticsMethodHint label="人工审核：统计口径"
                  ><strong>人工结论独立于策略</strong>
                  <p>
                    人工关注数量来自审核状态，不是待处置数量。记录关注、确认风险或标记误报只保存人工判断，不自动变更跳转策略。
                  </p></AnalyticsMethodHint
                >
              </div>
              <p>
                关注中 <strong>{{ metric(overview?.watchingCount) }}</strong> 条 · 审核仅记录判断
              </p>
              <button
                type="button"
                class="risk-panel-action"
                :disabled="!groupId || mutationBusy"
                @click="openReview({ targetType: 'GROUP', gid: groupId })"
              >
                记录分组审核 <span aria-hidden="true">↗</span>
              </button>
            </div>
          </div>
          <div class="risk-action-station">
            <span class="risk-station-icon"><RIcon name="database" :size="20" /></span>
            <div class="risk-station-body">
              <div class="risk-station-heading">
                <h2>执行核验</h2>
                <AnalyticsMethodHint label="执行核验：统计范围"
                  ><strong>本次登录保留的策略命令</strong>
                  <p>
                    结果待核实
                    {{ pendingCommands.length }}
                    条。提交不等于节点已同步；结果未知时只查询原命令。全组策略停用数：{{
                      metric(overview?.disabledCount)
                    }}。{{ coverageLabel }}，不能回算全组停用数。
                  </p></AnalyticsMethodHint
                >
              </div>
              <p>
                本次登录 <strong>{{ loadedCommands.length }}</strong> 条命令<span
                  v-if="pendingCommands.length"
                >
                  · {{ pendingCommands.length }} 条待核实</span
                >
              </p>
              <button type="button" class="risk-panel-action" @click="openList('commands')">
                查看策略回执 <span aria-hidden="true">↗</span>
              </button>
            </div>
          </div>
        </section>
      </template>
    </div>

    <RModal
      :open="infoOpen"
      title="风险数据说明"
      :description="groupName"
      :width="620"
      @close="infoOpen = false"
    >
      <div class="ar-form-stack">
        <section>
          <h3>风险画像与覆盖范围</h3>
          <p>
            风险分数、高中低数量、均分及最高分来自当前分组画像。未评估或未返回时展示未知；短链档案与事件接口独立读取，某一项失败不代表其他数据为空。
          </p>
          <p class="risk-section-note">
            当前策略覆盖：{{ coverageLabel }}。头部档案不能用于回算全组停用总数。
          </p>
        </section>
        <section>
          <h3>审核、策略与回执</h3>
          <p>
            人工审核只记录判断。历史事件建议和画像动作不等于当前策略；停用前仍需读取现行策略。命令提交与节点传播分开核验，结果未知时只查询原命令。
          </p>
        </section>
        <section v-if="overview?.groupReasonCodes.length">
          <h3>分组风险原因</h3>
          <div class="ar-reasons">
            <span v-for="reason in overview.groupReasonCodes" :key="reason" class="ar-reason">{{
              reason
            }}</span>
          </div>
        </section>
        <section v-if="overview?.agentSummary">
          <h3>Agent 风险摘要</h3>
          <p class="ar-answer-text">{{ overview.agentSummary }}</p>
        </section>
      </div>
    </RModal>
    <RModal
      :open="Boolean(listPanel)"
      :title="listPanelTitle"
      :description="groupName"
      :width="1080"
      drawer
      @close="closeList"
    >
      <template v-if="listPanel === 'cards'">
        <div class="risk-list-tools">
          <label class="risk-search"
            ><span class="risk-sr-only">搜索风险档案</span
            ><input
              v-model="searchText"
              type="search"
              placeholder="搜索短链或风险原因"
              aria-label="搜索风险档案" /></label
          ><RSelect
            v-model="levelFilter"
            label="风险等级"
            :options="[
              { value: 'ALL', label: '全部等级' },
              { value: 'HIGH', label: '高风险' },
              { value: 'MEDIUM', label: '中风险' },
              { value: 'LOW', label: '低风险' },
              { value: 'UNKNOWN', label: '风险未知' }
            ]"
          /><span v-if="!loading && !errors.cards" class="risk-muted"
            >{{ filteredCards.length }} / {{ cards.length }} 条已返回</span
          >
        </div>
        <p class="risk-section-note">
          按服务端风险顺序排列，最多返回 500 条；无档案不代表没有风险。
        </p>
        <p v-if="loading" role="status">正在读取短链档案…</p>
        <p v-else-if="errors.cards" class="risk-inline-error" role="alert">{{ errors.cards }}</p>
        <template v-else-if="filteredCards.length">
          <table class="risk-archive-table">
            <thead>
              <tr>
                <th>短链 / 风险原因</th>
                <th>风险等级</th>
                <th>2 小时 PV / UV</th>
                <th>7 天 PV / UV</th>
                <th>关注状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="card in filteredCards"
                :key="`${card.gid}:${card.domain}:${card.shortUri}`"
              >
                <td>
                  <strong>{{ cardTitle(card) }}</strong
                  ><small>{{ displayShortUrl(card.fullShortUrl) }}</small
                  ><small>{{ reasonSummary(card.reasonCodes) || '未提供风险原因' }}</small>
                </td>
                <td>
                  <RBadge :tone="riskTone(card.riskLevel)"
                    >{{ riskLevel(card.riskLevel) }} · {{ metric(card.riskScore) }}</RBadge
                  >
                </td>
                <td>{{ metric(card.pv2h) }} / {{ metric(card.uv2h) }}</td>
                <td>{{ metric(card.pv7d) }} / {{ metric(card.uv7d) }}</td>
                <td>{{ watchStatusLabel(card.watchStatus) }}</td>
                <td>
                  <RButton kind="text" :disabled="mutationBusy" @click="openDetail(card)"
                    >查看证据与策略</RButton
                  >
                </td>
              </tr>
            </tbody>
          </table>
          <div class="risk-archive-cards">
            <article
              v-for="card in filteredCards"
              :key="`${card.gid}:${card.domain}:${card.shortUri}`"
            >
              <header class="risk-preview-heading">
                <strong>{{ cardTitle(card) }}</strong
                ><RBadge :tone="riskTone(card.riskLevel)"
                  >{{ riskLevel(card.riskLevel) }} · {{ metric(card.riskScore) }}</RBadge
                >
              </header>
              <p class="ar-break risk-section-note">{{ displayShortUrl(card.fullShortUrl) }}</p>
              <p class="risk-section-note">
                2 小时 PV {{ metric(card.pv2h) }} / UV {{ metric(card.uv2h) }}
              </p>
              <p class="risk-section-note">
                7 天 PV {{ metric(card.pv7d) }} / UV {{ metric(card.uv7d) }}
              </p>
              <p class="risk-section-note">关注：{{ watchStatusLabel(card.watchStatus) }}</p>
              <p class="risk-section-note">
                {{ reasonSummary(card.reasonCodes) || '未提供风险原因' }}
              </p>
              <RButton kind="text" :disabled="mutationBusy" @click="openDetail(card)"
                >查看证据与策略</RButton
              >
            </article>
          </div>
        </template>
        <div v-else class="risk-panel-empty">
          <strong>{{ cards.length ? '没有符合条件的档案' : '尚无可展示的风险档案' }}</strong
          ><RButton v-if="cards.length" kind="text" @click="clearArchiveFilters">清除筛选</RButton
          ><RButton v-else kind="text" @click="relay.go('/home/agent/security-risk')"
            >打开安全风控 Agent</RButton
          >
        </div>
      </template>
      <template v-else-if="listPanel === 'events'">
        <p class="risk-section-note">
          仅包含当前分组的短链事件。证据与建议反映事件发生时的情况，不等于当前策略。
        </p>
        <p v-if="errors.events" class="risk-inline-error" role="alert">{{ errors.events }}</p>
        <p v-else-if="eventsLoading" role="status">正在读取风险事件…</p>
        <template v-else
          ><ol class="risk-event-list">
            <li v-for="event in events.records" :key="event.eventId">
              <header class="risk-preview-heading">
                <strong class="ar-break">{{
                  displayShortUrl(event.fullShortUrl) || '短链事件'
                }}</strong
                ><RBadge :tone="riskTone(event.riskLevel)"
                  >{{ riskLevel(event.riskLevel) }} · {{ metric(event.riskScore) }}</RBadge
                >
              </header>
              <time class="risk-preview-time">{{ formatTime(event.eventTime) }}</time>
              <div class="ar-reasons">
                <span v-for="reason in event.reasonCodes" :key="reason" class="ar-reason">{{
                  reason
                }}</span>
              </div>
              <div class="ar-actions">
                <RButton kind="text" @click="showEvent(event)">查看事件证据</RButton
                ><RButton kind="text" :disabled="mutationBusy" @click="openReview(event)"
                  >记录审核</RButton
                >
              </div>
            </li>
          </ol>
          <p v-if="!events.records.length" class="risk-muted">暂无短链风险事件。</p>
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
          </div></template
        >
        <RButton v-if="errors.events" kind="text" @click="loadEvents(events.pageNo)"
          >重试事件查询</RButton
        >
      </template>
      <template v-else-if="listPanel === 'commands'">
        <p class="risk-section-note">
          仅展示本次登录保留的策略命令。等待超时后只查询原命令，不重复发送停用动作。
        </p>
        <div v-if="!loadedCommands.length" class="risk-panel-empty">
          <strong>本次登录尚未提交策略命令</strong><span>策略回执会在操作后保留在这里。</span>
        </div>
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
      </template>
    </RModal>
    <RModal
      :open="Boolean(eventDetail)"
      title="短链风险事件证据"
      :width="760"
      drawer
      @close="eventDetail = null"
    >
      <template v-if="eventDetail"
        ><p class="risk-section-note">这是事件发生时的证据与建议，不代表当前生效策略。</p>
        <header class="risk-preview-heading">
          <strong class="ar-break">{{
            displayShortUrl(eventDetail.fullShortUrl) || '短链事件'
          }}</strong
          ><RBadge :tone="riskTone(eventDetail.riskLevel)"
            >{{ riskLevel(eventDetail.riskLevel) }} · {{ metric(eventDetail.riskScore) }}</RBadge
          >
        </header>
        <p class="risk-section-note">{{ formatTime(eventDetail.eventTime) }}</p>
        <div class="ar-reasons">
          <span v-for="reason in eventDetail.reasonCodes" :key="reason" class="ar-reason">{{
            riskReasonLabel(reason)
          }}</span>
        </div>
        <dl class="risk-event-evidence">
          <div
            v-for="item in [
              { field: 'pv2h', label: '2 小时访问 PV' },
              { field: 'uv2h', label: '2 小时访客 UV' },
              { field: 'pv24h', label: '24 小时访问 PV' },
              { field: 'pv7d', label: '7 天访问 PV' }
            ]"
            :key="item.field"
          >
            <dt>{{ item.label }}</dt>
            <dd>{{ metric(eventDetail.evidence?.[item.field]) }}</dd>
          </div>
        </dl>
        <details v-if="eventDetail.agentSummary" class="ar-details">
          <summary>查看当时的风险分析</summary>
          <p class="ar-answer-text">{{ eventDetail.agentSummary }}</p>
        </details>
        <details class="ar-details">
          <summary>原始事件与数据口径</summary>
          <pre class="ar-json">{{ pretty(eventDetail) }}</pre>
        </details>
        <RButton kind="secondary" :disabled="mutationBusy" @click="openReview(eventDetail)"
          >记录审核</RButton
        ></template
      >
    </RModal>
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
