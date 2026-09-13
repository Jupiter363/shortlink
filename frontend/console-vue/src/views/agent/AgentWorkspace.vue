<template>
  <div class="agent-page">
    <section class="page-heading">
      <div>
        <div class="eyebrow"><MagicStick /> 智能分析与风控</div>
        <h1>Agent 工作台</h1>
        <p>使用当前账号的数据权限执行统计分析、异常诊断和受控策略建议。</p>
      </div>
      <button class="health-status" type="button" :disabled="health.loading" @click="checkHealth">
        <span class="health-dot" :class="health.state"></span>
        <span>{{ health.text }}</span>
        <Refresh :class="{ spinning: health.loading }" />
      </button>
    </section>

    <div class="workspace-grid">
      <aside class="composer-panel surface">
        <div class="panel-heading">
          <div>
            <span class="panel-kicker">NEW RUN</span>
            <h2>发起分析</h2>
          </div>
          <el-tooltip content="创建新会话" placement="top">
            <button
              class="icon-button"
              type="button"
              aria-label="创建新会话"
              :disabled="sending"
              @click="resetSession"
            >
              <RefreshRight />
            </button>
          </el-tooltip>
        </div>

        <div class="session-line">
          <span>会话</span>
          <code>{{ shortSessionId }}</code>
        </div>

        <label class="field-label">选择 Agent</label>
        <div class="agent-picker" role="radiogroup" aria-label="选择 Agent">
          <button
            v-for="option in agentOptions"
            :key="option.value"
            class="agent-option"
            :class="{ active: form.agentType === option.value }"
            type="button"
            role="radio"
            :aria-checked="form.agentType === option.value"
            :disabled="sending"
            @click="selectAgent(option.value)"
          >
            <span class="agent-icon" :class="option.tone">
              <component :is="option.icon" />
            </span>
            <span>
              <strong>{{ option.label }}</strong>
              <small>{{ option.description }}</small>
            </span>
            <CircleCheckFilled v-if="form.agentType === option.value" class="selected-check" />
          </button>
        </div>

        <div class="prompt-heading">
          <label class="field-label">分析指令</label>
          <span>{{ form.message.length }}/2000</span>
        </div>
        <el-input
          v-model="form.message"
          type="textarea"
          :rows="8"
          maxlength="2000"
          resize="vertical"
          :disabled="sending"
          placeholder="描述你希望分析的分组、短链接、时间范围或风险问题"
          @keydown.ctrl.enter.prevent="sendMessage"
          @keydown.meta.enter.prevent="sendMessage"
        />

        <div class="preset-block">
          <span class="preset-label">快捷指令</span>
          <button
            v-for="preset in visiblePresets"
            :key="preset.label"
            class="preset-button"
            type="button"
            :disabled="sending"
            @click="applyPreset(preset.prompt)"
          >
            {{ preset.label }}
          </button>
        </div>

        <button
          class="send-button"
          type="button"
          :disabled="sending || !form.message.trim()"
          @click="sendMessage"
        >
          <Loading v-if="sending" class="spinning" />
          <Promotion v-else />
          <span>{{ sending ? 'Agent 执行中…' : '开始分析' }}</span>
          <kbd v-if="!sending">Ctrl ↵</kbd>
        </button>
        <p class="auth-note"><Lock /> 请求自动使用当前登录身份，数据范围由服务端鉴权。</p>
      </aside>

      <section class="result-column">
        <div v-if="!hasResult && !sending" class="empty-result surface">
          <div class="empty-illustration"><DataAnalysis /></div>
          <span class="empty-kicker">READY</span>
          <h2>等待分析任务</h2>
          <p>选择 Agent 并输入问题，结果会在这里展示为回答、指标卡片和完整执行轨迹。</p>
          <div class="capability-list">
            <span><TrendCharts /> 流量与转化洞察</span>
            <span><Warning /> 异常与风险诊断</span>
            <span><Connection /> 工具调用可追溯</span>
          </div>
        </div>

        <div v-else-if="sending" class="running-result surface" aria-live="polite">
          <div class="running-orbit"><MagicStick /></div>
          <div>
            <span class="empty-kicker">RUNNING</span>
            <h2>Agent 正在分析</h2>
            <p>正在编排工具、读取授权数据并生成结论，请保持当前页面开启。</p>
          </div>
        </div>

        <template v-else>
          <section class="answer-card surface" :class="{ failed: requestFailed }">
            <div class="answer-head">
              <div class="answer-title">
                <span class="answer-icon"><ChatDotRound /></span>
                <div>
                  <span class="panel-kicker">AGENT RESPONSE</span>
                  <h2>{{ requestFailed ? '请求未完成' : '分析结论' }}</h2>
                </div>
              </div>
              <div v-if="result.traceId" class="trace-chip">
                <span>Trace</span>
                <code>{{ truncateMiddle(result.traceId, 18) }}</code>
                <button type="button" aria-label="复制 Trace ID" @click="copyText(result.traceId, 'Trace ID')">
                  <CopyDocument />
                </button>
              </div>
            </div>
            <div class="answer-body">{{ result.answer || 'Agent 未返回文字结论。' }}</div>
            <div class="answer-meta">
              <span><Timer /> {{ result.sessionId || sessionId }}</span>
              <span>{{ resultAgent.label }}</span>
            </div>
          </section>

          <section v-if="warnings.length" class="warning-stack" aria-live="polite">
            <div v-for="(warning, index) in warnings" :key="index" class="warning-item">
              <WarningFilled />
              <span>{{ formatValue(warning) }}</span>
            </div>
          </section>

          <section v-if="insightCards.length" class="insight-grid">
            <article
              v-for="(card, cardIndex) in insightCards"
              :key="`${card.type || 'card'}-${cardIndex}`"
              class="insight-card surface"
              :class="cardTone(card)"
            >
              <div class="insight-head">
                <div>
                  <span class="card-type">{{ prettyKey(card.type || 'insight') }}</span>
                  <h3>{{ card.title || prettyKey(card.type || '分析结果') }}</h3>
                </div>
                <span class="severity-badge">{{ severityText(card) }}</span>
              </div>
              <p v-if="card.message" class="card-message">{{ card.message }}</p>
              <dl v-if="objectEntries(card.metrics).length" class="metric-grid">
                <div v-for="([key, value]) in objectEntries(card.metrics)" :key="key">
                  <dt>{{ prettyKey(key) }}</dt>
                  <dd>{{ displayMetric(key, value) }}</dd>
                </div>
              </dl>
              <div v-if="objectEntries(card.summary).length" class="detail-block">
                <span>摘要</span>
                <dl>
                  <template v-for="([key, value]) in objectEntries(card.summary)" :key="key">
                    <dt>{{ prettyKey(key) }}</dt>
                    <dd>{{ formatValue(value) }}</dd>
                  </template>
                </dl>
              </div>
              <div v-if="objectEntries(card.evidence).length" class="detail-block">
                <span>证据</span>
                <dl>
                  <template v-for="([key, value]) in objectEntries(card.evidence)" :key="key">
                    <dt>{{ prettyKey(key) }}</dt>
                    <dd>{{ formatValue(value) }}</dd>
                  </template>
                </dl>
              </div>
            </article>
          </section>

          <section v-if="pendingActions.length" class="surface section-card action-section">
            <div class="section-heading">
              <div>
                <span class="section-icon amber"><Select /></span>
                <div>
                  <span class="panel-kicker">RISK ACTIONS</span>
                  <h2>风控动作</h2>
                </div>
              </div>
              <span class="count-badge">{{ pendingActions.length }}</span>
            </div>
            <article
              v-for="(action, index) in pendingActions"
              :key="action.actionId || action.id || index"
              class="action-row"
              :class="actionStatusTone(action.status)"
            >
              <div class="action-summary">
                <div>
                  <h3>{{ action.title || action.actionName || action.type || '风控动作' }}</h3>
                  <p>{{ action.description || action.message || actionFallbackDescription(action) }}</p>
                </div>
                <span class="action-status" :class="actionStatusTone(action.status)">
                  {{ actionStatusText(action.status) }}
                </span>
              </div>
              <div class="action-meta">
                <code v-if="action.actionId || action.id">{{ action.actionId || action.id }}</code>
                <span v-if="action.expiresAt">有效期至 {{ action.expiresAt }}</span>
              </div>
              <pre v-if="actionDetails(action)">{{ formatValue(actionDetails(action)) }}</pre>
            </article>
          </section>

          <section v-if="traceEvents.length" class="surface section-card">
            <div class="section-heading">
              <div>
                <span class="section-icon blue"><Connection /></span>
                <div>
                  <span class="panel-kicker">EXECUTION</span>
                  <h2>执行轨迹</h2>
                </div>
              </div>
              <span class="count-badge">{{ traceEvents.length }} 节点</span>
            </div>
            <ol class="trace-list">
              <li v-for="(event, index) in traceEvents" :key="`${event.nodeName || 'node'}-${index}`">
                <span class="trace-marker" :class="traceTone(event.status)">
                  <CircleCheck v-if="traceTone(event.status) === 'success'" />
                  <CircleClose v-else-if="traceTone(event.status) === 'failed'" />
                  <MoreFilled v-else />
                </span>
                <div class="trace-content">
                  <div class="trace-title">
                    <strong>{{ event.nodeName || 'graph_node' }}</strong>
                    <span :class="traceTone(event.status)">{{ event.status || 'unknown' }}</span>
                  </div>
                  <div class="trace-meta">
                    <span v-if="event.timing?.durationMs !== undefined">{{ event.timing.durationMs }} ms</span>
                    <span v-if="event.checkpointVersion !== undefined">checkpoint {{ event.checkpointVersion }}</span>
                  </div>
                  <p v-if="event.error" class="trace-error">{{ formatValue(event.error) }}</p>
                  <dl v-if="objectEntries(event.summary).length" class="trace-summary">
                    <template v-for="([key, value]) in objectEntries(event.summary)" :key="key">
                      <dt>{{ prettyKey(key) }}</dt>
                      <dd>{{ formatValue(value) }}</dd>
                    </template>
                  </dl>
                </div>
              </li>
            </ol>
          </section>

          <section v-if="accessRows.length" class="surface section-card">
            <div class="section-heading">
              <div>
                <span class="section-icon violet"><List /></span>
                <div>
                  <span class="panel-kicker">SANITIZED RECORDS</span>
                  <h2>访问记录</h2>
                </div>
              </div>
              <span class="count-badge">{{ accessRows.length }} 条</span>
            </div>
            <el-table :data="accessRows.map(toAccessRecordRow)" stripe table-layout="auto" class="record-table">
              <el-table-column prop="createTime" label="时间（北京时间）" min-width="185" />
              <el-table-column prop="locale" label="地区" min-width="110" />
              <el-table-column prop="device" label="设备" min-width="90" />
              <el-table-column prop="browser" label="浏览器" min-width="100" />
              <el-table-column prop="os" label="系统" min-width="90" />
              <el-table-column prop="network" label="运营商 / ISP" min-width="120" />
              <el-table-column prop="ip" label="IP（脱敏）" min-width="160" />
              <el-table-column prop="uvType" label="访客（脱敏）" min-width="160" />
              <el-table-column prop="visitorType" label="历史首次观测" min-width="130" />
              <el-table-column prop="status" label="响应结果" min-width="100" />
              <el-table-column prop="eventType" label="事件类型" min-width="110" />
            </el-table>
          </section>

          <el-collapse class="debug-collapse">
            <el-collapse-item name="debug">
              <template #title>
                <span class="debug-title"><DocumentCopy /> 脱敏调试数据</span>
              </template>
              <div class="debug-grid">
                <div>
                  <span>Tool Calls</span>
                  <pre>{{ formatValue(result.toolCalls) }}</pre>
                </div>
                <div>
                  <span>Data Sources</span>
                  <pre>{{ formatValue(result.dataSources) }}</pre>
                </div>
                <div class="full-response">
                  <span>Full Response</span>
                  <pre>{{ formatValue(result) }}</pre>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Lock, WarningFilled } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { toAccessRecordRow } from '@/utils/agentAccessRecords'

