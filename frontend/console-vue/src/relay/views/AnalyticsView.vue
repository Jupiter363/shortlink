<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { analyticsApi } from '../api/analytics.js'
import {
  ANALYTICS_DIMENSIONS,
  AnalyticsContractError,
  classifyAnalyticsError,
  defaultShanghaiDateRange,
  dimensionView,
  formatCount,
  formatRatio,
  formatShanghaiDate,
  formatShanghaiTime,
  resolveAnalyticsScope,
  validateAccessContinuation,
  validateAnalyticsRange
} from '../domain/analytics.js'
import './analytics.css'

const relay = inject('relay')
if (!relay) throw new Error('AnalyticsView requires relay provider')

const state = relay.state
const defaultRange = defaultShanghaiDateRange()
const remembered = state.analyticsScope
const scopeType = ref(remembered?.type === 'link' ? 'link' : 'group')
const scopeId = ref(
  String(remembered?.id || remembered?.gid || state.groupId || state.groups?.[0]?.id || '')
)
const startDate = ref(defaultRange.startDate)
const endDate = ref(defaultRange.endDate)
const today = defaultRange.endDate
const metricKey = ref('pv')
const viewMode = ref('chart')
const dimensionKey = ref('country')

const metricsStatus = ref('idle')
const metricsModel = ref(null)
const metricsError = ref(null)
const appliedContext = ref(null)
let metricsController
let metricsSequence = 0

const recordsOpen = ref(false)
const recordStatus = ref('idle')
const recordPages = ref([])
const recordIndex = ref(0)
const recordContext = ref(null)
const recordError = ref(null)
const recordRetry = ref('first')
const recordPaging = ref(false)
let recordController
let recordSequence = 0

const groupOptions = computed(() =>
  (state.groups || []).map((group) => ({
    value: String(group.id),
    label: `${group.name || '未命名分组'}${Number.isSafeInteger(group.count) ? ` · ${group.count} 条` : ''}`
  }))
)

const availableLinks = computed(() => {
  const rows = (state.links || []).filter((link) => !link.recycled)
  if (
    remembered?.type === 'link' &&
    !rows.some((link) => String(link.id) === String(remembered.id))
  )
    rows.unshift(remembered)
  return rows
})

const linkOptions = computed(() =>
  availableLinks.value.map((link) => ({
    value: String(link.id || link.fullShortUrl),
    label: `${link.title || link.code || '未命名短链'} · ${link.code || link.fullShortUrl}`
  }))
)

const scopeOptions = computed(() =>
  scopeType.value === 'group' ? groupOptions.value : linkOptions.value
)
const scopeHint = computed(() =>
  scopeType.value === 'group'
    ? '分组统计最多覆盖 500 条已授权短链。'
    : '短链选项来自工作区当前页及最近选中的统计范围。'
)
const rangeCheck = computed(() => validateAnalyticsRange(startDate.value, endDate.value))
const inputSignature = computed(
  () => `${scopeType.value}|${scopeId.value}|${startDate.value}|${endDate.value}`
)
const dirty = computed(
  () => Boolean(appliedContext.value) && inputSignature.value !== appliedContext.value.signature
)
const currentPage = computed(() => recordPages.value[recordIndex.value] || null)
const currentDimension = computed(() =>
  metricsModel.value ? dimensionView(metricsModel.value, dimensionKey.value) : null
)
const trend = computed(() => metricsModel.value?.summary.daily || [])
const selectedMetricLabel = computed(() => ({ pv: 'PV', uv: 'UV', uip: 'UIP' })[metricKey.value])

const chartModel = computed(() => {
  const rows = trend.value
  if (!rows.length) return { points: '', dots: [], max: 0, desc: '后端没有返回按日趋势。' }
  const values = rows.map((row) => row[metricKey.value])
  const max = Math.max(...values, 0)
  const scale = max || 1
  const dots = rows.map((row, index) => {
    const x = rows.length === 1 ? 380 : 56 + index * (648 / (rows.length - 1))
    const y = 196 - (row[metricKey.value] / scale) * 156
    return { x, y, value: row[metricKey.value], date: row.date }
  })
  return {
    points: dots.map((point) => `${point.x},${point.y}`).join(' '),
    dots,
    max,
    desc: `${rows[0].date} 至 ${rows.at(-1).date} 的 ${selectedMetricLabel.value} 趋势，最大值 ${max}。`
  }
})

