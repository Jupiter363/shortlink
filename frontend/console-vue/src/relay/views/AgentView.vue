<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { agentApi } from '../api/agentRisk.js'
import {
  compileMessage,
  errorMessage,
  newAgentSession,
  normalizeAgentResult,
  pretty
} from '../domain/agentModel.js'
import './agent-risk.css'

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
watch(
  [agentType, () => relay.state.agentSessions],
  () => {
    if (!relay.state.session?.loggedIn) return
    relay.state.agentSessions ||= {}
    relay.state.agentSessions[agentType.value] ||= newAgentSession(agentType.value)
    session.value = relay.state.agentSessions[agentType.value]
  },
  { immediate: true }
)
const running = computed(() => session.value.runState === 'RUNNING')
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
const health = ref({ state: 'UNKNOWN', checkedAt: '', error: '' })
let healthController
let runController
let activeRun
let disposed = false

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
  session.value = newAgentSession(agentType.value)
  relay.state.agentSessions[agentType.value] = session.value
}

async function run() {
  if (relay.state.agentBusy || running.value) return
  const entry = session.value
  if (!compiled.value || compiled.value.length > 2000) {
    entry.error = !compiled.value ? '请输入问题。' : '问题与分析范围合计不能超过 2000 字。'
    return
  }
  if (entry.groupId && !selectedGroup.value) {
    entry.error = '所选分组已不可用，请重新选择分析范围。'
    return
  }
  const type = agentType.value
  const id = crypto.randomUUID()
  const originalSessionId = entry.id
  const message = compiled.value
  const controller = new AbortController()
  activeRun = { id, entry }
  runController = controller
  entry.runId = id
  entry.runState = 'RUNNING'
  entry.error = ''
  relay.state.agentBusy = true
  try {
    const raw = await agentApi.chat(
      { sessionId: originalSessionId, agentType: type, message },
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
    if (entry.result)
      entry.history.push({
        result: entry.result,
        message: entry.lastMessage,
        completedAt: entry.completedAt
      })
    entry.result = response
    entry.lastMessage = message
    entry.completedAt = new Date().toLocaleString('zh-CN')
    if (typeof response.sessionId === 'string' && response.sessionId) entry.id = response.sessionId
    entry.runState = 'SUCCESS'
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
  }
)
onMounted(checkHealth)
onBeforeUnmount(() => {
  disposed = true
  healthController?.abort()
  cancelWaiting()
})
</script>

<template>
  <section class="ar-view" :aria-labelledby="`agent-heading-${agentType}`">
    <header class="ar-page-head">
      <div>
        <p class="ar-kicker">
          JUPITER RELAY / {{ agentType === 'security-risk' ? '守护台' : '领航台' }}
        </p>
        <h1 :id="`agent-heading-${agentType}`">{{ copy.title }}</h1>
        <p>{{ copy.intro }}</p>
      </div>
      <div class="ar-actions">
        <RBadge
          :tone="
            health.state === 'REACHABLE'
              ? 'success'
              : health.state === 'ERROR'
                ? 'danger'
                : 'unknown'
          "
        >
          {{
            {
              REACHABLE: 'HTTP 入口可达',
              CHECKING: '检查入口中',
              ERROR: '入口检查失败',
              UNKNOWN: '入口状态未知'
            }[health.state]
          }}
        </RBadge>
        <RButton kind="text" :disabled="health.state === 'CHECKING' || running" @click="checkHealth"
          >检查入口</RButton
        >
        <RButton kind="secondary" :disabled="relay.state.agentBusy" @click="startNewSession"
          >新会话</RButton
        >
      </div>
    </header>
    <p class="ar-notice">
      {{ health.checkedAt ? `${health.checkedAt} 检查 · ` : '' }}入口状态只表示 HTTP 可达性，不表示
      Graph、工具或模型分别就绪。{{ health.error }}
    </p>
    <div class="ar-agent-layout">
      <aside class="ar-panel ar-composer">
        <div class="ar-identity">
          <RRobot :role="copy.role" :expression="running ? 'waiting' : 'neutral'" :size="112" />
          <div>
            <RBadge tone="info">{{ copy.name }}</RBadge>
            <p>两个 Agent 的会话独立保留</p>
          </div>
        </div>
        <RSelect
          v-model="session.groupId"
          label="分析范围"
          :options="scopeOptions"
          :disabled="running"
        />
        <p class="ar-caption">范围会写入问题文本；服务端按当前登录账户校验数据权限。</p>
        <RTextarea
          v-model="session.prompt"
          label="你想了解什么？"
          :placeholder="copy.placeholder"
          :maxlength="2000"
          :rows="7"
          :count="false"
          :disabled="running"
        />
        <p class="ar-count" :class="{ 'ar-text-danger': compiled.length > 2000 }">
          含范围 {{ compiled.length }} / 2000 字
        </p>
        <div class="ar-presets">
          <RButton
            v-for="preset in copy.presets"
            :key="preset"
            kind="text"
            :disabled="running"
            @click="session.prompt = preset"
            >{{ preset }}</RButton
          >
        </div>
        <p
          v-if="session.error && session.runState !== 'ERROR'"
          class="ar-alert ar-alert-danger"
          role="alert"
        >
          {{ session.error }}
        </p>
        <RButton
          block
          :loading="running"
          loading-text="等待完整响应"
          :disabled="compiled.length > 2000 || (!running && relay.state.agentBusy)"
          @click="run"
          >开始分析</RButton
        >
        <p class="ar-caption">同步请求最多等待 60 秒；完成后展示回答与真实执行轨迹。</p>
        <p v-if="agentType === 'security-risk'" class="ar-caption">
          安全 Agent 可能依据服务端策略自动限流；人工审核和现有策略管理在风险中心进行。
        </p>
        <details class="ar-details">
          <summary>当前会话</summary>
          <code class="ar-break">{{ session.id }}</code>
        </details>
      </aside>
      <main class="ar-output" aria-live="polite" :aria-busy="running">
        <section v-if="session.runState === 'READY'" class="ar-panel ar-empty">
          <RRobot :role="copy.role" :size="150" /><RBadge tone="unknown">等待你的问题</RBadge>
          <h2>让每个判断都有数据依据</h2>
          <p>输入问题并选择范围。证据不完整时，保留缺口与不确定项。</p>
        </section>
        <section v-else-if="running" class="ar-panel ar-empty" role="status">
          <RRobot :role="copy.role" expression="waiting" :size="150" /><RBadge tone="info"
            >RUNNING</RBadge
          >
          <h2>正在等待完整分析结果</h2>
          <p>请求期间暂不能切换 Agent 或新建会话；工具与 Graph 轨迹将在响应完成后展示。</p>
        </section>
        <section v-else-if="session.runState === 'ERROR'" class="ar-panel ar-empty" role="alert">
          <RRobot :role="copy.role" expression="recovery" :size="126" />
          <h2>本次分析未收到完整结果</h2>
          <p>{{ session.error }}</p>
          <RButton
            v-if="agentType === 'security-risk'"
            kind="secondary"
            @click="relay.go('/home/risk-center')"
            >先到风险中心核验</RButton
          >
          <p class="ar-caption">问题已保留；确认后可使用左侧“开始分析”重新提交。</p>
        </section>
        <template v-if="result && !running">
          <section class="ar-panel ar-answer">
            <header class="ar-section-head">
              <div>
                <RBadge tone="info">{{
                  session.runState === 'ERROR' ? '上一次完整结果' : '完整响应'
                }}</RBadge>
                <h2>分析回答</h2>
                <small>{{ session.completedAt }}</small>
              </div>
              <RRobot :role="copy.role" expression="success" :size="80" />
            </header>
            <p class="ar-answer-text">{{ result.answer || '本次响应没有回答正文。' }}</p>
            <p
              v-for="(warning, index) in result.warnings"
              :key="index"
              class="ar-alert ar-alert-warning"
            >
              {{ warning }}
            </p>
          </section>
          <section class="ar-panel">
            <h2>回答证据</h2>
            <p v-if="!result.cards.length" class="ar-muted">
              本次响应未返回证据卡片，不能据此推断数据已完整。
            </p>
            <article v-for="card in result.cards" :key="card.key" class="ar-evidence-card">
              <h3>{{ card.title }}</h3>
              <p v-if="card.message">{{ card.message }}</p>
              <p v-if="card.summary && typeof card.summary === 'string'">{{ card.summary }}</p>
              <div v-if="card.type === 'access_records'" class="ar-table-scroll">
                <table>
                  <caption>
                    脱敏访问记录
                  </caption>
                  <thead>
                    <tr>
                      <th>时间</th>
                      <th>来源</th>
                      <th>地域 / 运营商</th>
                      <th>设备 / 系统</th>
                      <th>访客</th>
                      <th>响应</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, index) in card.rows" :key="index">
                      <td>{{ row.createTime }}</td>
                      <td>{{ row.ip }}</td>
                      <td>
                        {{ row.locale }}<small>{{ row.network }}</small>
                      </td>
                      <td>
                        {{ row.device }}<small>{{ row.os }} / {{ row.browser }}</small>
                      </td>
                      <td>{{ row.visitorType }}</td>
                      <td>
                        {{ row.status }}<small>{{ row.eventType }}</small>
                      </td>
                    </tr>
                    <tr v-if="!card.rows.length">
                      <td colspan="6">未返回访问记录</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <template v-else>
                <pre v-if="card.metrics" class="ar-json">{{ pretty(card.metrics) }}</pre>
                <pre v-if="card.evidence" class="ar-json">{{ pretty(card.evidence) }}</pre>
                <pre v-if="card.reasonCodes" class="ar-json">{{ pretty(card.reasonCodes) }}</pre>
                <details class="ar-details">
                  <summary>完整证据卡片</summary>
                  <pre class="ar-json">{{ pretty(card) }}</pre>
                </details></template
              >
            </article>
            <details class="ar-details">
              <summary>数据来源（{{ result.dataSources.length }}）</summary>
              <pre class="ar-json">{{ pretty(result.dataSources) }}</pre>
            </details>
          </section>
          <section v-if="result.pendingActions.length" class="ar-panel">
            <h2>动作与待处理事项</h2>
            <p class="ar-caption">
              以下为 Agent
              返回的实际结果。自动限流、人工审核与现有策略停用分别核验；已提交不代表传播完成。
            </p>
            <article
              v-for="(action, index) in result.pendingActions"
              :key="index"
              class="ar-evidence-card"
            >
              <h3>{{ action.title || action.type }}</h3>
              <RBadge :tone="action.status === 'executed' ? 'info' : 'warning'">{{
                {
                  executed: '命令已执行 · 传播待核实',
                  pending_confirmation: '待人工核实',
                  not_fully_applied: '未完全执行'
                }[action.status] || '结果未知'
              }}</RBadge>
              <pre class="ar-json">{{ pretty(action) }}</pre>
            </article>
            <RButton kind="secondary" @click="relay.go('/home/risk-center')"
              >到风险中心核验</RButton
            >
          </section>
          <section class="ar-panel">
            <h2>Completed Tool Timeline</h2>
            <p class="ar-caption">响应返回的工具记录，保留失败和未提供的状态。</p>
            <p v-if="!result.toolCalls.length" class="ar-muted">本次响应未提供工具执行记录。</p>
            <ol class="ar-timeline">
              <li v-for="(tool, index) in result.toolCalls" :key="tool.key">
                <span class="ar-step">{{ index + 1 }}</span>
                <div>
                  <strong>{{ tool.label }}</strong
                  ><small v-if="tool.durationMs != null">{{ tool.durationMs }} ms</small>
                  <details class="ar-details">
                    <summary>执行详情</summary>
                    <pre class="ar-json">{{ pretty(tool) }}</pre>
                  </details>
                </div>
                <RBadge :tone="tool.tone">{{ tool.outcome }}</RBadge>
              </li>
            </ol>
            <h3>Graph Trace</h3>
            <p v-if="!result.traceEvents.length" class="ar-muted">
              本次响应未提供 Graph 节点轨迹。
            </p>
            <div class="ar-graph">
              <article v-for="node in result.traceEvents" :key="node.key">
                <strong>{{ node.label }}</strong
                ><RBadge tone="unknown">{{ node.status }}</RBadge
                ><small v-if="node.timing?.durationMs != null"
                  >{{ node.timing.durationMs }} ms</small
                >
                <details>
                  <summary>节点详情</summary>
                  <pre class="ar-json">{{ pretty(node) }}</pre>
                </details>
              </article>
            </div>
            <details
              class="ar-details"
              :open="session.debugOpen"
              @toggle="session.debugOpen = $event.currentTarget.open"
            >
              <summary>脱敏调试数据</summary>
              <pre class="ar-json">{{ pretty(result) }}</pre>
            </details>
          </section>
        </template>
        <section v-if="session.history.length && !running" class="ar-panel">
          <h2>本会话历史回答</h2>
          <details v-for="(entry, index) in session.history" :key="index" class="ar-details">
            <summary>{{ entry.completedAt }} · 第 {{ index + 1 }} 次回答</summary>
            <p class="ar-answer-text">{{ entry.result.answer }}</p>
            <pre class="ar-json">{{ pretty(entry.result) }}</pre>
          </details>
        </section>
      </main>
    </div>
  </section>
</template>