const { proxy } = getCurrentInstance()
const API = proxy.$API
const route = useRoute()
const router = useRouter()
const sessionStorageKeyPrefix = 'shortLinkAgentConsoleSessionId'
const legacySessionStorageKey = sessionStorageKeyPrefix
const agentTypeStorageKey = 'shortLinkAgentConsoleAgentType'

const agentOptions = [
  {
    value: 'campaign-analysis',
    label: '投放分析',
    description: '流量趋势、渠道表现与异常解释',
    icon: DataAnalysis,
    tone: 'blue'
  },
  {
    value: 'security-risk',
    label: '安全风控',
    description: '访问风险、策略诊断与受控建议',
    icon: Lock,
    tone: 'violet'
  }
]

const presets = {
  'campaign-analysis': [
    { label: '分析默认分组近 7 天表现', prompt: '分析 default 分组最近 7 天的流量表现和异常变化' },
    { label: '解释流量异常', prompt: '诊断 default 分组最近 24 小时的流量异常，并说明判断依据' },
    { label: '查看访问构成', prompt: '汇总 default 分组最近 7 天的访问设备、地区和浏览器构成' }
  ],
  'security-risk': [
    { label: '诊断近 24 小时风险', prompt: '诊断 default 分组最近 24 小时的访问风险' },
    { label: '检查活跃风险链接', prompt: '列出当前活跃的高风险短链接并说明风险证据' },
    { label: '审视当前策略', prompt: '检查当前风险策略，指出覆盖空白并给出受控调整建议' }
  ]
}