function qualityTone(status) {
  if (['AVAILABLE', 'COMPLETE', 'NORMAL'].includes(status)) return 'success'
  if (['PARTIAL', 'DEGRADED'].includes(status)) return 'warning'
  return 'unknown'
}

function qualityLabel(status) {
  return (
    {
      AVAILABLE: '可用',
      EMPTY: '无数据',
      PARTIAL: '部分完整',
      UNKNOWN: '未知',
      COMPLETE: '完整',
      NORMAL: '采集正常',
      DEGRADED: '采集降级'
    }[status] ||
    status ||
    '未知'
  )
}

function approximationLabel(key) {
  const type = metricsModel.value?.meta.approximation?.[key]?.type
  if (type === 'EXACT') return '精确事件去重'
  if (type === 'APPROXIMATE') return '近似统计'
  return '算法信息未提供'
}

function chooseDefaultScope() {
  const values = scopeOptions.value
  if (!values.some((option) => option.value === scopeId.value))
    scopeId.value = values[0]?.value || ''
}

watch(scopeType, chooseDefaultScope)

function inputContext() {
  if (!rangeCheck.value.ok)
    throw new AnalyticsContractError(rangeCheck.value.code, rangeCheck.value.message)
  const scope = resolveAnalyticsScope(state, scopeType.value, scopeId.value)
  return {
    scope,
    range: { startDate: startDate.value, endDate: endDate.value },
    signature: inputSignature.value,
    label: scope.label
  }
}

function rememberScope(scope) {
  state.analyticsScope =
    scope.type === 'link'
      ? {
          type: 'link',
          id: scope.id,
          gid: scope.gid,
          groupId: scope.gid,
          code: scope.code,
          fullShortUrl: scope.fullShortUrl,
          title: scope.label
        }
      : { type: 'group', id: scope.id, gid: scope.gid, groupId: scope.gid }
}

async function loadMetrics() {
  let context
  try {
    context = inputContext()
  } catch (error) {
    metricsModel.value = null
    metricsError.value = classifyAnalyticsError(error)
    metricsStatus.value = 'error'
    return
  }
  appliedContext.value = context
  rememberScope(context.scope)
  metricsController?.abort()
  const controller = new AbortController()
  metricsController = controller
  const sequence = ++metricsSequence
  metricsStatus.value = 'loading'
  metricsModel.value = null
  metricsError.value = null
  if (context.scope.type === 'group' && context.scope.count === 0) {
    metricsStatus.value = 'empty'
    return
  }
  try {
    const result = await analyticsApi.queryMetrics(context.scope, context.range, {
      signal: controller.signal
    })
    if (sequence !== metricsSequence || controller.signal.aborted) return
    metricsModel.value = result
    metricsStatus.value = result.empty ? 'empty' : 'ready'
  } catch (error) {
    if (sequence !== metricsSequence || controller.signal.aborted) return
    const presentation = classifyAnalyticsError(error)
    if (presentation.kind === 'cancelled') return
    metricsError.value = presentation
    metricsStatus.value = 'error'
  }
}

function rowWidth(row) {
  const max = Math.max(...(currentDimension.value?.rows || []).map((item) => item.count), 0)
  return max ? `${Math.max(2, (row.count / max) * 100)}%` : '0%'
}

function openRecords() {
  if (!appliedContext.value || dirty.value) {
    relay.notify('请先应用当前统计范围，再读取访问记录。', 'warning')
    return
  }
  recordsOpen.value = true
  recordContext.value = {
    scope: { ...appliedContext.value.scope },
    range: { ...appliedContext.value.range },
    label: appliedContext.value.label
  }
  loadFirstRecordBatch()
}

function closeRecords() {
  recordController?.abort()
  ++recordSequence
  recordsOpen.value = false
  recordStatus.value = 'idle'
  recordPages.value = []
  recordIndex.value = 0
  recordError.value = null
  recordPaging.value = false
}