const createSessionId = () => {
  if (window.crypto?.randomUUID) return `console-${window.crypto.randomUUID()}`
  return `console-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const allowedAgentTypes = new Set(agentOptions.map((option) => option.value))
const normalizeAgentType = (value) => {
  const normalized = String(value || '')
    .trim()
    .toLowerCase()
  return allowedAgentTypes.has(normalized) ? normalized : 'campaign-analysis'
}
const sessionStorageKey = (agentType) => `${sessionStorageKeyPrefix}:${normalizeAgentType(agentType)}`
const loadSession = (agentType, allowLegacy = false) => {
  const key = sessionStorageKey(agentType)
  const stored = localStorage.getItem(key)
  if (stored) return stored

  const legacySession = allowLegacy ? localStorage.getItem(legacySessionStorageKey) : null
  const created = legacySession || createSessionId()
  localStorage.setItem(key, created)
  return created
}

const initialAgentType = normalizeAgentType(route.meta.agentType)
const sessionId = ref(loadSession(initialAgentType, true))

const form = reactive({
  agentType: initialAgentType,
  message: ''
})
sessionStorage.setItem(agentTypeStorageKey, initialAgentType)
const health = reactive({ loading: false, state: 'checking', text: '正在检查 Agent 服务入口' })
let healthCheckVersion = 0
const sending = ref(false)
const hasResult = ref(false)
const requestFailed = ref(false)
const result = ref({})
const resultAgentType = ref(initialAgentType)
const activeRunId = ref(0)

const resultAgent = computed(
  () => agentOptions.find((option) => option.value === resultAgentType.value) || agentOptions[0]
)
const visiblePresets = computed(() => presets[form.agentType] || [])
const shortSessionId = computed(() => truncateMiddle(sessionId.value, 24))
const warnings = computed(() => (Array.isArray(result.value.warnings) ? result.value.warnings : []))
const allCards = computed(() => (Array.isArray(result.value.cards) ? result.value.cards : []))
const insightCards = computed(() => allCards.value.filter((card) => card?.type !== 'access_records'))
const pendingActions = computed(() =>
  Array.isArray(result.value.pendingActions) ? result.value.pendingActions : []
)
const traceEvents = computed(() =>
  Array.isArray(result.value.traceEvents) ? result.value.traceEvents : []
)
const accessRows = computed(() => {
  const accessCard = [...allCards.value].reverse().find((card) => card?.type === 'access_records')
  return Array.isArray(accessCard?.rows) ? accessCard.rows : []
})

function selectAgent(value) {
  if (sending.value) return
  const agentType = normalizeAgentType(value)
  if (agentType === form.agentType) return
  router.push(`/home/agent/${agentType}`)
}

function clearRunState() {
  form.message = ''
  result.value = {}
  hasResult.value = false
  requestFailed.value = false
}

function activateAgent(value) {
  const agentType = normalizeAgentType(value)
  activeRunId.value += 1
  sending.value = false
  form.agentType = agentType
  sessionId.value = loadSession(agentType)
  sessionStorage.setItem(agentTypeStorageKey, agentType)
  clearRunState()
}

function resetSession() {
  if (sending.value) return
  sessionId.value = createSessionId()
  localStorage.setItem(sessionStorageKey(form.agentType), sessionId.value)
  clearRunState()
  ElMessage.success('已创建新会话')
}

function applyPreset(prompt) {
  if (sending.value) return
  form.message = prompt
}

function unwrapResult(response) {
  const envelope = response?.data
  if (!envelope || typeof envelope !== 'object') return envelope
  const code = String(envelope.code ?? '0')
  if (code !== '0' && code !== '200') {
    throw new Error(envelope.message || '服务返回失败')
  }
  return envelope.data ?? envelope
}

function errorMessage(error) {
  const body = error?.response?.data
  if (typeof body === 'string' && body.trim()) return body
  if (body?.message) return body.message

  const status = Number(error?.response?.status)
  if (status === 401) return '登录状态已失效，请重新登录后再试'
  if (status === 403) return '当前账号无权访问请求的数据'
  if (status === 429) return '请求过于频繁，请稍后再试'
  if (status === 504 || error?.code === 'ECONNABORTED') return 'Agent 执行超时，请缩小查询范围后重试'
  if (!error?.response && error?.request) return '无法连接 Agent 服务，请检查服务状态'
  return error?.message || '请求失败，请稍后重试'
}

async function checkHealth() {
  const version = ++healthCheckVersion
  health.loading = true
  health.state = 'checking'
  health.text = '正在检查 Agent 服务入口'
  try {
    const data = unwrapResult(await API.agent.health()) || {}
    if (version !== healthCheckVersion) return
    health.state = 'ok'
    health.text = `Agent 服务可达 · ${data.status || 'UP'}`
  } catch (error) {
    if (version !== healthCheckVersion) return
    health.state = 'error'
    health.text = 'Agent 服务不可用'
  } finally {
    if (version === healthCheckVersion) health.loading = false
  }
}

async function sendMessage() {
  const message = form.message.trim()
  if (!message || sending.value) return
  const requestedAgentType = normalizeAgentType(form.agentType)
  const requestedSessionId = sessionId.value
  const runId = activeRunId.value + 1
  activeRunId.value = runId
  sending.value = true
  requestFailed.value = false
  try {
    const data = unwrapResult(
      await API.agent.chat({
        sessionId: requestedSessionId,
        agentType: requestedAgentType,
        message
      })
    )
    if (activeRunId.value !== runId || form.agentType !== requestedAgentType) return
    healthCheckVersion += 1
    health.loading = false
    health.state = 'ok'
    health.text = 'Agent 服务可达 · 最近调用成功'
    resultAgentType.value = requestedAgentType
    result.value = data && typeof data === 'object' ? data : { answer: String(data || '') }
    if (result.value.sessionId) {
      sessionId.value = result.value.sessionId
      localStorage.setItem(sessionStorageKey(requestedAgentType), result.value.sessionId)
    }
  } catch (error) {
    if (activeRunId.value !== runId || form.agentType !== requestedAgentType) return
    resultAgentType.value = requestedAgentType
    requestFailed.value = true
    result.value = {
      sessionId: requestedSessionId,
      answer: errorMessage(error),
      warnings: ['本次请求没有产生可用分析结果，请检查 Agent 配置或稍后重试。'],
      cards: [],
      pendingActions: [],
      toolCalls: [],
      dataSources: [],
      traceEvents: []
    }
  } finally {
    if (activeRunId.value === runId && form.agentType === requestedAgentType) {
      hasResult.value = true
      sending.value = false
    }
  }
}

function normalizedActionStatus(status) {
  const normalized = String(status || '').trim().toLowerCase()
  if (normalized === 'pending_confirmation') return 'pending'
  if (normalized === 'executed') return 'executed'
  if (normalized === 'not_fully_applied') return 'partial'
  return 'unknown'
}

function actionStatusText(status) {
  const labels = {
    pending: '待人工确认',
    executed: '已执行',
    partial: '部分未生效',
    unknown: '状态待核实'
  }
  return labels[normalizedActionStatus(status)]
}

function actionStatusTone(status) {
  return normalizedActionStatus(status)
}

function actionFallbackDescription(action) {
  const descriptions = {
    pending: '执行前需要人工确认。',
    executed: '策略已自动执行。',
    partial: '部分策略未成功执行，请检查详情。',
    unknown: '服务未返回可识别的动作状态，请检查详情。'
  }
  return descriptions[normalizedActionStatus(action?.status)]
}

function actionDetails(action) {
  return action?.arguments ?? action?.policies
}

function objectEntries(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? Object.entries(value) : []
}

function formatValue(value) {
  if (value === undefined || value === null || value === '') return '—'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return String(value)
}

function prettyKey(key) {
  const known = {
    pv: 'PV',
    uv: 'UV',
    uip: 'UIP',
    pvPerUv: 'PV / UV',
    uipShare: 'UIP / PV',
    totalPv: '累计 PV',
    latestPv: '最新 PV',
    changeRatio: '变化比例',
    peakHour: '峰值时段',
    peakHourPv: '峰值 PV',
    topIpShare: 'Top IP 占比',
    reasonCode: '原因代码',
    sourceTool: '来源工具'
  }
  return (
    known[key] ||
    String(key)
      .replace(/([A-Z])/g, ' $1')
      .replace(/_/g, ' ')
      .replace(/^./, (value) => value.toUpperCase())
  )
}

function displayMetric(key, value) {
  if (value === undefined || value === null || value === '') return '—'
  if (typeof value === 'number' && /(share|ratio)$/i.test(key) && value >= 0 && value <= 1) {
    return `${(value * 100).toFixed(1)}%`
  }
  return formatValue(value)
}

function cardTone(card) {
  const severity = String(card?.severity || '').toLowerCase()
  if (severity === 'error' || card?.type === 'traffic_anomaly') return 'danger'
  if (severity === 'warning' || card?.type === 'tool_warning') return 'warning'
  if (severity === 'info' || card?.type === 'performance_insight') return 'info'
  return 'default'
}

function severityText(card) {
  const labels = { danger: '风险', warning: '注意', info: '洞察', default: '数据' }
  return labels[cardTone(card)]
}

function traceTone(status) {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'success' || normalized === 'succeeded') return 'success'
  if (normalized === 'failed' || normalized === 'error') return 'failed'
  return 'neutral'
}

function truncateMiddle(value, maxLength) {
  const text = String(value || '')
  if (text.length <= maxLength) return text
  const side = Math.floor((maxLength - 1) / 2)
  return `${text.slice(0, side)}…${text.slice(-side)}`
}

async function copyText(value, label) {
  try {
    await navigator.clipboard.writeText(String(value))
    ElMessage.success(`${label} 已复制`)
  } catch {
    ElMessage.error(`${label} 复制失败`)
  }
}

watch(
  () => route.meta.agentType,
  (agentType) => {
    const normalized = normalizeAgentType(agentType)
    if (normalized !== form.agentType) activateAgent(normalized)
  }
)

onMounted(checkHealth)
</script>

<style lang="scss" scoped>
.agent-page {
  --ink: #18233a;
  --muted: #68758e;
  --line: #dfe5ef;
  --blue: #2368e8;
  width: min(1500px, calc(100% - 48px));
  margin: 0 auto;
  padding: 30px 0 48px;
  color: var(--ink);
}

.surface {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(30, 49, 84, 0.055);
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.eyebrow,
.panel-kicker,
.empty-kicker {
  color: #2368e8;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.09em;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 7px;
}

.eyebrow :deep(svg) {
  width: 14px;
}

.page-heading h1 {
  color: #18233a;
  font-size: 27px;
  font-weight: 700;
  line-height: 36px;
}

.page-heading p {
  margin-top: 6px;
  color: var(--muted);
  font-size: 14px;
  line-height: 22px;
}

.health-status {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  border: 1px solid #dbe2ed;
  border-radius: 8px;
  background: #ffffff;
  color: #526079;
  font: inherit;
  font-size: 12px;
}

.health-status:hover {
  border-color: #a9bddd;
  color: #235fc8;
}

.health-status > :deep(svg) {
  width: 14px;
}

.health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f2a93b;
  box-shadow: 0 0 0 3px #fff3dc;
}

.health-dot.ok {
  background: #20a164;
  box-shadow: 0 0 0 3px #dcf5e9;
}

.health-dot.error {
  background: #dc4c4c;
  box-shadow: 0 0 0 3px #fee7e7;
}

.workspace-grid {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.composer-panel {
  position: sticky;
  top: 20px;
  padding: 20px;
}

.panel-heading,
.answer-head,
.section-heading,
.insight-head,
.trace-title,
.prompt-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.panel-heading h2,
.section-heading h2,
.answer-title h2 {
  margin-top: 2px;
  color: #1e2a42;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.icon-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid #dce3ee;
  border-radius: 8px;
  background: #f8fafe;
  color: #60708c;
}

.icon-button:hover {
  border-color: #a8bee5;
  color: var(--blue);
}

.icon-button:disabled,
.agent-option:disabled,
.preset-button:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.icon-button :deep(svg) {
  width: 16px;
}

.session-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 15px 0 18px;
  padding: 9px 11px;
  border: 1px solid #e7ebf2;
  border-radius: 7px;
  background: #f8fafd;
  color: #7b879b;
  font-size: 11px;
}

.session-line code,
.trace-chip code,
.action-meta code {
  color: #4e5f7a;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.field-label {
  display: block;
  margin-bottom: 8px;
  color: #43516a;
  font-size: 12px;
  font-weight: 700;
}

.agent-picker {
  display: grid;
  gap: 8px;
  margin-bottom: 18px;
}

.agent-option {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border: 1px solid #e0e5ee;
  border-radius: 9px;
  background: #ffffff;
  color: #334058;
  text-align: left;
}

.agent-option:hover:not(:disabled) {
  border-color: #b7c6df;
  background: #fbfcff;
}

.agent-option.active {
  border-color: #78a2ee;
  background: #f4f8ff;
  box-shadow: inset 0 0 0 1px rgba(35, 104, 232, 0.08);
}

.agent-option strong,
.agent-option small {
  display: block;
}

.agent-option strong {
  font-size: 13px;
  line-height: 19px;
}

.agent-option small {
  margin-top: 2px;
  color: #7b879b;
  font-size: 11px;
  line-height: 16px;
}

.agent-icon,
.answer-icon,
.section-icon {
  display: grid;
  place-items: center;
  border-radius: 8px;
}

.agent-icon {
  width: 36px;
  height: 36px;
}

.agent-icon.blue {
  background: #e5efff;
  color: #2368e8;
}

.agent-icon.violet {
  background: #eee9ff;
  color: #7052d5;
}

.agent-icon :deep(svg) {
  width: 18px;
}

.selected-check {
  width: 17px;
  color: #2368e8;
}

.prompt-heading {
  margin-bottom: 8px;
}

.prompt-heading .field-label {
  margin: 0;
}

.prompt-heading > span {
  color: #95a0b2;
  font-size: 11px;
}

.composer-panel :deep(.el-textarea__inner) {
  min-height: 150px !important;
  padding: 12px 13px;
  border: 0;
  border-radius: 8px;
  box-shadow: 0 0 0 1px #dce3ee inset;
  color: #25314a;
  font-family: inherit;
  font-size: 13px;
  line-height: 21px;
}

.composer-panel :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px #5d8fe8 inset;
}

.preset-block {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 11px;
}

.preset-label {
  width: 100%;
  margin-bottom: 1px;
  color: #8994a8;
  font-size: 11px;
}

.preset-button {
  padding: 5px 8px;
  border: 1px solid #e1e6ef;
  border-radius: 6px;
  background: #f8fafd;
  color: #5f6d85;
  font: inherit;
  font-size: 11px;
}

.preset-button:hover:not(:disabled) {
  border-color: #b8c9e7;
  background: #f1f6ff;
  color: #235fc8;
}

.send-button {
  display: flex;
  width: 100%;
  min-height: 42px;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 17px;
  border: 1px solid #2368e8;
  border-radius: 8px;
  background: #2368e8;
  color: #ffffff;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  box-shadow: 0 7px 15px rgba(35, 104, 232, 0.19);
}

.send-button:hover:not(:disabled) {
  border-color: #195acb;
  background: #195acb;
}

.send-button:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.send-button :deep(svg) {
  width: 16px;
}

.send-button kbd {
  position: absolute;
  margin-left: 270px;
  color: rgba(255, 255, 255, 0.68);
  font-family: inherit;
  font-size: 10px;
  font-weight: 500;
}

.auth-note {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 10px;
  color: #8a95a8;
  font-size: 10px;
  line-height: 16px;
}

.auth-note :deep(svg) {
  width: 12px;
  flex: 0 0 auto;
}

.result-column {
  display: grid;
  min-width: 0;
  gap: 14px;
}

.empty-result {
  display: grid;
  min-height: 530px;
  place-items: center;
  align-content: center;
  padding: 54px;
  text-align: center;
}

.empty-illustration {
  display: grid;
  width: 72px;
  height: 72px;
  place-items: center;
  margin-bottom: 20px;
  border: 1px solid #cad9f4;
  border-radius: 18px;
  background: #edf4ff;
  color: #2368e8;
  box-shadow: 0 10px 24px rgba(35, 104, 232, 0.11);
}

.empty-illustration :deep(svg) {
  width: 32px;
}

.empty-result h2,
.running-result h2 {
  margin-top: 5px;
  color: #23304a;
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.empty-result > p,
.running-result p {
  max-width: 480px;
  margin-top: 8px;
  color: #77839a;
  font-size: 13px;
  line-height: 21px;
}

.capability-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 18px;
  margin-top: 26px;
  color: #62718b;
  font-size: 11px;
}

.capability-list span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.capability-list :deep(svg) {
  width: 14px;
  color: #4e7ed5;
}

.running-result {
  display: flex;
  min-height: 210px;
  align-items: center;
  gap: 24px;
  padding: 36px;
}

.running-orbit {
  display: grid;
  width: 58px;
  height: 58px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid #bcd0f2;
  border-radius: 50%;
  background: #edf4ff;
  color: #2368e8;
  animation: pulse-ring 1.6s ease-in-out infinite;
}

.running-orbit :deep(svg) {
  width: 25px;
}

.answer-card {
  overflow: hidden;
  border-top: 3px solid #2368e8;
}

.answer-card.failed {
  border-top-color: #d84b4b;
}

.answer-head {
  padding: 17px 20px 14px;
  border-bottom: 1px solid #e8ecf3;
}

.answer-title,
.section-heading > div {
  display: flex;
  align-items: center;
  gap: 11px;
}

.answer-icon {
  width: 38px;
  height: 38px;
  background: #e8f1ff;
  color: #2368e8;
}

.answer-icon :deep(svg) {
  width: 19px;
}

.trace-chip {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 8px 6px 10px;
  border: 1px solid #e0e5ed;
  border-radius: 7px;
  background: #f8fafd;
  color: #8a95a8;
  font-size: 10px;
}

.trace-chip button {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 5px;
  background: #e9eef7;
  color: #5d6d86;
}

.trace-chip button:hover {
  background: #dfe9fa;
  color: #2368e8;
}

.trace-chip button :deep(svg) {
  width: 13px;
}

.answer-body {
  min-height: 92px;
  padding: 19px 22px;
  color: #27334a;
  font-size: 14px;
  line-height: 24px;
  white-space: pre-wrap;
  word-break: break-word;
}

.answer-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 20px;
  border-top: 1px solid #edf0f5;
  background: #fafbfc;
  color: #8994a7;
  font-size: 10px;
}

.answer-meta span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.answer-meta :deep(svg) {
  width: 12px;
}

.warning-stack {
  display: grid;
  gap: 7px;
}

.warning-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 11px 13px;
  border: 1px solid #f0cea1;
  border-radius: 8px;
  background: #fff8ec;
  color: #8b5518;
  font-size: 12px;
  line-height: 19px;
}

.warning-item :deep(svg) {
  width: 15px;
  flex: 0 0 auto;
  margin-top: 2px;
  color: #d48a24;
}

.insight-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.insight-card {
  padding: 17px;
  border-left: 3px solid #4c7fd9;
}

.insight-card.warning {
  border-left-color: #df9632;
}

.insight-card.danger {
  border-left-color: #d84b4b;
}

.insight-card.info {
  border-left-color: #6f56d9;
}

.card-type {
  color: #8894a8;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.insight-head h3 {
  margin-top: 3px;
  color: #24314a;
  font-size: 14px;
  font-weight: 700;
  line-height: 20px;
}

.severity-badge,
.count-badge {
  padding: 4px 7px;
  border-radius: 6px;
  background: #edf3fc;
  color: #496b9f;
  font-size: 10px;
  font-weight: 700;
}

.warning .severity-badge {
  background: #fff0dc;
  color: #a7630b;
}

.danger .severity-badge {
  background: #fdeaea;
  color: #b83e3e;
}

.info .severity-badge {
  background: #eeeaff;
  color: #654ac1;
}

.card-message {
  margin-top: 10px;
  color: #66748d;
  font-size: 12px;
  line-height: 19px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin-top: 13px;
}

.metric-grid > div {
  min-width: 0;
  padding: 9px 10px;
  border-radius: 7px;
  background: #f5f8fc;
}

.metric-grid dt {
  overflow: hidden;
  color: #8793a7;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-grid dd {
  margin-top: 3px;
  overflow: hidden;
  color: #273550;
  font-size: 15px;
  font-weight: 700;
  line-height: 21px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-block {
  margin-top: 13px;
  padding-top: 11px;
  border-top: 1px solid #edf0f5;
}

.detail-block > span {
  color: #6e7b91;
  font-size: 11px;
  font-weight: 700;
}

.detail-block dl,
.trace-summary {
  display: grid;
  grid-template-columns: minmax(90px, auto) minmax(0, 1fr);
  gap: 5px 12px;
  margin-top: 7px;
  font-size: 11px;
  line-height: 17px;
}

.detail-block dt,
.trace-summary dt {
  color: #8a96a9;
}

.detail-block dd,
.trace-summary dd {
  min-width: 0;
  color: #44516a;
  text-align: right;
  white-space: pre-wrap;
  word-break: break-word;
}

.section-card {
  padding: 18px 20px;
}

.section-heading {
  margin-bottom: 15px;
}

.section-icon {
  width: 36px;
  height: 36px;
}

.section-icon :deep(svg) {
  width: 18px;
}

.section-icon.amber {
  background: #fff0dc;
  color: #cc7b12;
}

.section-icon.blue {
  background: #e7f0ff;
  color: #2368e8;
}

.section-icon.violet {
  background: #eeeaff;
  color: #6b50cb;
}

.action-row {
  padding: 13px 14px;
  border: 1px solid #eadfcf;
  border-radius: 8px;
  background: #fffcf7;
}

.action-row.executed {
  border-color: #b9dfcb;
  background: #f5fcf8;
}

.action-row.partial {
  border-color: #efb8b8;
  background: #fff7f7;
}

.action-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.action-summary > div {
  min-width: 0;
}

.action-status {
  flex: 0 0 auto;
  padding: 4px 7px;
  border-radius: 6px;
  background: #fff0dc;
  color: #9b5e10;
  font-size: 10px;
  font-weight: 700;
}

.action-status.executed {
  background: #e4f6ec;
  color: #217a4c;
}

.action-status.partial {
  background: #fde7e7;
  color: #b43e3e;
}

.action-status.unknown {
  background: #edf1f6;
  color: #607089;
}

.action-row + .action-row {
  margin-top: 8px;
}

.action-row h3 {
  color: #3e3427;
  font-size: 13px;
  font-weight: 700;
  line-height: 19px;
}

.action-row p {
  margin-top: 3px;
  color: #7b6d5d;
  font-size: 11px;
  line-height: 17px;
}

.action-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 8px;
  color: #8d7c66;
  font-size: 10px;
}

.action-row pre,
.debug-grid pre {
  overflow: auto;
  margin-top: 9px;
  border-radius: 7px;
  background: #182237;
  color: #d7e1f2;
  font: 11px/18px ui-monospace, SFMono-Regular, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.action-row pre {
  max-height: 180px;
  padding: 10px;
}

.trace-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.trace-list li {
  position: relative;
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 11px;
}

.trace-list li:not(:last-child) {
  padding-bottom: 15px;
}

.trace-list li:not(:last-child)::before {
  position: absolute;
  top: 25px;
  bottom: 0;
  left: 12px;
  width: 1px;
  background: #dfe5ef;
  content: '';
}

.trace-marker {
  z-index: 1;
  display: grid;
  width: 25px;
  height: 25px;
  place-items: center;
  border: 1px solid #d8e0eb;
  border-radius: 50%;
  background: #f7f9fc;
  color: #7f8ba0;
}

.trace-marker.success {
  border-color: #9cd5b9;
  background: #e9f8f0;
  color: #218453;
}

.trace-marker.failed {
  border-color: #efaaaa;
  background: #fff0f0;
  color: #c74242;
}

.trace-marker :deep(svg) {
  width: 13px;
}

.trace-content {
  min-width: 0;
  padding: 2px 0 10px;
}

.trace-title strong {
  color: #34415a;
  font-size: 12px;
}

.trace-title > span {
  color: #7c899e;
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
}

.trace-title > span.success {
  color: #238252;
}

.trace-title > span.failed,
.trace-error {
  color: #c64444;
}

.trace-meta {
  display: flex;
  gap: 12px;
  margin-top: 2px;
  color: #929caf;
  font-size: 10px;
}

.trace-error {
  margin-top: 6px;
  font-size: 11px;
  line-height: 17px;
}

.record-table {
  width: 100%;
  border-top: 1px solid #edf0f5;
}

.record-table :deep(th.el-table__cell) {
  background: #f7f9fc;
  color: #6d7a90;
  font-size: 11px;
  font-weight: 700;
}

.record-table :deep(td.el-table__cell) {
  color: #3f4d65;
  font-size: 11px;
}

.debug-collapse {
  overflow: hidden;
  border: 1px solid #dfe5ef;
  border-radius: 10px;
  background: #ffffff;
}

.debug-collapse :deep(.el-collapse-item__header) {
  height: 48px;
  padding: 0 18px;
  border-bottom: 0;
  color: #56657e;
  font-size: 12px;
}

.debug-collapse :deep(.el-collapse-item__wrap) {
  border-top: 1px solid #edf0f5;
  border-bottom: 0;
}

.debug-collapse :deep(.el-collapse-item__content) {
  padding: 16px;
}

.debug-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-weight: 700;
}

.debug-title :deep(svg) {
  width: 15px;
}

.debug-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.debug-grid > div > span {
  color: #7b879b;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.debug-grid pre {
  max-height: 300px;
  padding: 12px;
}

.debug-grid .full-response {
  grid-column: 1 / -1;
}

.spinning {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes pulse-ring {
  50% {
    box-shadow: 0 0 0 8px rgba(35, 104, 232, 0.08);
  }
}

@media (max-width: 1180px) {
  .agent-page {
    width: calc(100% - 32px);
  }

  .workspace-grid {
    grid-template-columns: 330px minmax(0, 1fr);
  }

  .insight-grid {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinning,
  .running-orbit {
    animation: none;
  }
}
</style>