async function loadFirstRecordBatch() {
  if (!recordContext.value) return
  recordController?.abort()
  const controller = new AbortController()
  recordController = controller
  const sequence = ++recordSequence
  recordPages.value = []
  recordIndex.value = 0
  recordError.value = null
  recordRetry.value = 'first'
  recordStatus.value = 'loading'
  try {
    const page = await analyticsApi.queryAccessRecords(
      recordContext.value.scope,
      recordContext.value.range,
      { current: 1, size: 20 },
      { signal: controller.signal }
    )
    if (sequence !== recordSequence || controller.signal.aborted) return
    recordPages.value = [page]
    recordStatus.value = page.empty ? 'empty' : 'ready'
  } catch (error) {
    if (sequence !== recordSequence || controller.signal.aborted) return
    const presentation = classifyAnalyticsError(error)
    if (presentation.kind === 'cancelled') return
    recordError.value = presentation
    recordStatus.value = presentation.kind === 'snapshot-expired' ? 'expired' : 'error'
  }
}

async function nextRecordBatch() {
  if (recordPaging.value || !currentPage.value) return
  if (recordIndex.value < recordPages.value.length - 1) {
    recordIndex.value += 1
    recordError.value = null
    return
  }
  if (!currentPage.value.hasNext) return
  recordController?.abort()
  const controller = new AbortController()
  recordController = controller
  const sequence = ++recordSequence
  recordPaging.value = true
  recordError.value = null
  recordRetry.value = 'next'
  try {
    const firstSnapshot = recordPages.value[0]?.snapshotId
    const sentCursor = currentPage.value.nextCursor
    const page = await analyticsApi.queryAccessRecords(
      recordContext.value.scope,
      recordContext.value.range,
      {
        current: recordIndex.value + 2,
        size: 20,
        snapshotId: firstSnapshot,
        cursor: sentCursor
      },
      { signal: controller.signal }
    )
    if (sequence !== recordSequence || controller.signal.aborted) return
    validateAccessContinuation(recordPages.value, page, sentCursor)
    recordPages.value.push(page)
    recordIndex.value += 1
    recordStatus.value = 'ready'
  } catch (error) {
    if (sequence !== recordSequence || controller.signal.aborted) return
    const presentation = classifyAnalyticsError(error)
    if (presentation.kind === 'cancelled') return
    recordError.value = presentation
    if (presentation.kind === 'snapshot-expired') {
      recordPages.value = []
      recordIndex.value = 0
      recordStatus.value = 'expired'
    }
  } finally {
    if (sequence === recordSequence) recordPaging.value = false
  }
}

function previousRecordBatch() {
  if (recordIndex.value > 0 && !recordPaging.value) {
    recordIndex.value -= 1
    recordError.value = null
  }
}

function retryRecords() {
  if (recordRetry.value === 'next' && currentPage.value) nextRecordBatch()
  else loadFirstRecordBatch()
}

onMounted(() => {
  chooseDefaultScope()
  loadMetrics()
})

onBeforeUnmount(() => {
  metricsController?.abort()
  recordController?.abort()
  ++metricsSequence
  ++recordSequence
})
</script>

<template>
  <section class="analytics-view" aria-labelledby="analytics-heading">
    <header class="analytics-heading">
      <div>
        <p class="analytics-kicker">JUPITER RELAY / 观测台</p>
        <h1 id="analytics-heading">访问统计</h1>
        <p>从后端统计快照读取真实趋势、维度质量与脱敏访问记录。</p>
      </div>
      <RButton
        kind="secondary"
        :disabled="!appliedContext || dirty || metricsStatus === 'loading'"
        @click="openRecords"
      >
        <RIcon name="database" :size="20" />访问记录
      </RButton>
    </header>

    <section class="analytics-filter" aria-label="统计筛选">
      <div class="analytics-scope-tabs" role="group" aria-label="统计范围类型">
        <button
          :class="{ active: scopeType === 'group' }"
          :aria-pressed="scopeType === 'group'"
          type="button"
          @click="scopeType = 'group'"
        >
          分组
        </button>
        <button
          :class="{ active: scopeType === 'link' }"
          :aria-pressed="scopeType === 'link'"
          type="button"
          @click="scopeType = 'link'"
        >
          单条短链
        </button>
      </div>
      <RSelect
        v-model="scopeId"
        label="授权范围"
        :options="scopeOptions"
        :placeholder="scopeOptions.length ? '选择范围' : '暂无可用范围'"
        :disabled="!scopeOptions.length || metricsStatus === 'loading'"
        :hint="scopeHint"
      />
      <RDateTime
        v-model="startDate"
        type="date"
        label="开始日期"
        :max="today"
        :disabled="metricsStatus === 'loading'"
      />
      <RDateTime
        v-model="endDate"
        type="date"
        label="结束日期"
        :max="today"
        :disabled="metricsStatus === 'loading'"
        :error="rangeCheck.ok ? '' : rangeCheck.message"
      />
      <RButton
        :loading="metricsStatus === 'loading'"
        loading-text="读取快照"
        :disabled="!scopeId || !rangeCheck.ok"
        @click="loadMetrics"
        >应用范围</RButton
      >
    </section>

    <p v-if="dirty" class="analytics-alert analytics-alert--warning" role="status">
      筛选条件已修改；下方仍显示上一次已应用范围，点击“应用范围”后更新。
    </p>

    <section
      v-if="metricsStatus === 'loading'"
      class="analytics-state"
      role="status"
      aria-live="polite"
    >
      <RRobot role="navigator" expression="waiting" :size="128" />
      <h2>正在读取统计快照</h2>
      <p>
        {{ appliedContext?.label }} · {{ appliedContext?.range.startDate }} 至
        {{ appliedContext?.range.endDate }}
      </p>
    </section>

    <section v-else-if="metricsStatus === 'error'" class="analytics-state" role="alert">
      <RRobot role="navigator" expression="recovery" :size="118" />
      <RBadge :tone="metricsError?.kind === 'permission' ? 'danger' : 'warning'">{{
        metricsError?.kind === 'permission' ? '权限不足' : '读取失败'
      }}</RBadge>
      <h2>{{ metricsError?.message }}</h2>
      <RButton v-if="metricsError?.retryable" kind="secondary" @click="loadMetrics"
        >重新读取</RButton
      >
    </section>

    <section v-else-if="metricsStatus === 'empty'" class="analytics-state">
      <RRobot role="navigator" :size="128" />
      <RBadge tone="unknown">当前范围无数据</RBadge>
      <h2>{{ appliedContext?.label || '所选范围' }}暂无可展示访问</h2>
      <p>
        查询窗口为 {{ appliedContext?.range.startDate }} 至
        {{ appliedContext?.range.endDate }}；没有用示例数值填充空结果。
      </p>
      <RButton kind="secondary" @click="openRecords">核对访问记录</RButton>
    </section>

    <template v-else-if="metricsStatus === 'ready' && metricsModel">
      <section class="analytics-snapshot" aria-label="统计快照说明">
        <div>
          <strong>{{ appliedContext.label }}</strong>
          <span
            >{{ appliedContext.range.startDate }} — {{ appliedContext.range.endDate }} ·
            Asia/Shanghai</span
          >
        </div>
        <div class="analytics-badges">
          <RBadge :tone="qualityTone(metricsModel.meta.completeness)">{{
            qualityLabel(metricsModel.meta.completeness)
          }}</RBadge>
          <RBadge :tone="qualityTone(metricsModel.meta.collectionQuality?.status)">{{
            qualityLabel(metricsModel.meta.collectionQuality?.status)
          }}</RBadge>
          <span>生成于 {{ formatShanghaiTime(metricsModel.meta.generatedAt) }}</span>
        </div>
      </section>

      <p
        v-if="
          metricsModel.meta.completeness !== 'COMPLETE' ||
          metricsModel.missingCoreMetrics.length ||
          metricsModel.meta.collectionQuality?.status !== 'NORMAL'
        "
        class="analytics-alert analytics-alert--warning"
      >
        该快照的数据并非完全可用。<template v-if="metricsModel.meta.missingMetrics.length">
          缺失项：{{ metricsModel.meta.missingMetrics.join('、') }}。</template
        ><template v-if="metricsModel.missingCoreMetrics.length">
          核心指标缺失：{{
            metricsModel.missingCoreMetrics.map((item) => item.toUpperCase()).join('、')
          }}。</template
        >
        <template v-if="metricsModel.meta.collectionQuality?.reasons?.length">
          采集说明：{{ metricsModel.meta.collectionQuality.reasons.join('、') }}。</template
        >
      </p>

      <section class="analytics-metrics" aria-label="核心指标">
        <article>
          <span>访问次数 PV</span><strong>{{ formatCount(metricsModel.summary.pv) }}</strong
          ><small>{{ approximationLabel('pv') }}</small>
        </article>
        <article>
          <span>独立访客 UV</span><strong>{{ formatCount(metricsModel.summary.uv) }}</strong
          ><small>{{ approximationLabel('uv') }}</small>
        </article>
        <article>
          <span>独立 IP UIP</span><strong>{{ formatCount(metricsModel.summary.uip) }}</strong
          ><small>{{ approximationLabel('uip') }}</small>
        </article>
        <article>
          <span>拒绝访问</span><strong>{{ formatCount(metricsModel.summary.denied) }}</strong
          ><small>{{ approximationLabel('denied') }}</small>
        </article>
      </section>

      <section class="analytics-panel analytics-trend-panel">
        <header class="analytics-panel-head">
          <div>
            <p class="analytics-eyebrow">REQUESTED WINDOW</p>
            <h2>按日趋势</h2>
          </div>
          <div class="analytics-switches">
            <div role="group" aria-label="趋势指标">
              <button
                v-for="item in ['pv', 'uv', 'uip']"
                :key="item"
                type="button"
                :class="{ active: metricKey === item }"
                :aria-pressed="metricKey === item"
                @click="metricKey = item"
              >
                {{ item.toUpperCase() }}
              </button>
            </div>
            <div role="group" aria-label="展示方式">
              <button
                type="button"
                :class="{ active: viewMode === 'chart' }"
                :aria-pressed="viewMode === 'chart'"
                @click="viewMode = 'chart'"
              >
                图</button
              ><button
                type="button"
                :class="{ active: viewMode === 'table' }"
                :aria-pressed="viewMode === 'table'"
                @click="viewMode = 'table'"
              >
                表
              </button>
            </div>
          </div>
        </header>
        <div v-if="!trend.length" class="analytics-inline-empty">
          后端未返回按日趋势，核心指标仍按原响应展示。
        </div>
        <div v-else-if="viewMode === 'chart'" class="analytics-chart-wrap">
          <svg
            class="analytics-chart"
            viewBox="0 0 760 238"
            role="img"
            :aria-label="chartModel.desc"
          >
            <title>{{ selectedMetricLabel }} 按日趋势</title>
            <desc>{{ chartModel.desc }}</desc>
            <g class="analytics-chart-grid">
              <line v-for="y in [40, 92, 144, 196]" :key="y" x1="56" :y1="y" x2="704" :y2="y" />
            </g>
            <polyline :points="chartModel.points" />
            <g v-for="point in chartModel.dots" :key="point.date">
              <circle :cx="point.x" :cy="point.y" r="5" />
              <text :x="point.x" :y="point.y - 13">{{ formatCount(point.value) }}</text>
              <text class="analytics-chart-date" :x="point.x" y="224">
                {{ point.date.slice(5) }}
              </text>
            </g>
          </svg>
        </div>
        <div v-else class="analytics-table-scroll">
          <table>
            <caption>
              {{
                selectedMetricLabel
              }}
              按日统计
            </caption>
            <thead>
              <tr>
                <th>日期</th>
                <th>PV</th>
                <th>UV</th>
                <th>UIP</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in trend" :key="row.date">
                <td>{{ row.date }}</td>
                <td>{{ formatCount(row.pv) }}</td>
                <td>{{ formatCount(row.uv) }}</td>
                <td>{{ formatCount(row.uip) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="analytics-panel">
        <header class="analytics-panel-head">
          <div>
            <p class="analytics-eyebrow">DIMENSION QUALITY</p>
            <h2>访问维度</h2>
          </div>
          <RBadge :tone="qualityTone(currentDimension.quality.status)">{{
            qualityLabel(currentDimension.quality.status)
          }}</RBadge>
        </header>
        <div class="analytics-dimension-tabs" role="tablist" aria-label="统计维度">
          <button
            v-for="item in ANALYTICS_DIMENSIONS"
            :key="item.key"
            type="button"
            role="tab"
            :class="{ active: dimensionKey === item.key }"
            :aria-selected="dimensionKey === item.key"
            @click="dimensionKey = item.key"
          >
            {{ item.label }}
          </button>
        </div>
        <p class="analytics-dimension-note">{{ currentDimension.note }}</p>
        <div v-if="dimensionKey === 'newvisitor'" class="analytics-history">
          <strong>首次观测口径</strong>
          <span v-if="currentDimension.quality.historyStart && currentDimension.quality.historyEnd"
            >保留数据范围：{{ formatShanghaiDate(currentDimension.quality.historyStart) }} —
            {{ formatShanghaiDate(currentDimension.quality.historyEnd) }}</span
          >
          <span v-else>后端未返回历史保留区间。</span>
          <span
            >覆盖率：{{ formatRatio(currentDimension.quality.coverage)
            }}<template v-if="currentDimension.quality.maxHistoryDays">
              · 最多 {{ currentDimension.quality.maxHistoryDays }} 天</template
            ></span
          >
        </div>
        <div v-if="currentDimension.rows.length" class="analytics-distribution">
          <article
            v-for="(row, index) in currentDimension.rows"
            :key="`${row.label}-${index}`"
            :class="{ unknown: row.unknown }"
          >
            <div>
              <strong>{{ row.label }}</strong
              ><span
                >{{ formatCount(row.count) }} · {{ formatRatio(row.ratio)
                }}<template v-if="row.error !== null && row.error !== undefined">
                  · 误差 ≤ {{ formatCount(row.error) }}</template
                ></span
              >
            </div>
            <div class="analytics-bar" aria-hidden="true">
              <span :style="{ width: rowWidth(row) }" />
            </div>
          </article>
        </div>
        <div v-else class="analytics-inline-empty">
          该维度未返回可展示数据；不将缺失字段补为 0。
        </div>
        <p v-if="currentDimension.quality.reason" class="analytics-quality-reason">
          后端说明：{{ currentDimension.quality.reason }}
        </p>
      </section>
    </template>

    <RModal
      :open="recordsOpen"
      title="脱敏访问记录"
      description="使用服务端快照和游标逐批读取；不提供随机页码或推断总数。"
      drawer
      width="760"
      :close-on-backdrop="!recordPaging"
      @close="closeRecords"
    >
      <div class="analytics-records">
        <div v-if="recordContext" class="analytics-record-context">
          <strong>{{ recordContext.label }}</strong
          ><span
            >{{ recordContext.range.startDate }} — {{ recordContext.range.endDate }} ·
            Asia/Shanghai</span
          >
        </div>
        <div v-if="recordStatus === 'loading'" class="analytics-record-state" role="status">
          <span class="analytics-spinner" />正在读取首批快照…
        </div>
        <div v-else-if="recordStatus === 'expired'" class="analytics-record-state" role="alert">
          <RIcon name="warning" :size="28" /><strong>统计快照已失效</strong>
          <p>{{ recordError?.message }}</p>
          <RButton kind="secondary" @click="loadFirstRecordBatch">从首批重新读取</RButton>
        </div>
        <div v-else-if="recordStatus === 'error'" class="analytics-record-state" role="alert">
          <RIcon name="warning" :size="28" /><strong>访问记录读取失败</strong>
          <p>{{ recordError?.message }}</p>
          <RButton v-if="recordError?.retryable" kind="secondary" @click="retryRecords"
            >重试</RButton
          >
        </div>
        <div v-else-if="recordStatus === 'empty'" class="analytics-record-state">
          <RIcon name="database" :size="30" /><strong>当前快照没有访问记录</strong>
          <p>范围与日期保持不变，未生成占位数据。</p>
        </div>
        <template v-else-if="recordStatus === 'ready' && currentPage">
          <div class="analytics-record-meta">
            <span>第 {{ recordIndex + 1 }} 批 · 每批最多 20 条</span
            ><span>快照创建：{{ formatShanghaiTime(currentPage.meta.snapshotCreatedAt) }}</span
            ><span>失效：{{ formatShanghaiTime(currentPage.meta.snapshotExpiresAt) }}</span>
          </div>
          <p v-if="recordError" class="analytics-alert analytics-alert--warning" role="alert">
            {{ recordError.message }} <button type="button" @click="retryRecords">重试本批</button>
          </p>
          <div class="analytics-table-scroll analytics-record-table">
            <table>
              <caption>
                服务端返回的脱敏访问明细
              </caption>
              <thead>
                <tr>
                  <th>时间 / 短链</th>
                  <th>访客</th>
                  <th>地域 / ISP</th>
                  <th>设备</th>
                  <th>事件 / 响应</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(row, index) in currentPage.records"
                  :key="row.eventId || `${row.occurredAt}-${row.visitorIdentifier}-${index}`"
                >
                  <td>
                    {{ row.occurredAtDisplay }}<small>{{ row.fullShortUrl || row.linkId }}</small>
                  </td>
                  <td>
                    {{ row.visitorType }}<small>访客 {{ row.visitorIdentifier }}</small
                    ><small>IP {{ row.ipIdentifier }}</small>
                  </td>
                  <td>
                    {{ row.location }}<small>{{ row.isp }}</small>
                  </td>
                  <td>
                    {{ row.device }}<small>{{ row.os }} / {{ row.browser }}</small>
                  </td>
                  <td>
                    {{ row.eventType }}<small>HTTP {{ row.responseStatus }}</small>
                  </td>
                </tr>
                <tr v-if="!currentPage.records.length">
                  <td colspan="5">本批没有访问记录</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="analytics-record-cards">
            <article
              v-for="row in currentPage.records"
              :key="`card-${row.eventId || row.occurredAt}`"
            >
              <strong>{{ row.occurredAtDisplay }}</strong
              ><span>{{ row.fullShortUrl || row.linkId }}</span>
              <dl>
                <div>
                  <dt>访客</dt>
                  <dd>{{ row.visitorType }} · {{ row.visitorIdentifier }}</dd>
                </div>
                <div>
                  <dt>IP</dt>
                  <dd>{{ row.ipIdentifier }}</dd>
                </div>
                <div>
                  <dt>地域 / ISP</dt>
                  <dd>{{ row.location }} · {{ row.isp }}</dd>
                </div>
                <div>
                  <dt>设备</dt>
                  <dd>{{ row.device }} · {{ row.os }} · {{ row.browser }}</dd>
                </div>
                <div>
                  <dt>结果</dt>
                  <dd>{{ row.eventType }} · HTTP {{ row.responseStatus }}</dd>
                </div>
              </dl>
            </article>
          </div>
          <p class="analytics-record-note">
            新老访客按范围在保留数据集中的首次观测划分；单条记录不展示或推断该访客的首次访问时间。
          </p>
        </template>
      </div>
      <template #footer>
        <div class="analytics-record-footer">
          <template v-if="currentPage"
            ><RButton
              kind="secondary"
              :disabled="recordIndex === 0 || recordPaging"
              @click="previousRecordBatch"
              >上一批</RButton
            ><span>已缓存 {{ recordPages.length }} 批</span
            ><RButton
              :loading="recordPaging"
              loading-text="读取下一批"
              :disabled="recordIndex === recordPages.length - 1 && !currentPage.hasNext"
              @click="nextRecordBatch"
              >下一批</RButton
            ></template
          >
          <span v-else>快照和游标仅用于本次访问记录读取。</span>
        </div>
      </template>
    </RModal>
  </section>
</template>
