<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { analyticsApi } from '../api/analytics.js'
import { absoluteShortUrl } from '../api/product.js'
import PageHeading from '../components/PageHeading.vue'
import AnalyticsDimensionPanel from '../components/AnalyticsDimensionPanel.vue'
import {
  AnalyticsContractError,
  classifyAnalyticsError,
  defaultShanghaiDateRange,
  formatCount,
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
const displayShortUrl = (value) =>
  absoluteShortUrl(value) || (typeof value === 'string' ? value : '')
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
const dashboardGroups = [
  {
    title: '访问时段',
    className: 'analytics-time-panel',
    dimensions: [
      { key: 'hour', label: '24 小时' },
      { key: 'weekday', label: '星期' }
    ]
  },
  {
    title: '访客构成',
    className: 'analytics-visitors-panel',
    dimensions: [
      { key: 'newvisitor', label: '新老访客' },
      { key: 'ip', label: '高频 IP' }
    ]
  },
  {
    title: '设备环境',
    className: 'analytics-devices-panel',
    dimensions: [
      { key: 'device', label: '设备' },
      { key: 'os', label: '操作系统' },
      { key: 'browser', label: '浏览器' }
    ]
  },
  {
    title: '地域与网络',
    className: 'analytics-location-panel',
    dimensions: [
      { key: 'country', label: '国家' },
      { key: 'province', label: '省份' },
      { key: 'isp', label: '运营商 / ISP' }
    ]
  }
]
const metricCards = [
  { key: 'pv', label: '访问次数', abbreviation: 'PV' },
  { key: 'uv', label: '独立访客', abbreviation: 'UV' },
  { key: 'uip', label: '独立 IP', abbreviation: 'UIP' },
  { key: 'denied', label: '拒绝访问', abbreviation: '' }
]
const filtersOpen = ref(false)
const qualityOpen = ref(false)
const chartElement = ref(null)
const chartWidth = ref(760)
const chartHeight = ref(238)
let chartObserver

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
const trend = computed(() => metricsModel.value?.summary.daily || [])
const selectedMetricLabel = computed(() => ({ pv: 'PV', uv: 'UV', uip: 'UIP' })[metricKey.value])
const hasQualityWarning = computed(() =>
  Boolean(
    metricsModel.value &&
    (metricsModel.value.meta.completeness !== 'COMPLETE' ||
      metricsModel.value.missingCoreMetrics.length ||
      metricsModel.value.meta.collectionQuality?.status !== 'NORMAL')
  )
)

watch(chartElement, (element) => {
  chartObserver?.disconnect()
  if (!element) return
  chartWidth.value = Math.max(160, Math.round(element.clientWidth))
  chartObserver = new ResizeObserver(([entry]) => {
    if (entry.contentRect.width > 0) {
      chartWidth.value = Math.max(160, Math.round(entry.contentRect.width))
      chartHeight.value = Math.max(160, Math.round(entry.contentRect.height))
    }
  })
  chartObserver.observe(element)
})

const axisFormatter = new Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 1
})
const chartModel = computed(() => {
  const rows = trend.value
  if (!rows.length)
    return { points: '', area: '', dots: [], ticks: [], desc: '没有可展示的按日趋势。' }
  const max = Math.max(...rows.map((row) => row[metricKey.value]), 0)
  const ceiling = Math.max(4, Math.ceil(max / 4) * 4)
  const left = Math.max(38, axisFormatter.format(ceiling).length * 8 + 14),
    right = chartWidth.value - 22
  const top = 22,
    bottom = chartHeight.value - 30
  const plotWidth = Math.max(1, right - left)
  const labelStep = Math.max(
    1,
    Math.ceil((rows.length - 1) / Math.max(1, Math.floor(plotWidth / 62)))
  )
  const dots = rows.map((row, index) => ({
    x: rows.length === 1 ? (left + right) / 2 : left + (index * plotWidth) / (rows.length - 1),
    y: bottom - (row[metricKey.value] / ceiling) * (bottom - top),
    value: row[metricKey.value],
    date: row.date,
    labelled:
      index === 0 ||
      index === rows.length - 1 ||
      (index % labelStep === 0 && index < rows.length - 1 - labelStep / 2)
  }))
  return {
    left,
    points: dots.map((point) => `${point.x},${point.y}`).join(' '),
    area: `${dots[0].x},${bottom} ${dots.map((point) => `${point.x},${point.y}`).join(' ')} ${dots.at(-1).x},${bottom}`,
    dots,
    ticks: Array.from({ length: 5 }, (_, index) => ({
      value: (ceiling * (4 - index)) / 4,
      y: top + (index * (bottom - top)) / 4
    })),
    desc: `${rows[0].date} 至 ${rows.at(-1).date} 的 ${selectedMetricLabel.value} 趋势，最高 ${max}。`
  }
})

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

function closeFilters() {
  if (appliedContext.value) {
    scopeType.value = appliedContext.value.scope.type
    scopeId.value = appliedContext.value.scope.id
    startDate.value = appliedContext.value.range.startDate
    endDate.value = appliedContext.value.range.endDate
  }
  filtersOpen.value = false
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
  filtersOpen.value = false
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
  chartObserver?.disconnect()
  metricsController?.abort()
  recordController?.abort()
  ++metricsSequence
  ++recordSequence
})
</script>

<template>
  <section class="analytics-view operation-page" aria-labelledby="analytics-heading">
    <PageHeading class="analytics-heading">
      <div>
        <h1 id="analytics-heading">访问统计</h1>
        <p>查看访问趋势、访客构成与访问环境。</p>
      </div>
    </PageHeading>

    <div class="analytics-body operation-body" role="region" aria-label="访问统计内容" tabindex="0">
      <div class="analytics-context-bar" aria-label="当前统计范围">
        <div class="analytics-context-scope">
          <RIcon :name="appliedContext?.scope.type === 'link' ? 'link' : 'folder'" :size="18" />
          <strong :title="appliedContext?.label">{{
            appliedContext?.label || '选择统计范围'
          }}</strong>
          <span class="analytics-context-dates">
            <template v-if="appliedContext"
              >{{ appliedContext.range.startDate }} 至 {{ appliedContext.range.endDate }}</template
            >
            <template v-else>请选择日期</template>
          </span>
        </div>
        <div class="analytics-context-actions">
          <RButton
            kind="secondary"
            class="analytics-context-button"
            :disabled="!appliedContext || dirty || metricsStatus === 'loading'"
            @click="openRecords"
          >
            <RIcon name="database" :size="16" />访问记录
          </RButton>
          <RButton
            kind="secondary"
            class="analytics-context-button"
            aria-haspopup="dialog"
            :disabled="metricsStatus === 'loading'"
            @click="filtersOpen = true"
          >
            <RIcon name="gear" :size="16" />筛选
          </RButton>
          <RButton
            kind="text"
            class="analytics-context-button"
            aria-haspopup="dialog"
            :disabled="!metricsModel"
            @click="qualityOpen = true"
          >
            <RIcon name="database" :size="16" />数据说明
            <span
              v-if="hasQualityWarning"
              class="analytics-quality-indicator"
              aria-label="部分数据尚未确认"
            />
          </RButton>
        </div>
      </div>

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
          {{ appliedContext?.range.endDate }}。可调整范围或日期查看其他访问。
        </p>
        <RButton kind="secondary" @click="openRecords">核对访问记录</RButton>
      </section>

      <template v-else-if="metricsStatus === 'ready' && metricsModel">
        <section class="analytics-metrics" aria-label="核心指标">
          <article
            v-for="item in metricCards"
            :key="item.key"
            :class="{ 'analytics-metric-primary': item.key === 'pv' }"
          >
            <div class="analytics-metric-label">
              <span>{{ item.label }}</span
              ><small>{{ item.abbreviation }}</small>
            </div>
            <strong>{{ formatCount(metricsModel.summary[item.key]) }}</strong>
            <small>{{ approximationLabel(item.key) }}</small>
          </article>
        </section>

        <div class="analytics-dashboard">
          <section
            class="analytics-panel analytics-trend-panel"
            aria-labelledby="analytics-trend-title"
          >
            <header class="analytics-panel-head">
              <div>
                <h2 id="analytics-trend-title">访问趋势</h2>
                <p>按日统计 · 北京时间</p>
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
                    图
                  </button>
                  <button
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
            <div v-if="!trend.length" class="analytics-inline-empty">此范围暂无按日趋势。</div>
            <div v-else-if="viewMode === 'chart'" ref="chartElement" class="analytics-chart-wrap">
              <svg
                class="analytics-chart"
                :viewBox="`0 0 ${chartWidth} ${chartHeight}`"
                role="img"
                :aria-label="chartModel.desc"
              >
                <title>{{ selectedMetricLabel }} 按日趋势</title>
                <desc>{{ chartModel.desc }}</desc>
                <g v-for="tick in chartModel.ticks" :key="tick.value" class="analytics-chart-grid">
                  <line :x1="chartModel.left" :y1="tick.y" :x2="chartWidth - 22" :y2="tick.y" />
                  <text :x="chartModel.left - 8" :y="tick.y + 4">
                    <title>{{ formatCount(tick.value) }}</title>
                    {{ axisFormatter.format(tick.value) }}
                  </text>
                </g>
                <polygon :points="chartModel.area" class="analytics-chart-area" />
                <polyline :points="chartModel.points" />
                <g
                  v-for="point in chartModel.dots"
                  :key="point.date"
                  class="analytics-chart-point"
                  tabindex="0"
                  role="img"
                  :aria-label="`${point.date}，${selectedMetricLabel} ${formatCount(point.value)}`"
                >
                  <title>
                    {{ point.date }} · {{ selectedMetricLabel }} {{ formatCount(point.value) }}
                  </title>
                  <circle :cx="point.x" :cy="point.y" r="4" />
                  <text :x="point.x" :y="point.y - 11" class="analytics-chart-value">
                    {{ formatCount(point.value) }}
                  </text>
                  <text
                    v-if="point.labelled"
                    class="analytics-chart-date"
                    :x="point.x"
                    :y="chartHeight - 6"
                  >
                    {{ point.date.slice(5) }}
                  </text>
                </g>
              </svg>
            </div>
            <div
              v-else
              class="analytics-table-scroll"
              role="region"
              aria-label="按日趋势表，可滚动查看更多日期"
              tabindex="0"
            >
              <table>
                <caption>
                  所选日期的每日访问统计
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
          <AnalyticsDimensionPanel
            v-for="group in dashboardGroups"
            :key="group.title"
            :class="group.className"
            :model="metricsModel"
            :title="group.title"
            :dimensions="group.dimensions"
            :context="`${appliedContext?.label || '所选范围'} · ${appliedContext?.range.startDate || ''} 至 ${appliedContext?.range.endDate || ''}`"
          />
        </div>
      </template>
    </div>

    <RModal
      :open="filtersOpen"
      title="统计筛选"
      description="选择统计范围与日期，最多查看连续 7 天。"
      :width="640"
      @close="closeFilters"
    >
      <section class="analytics-filter" aria-label="统计筛选条件">
        <div class="analytics-scope-tabs" role="group" aria-label="统计范围类型">
          <button
            type="button"
            :class="{ active: scopeType === 'group' }"
            :aria-pressed="scopeType === 'group'"
            @click="scopeType = 'group'"
          >
            分组
          </button>
          <button
            type="button"
            :class="{ active: scopeType === 'link' }"
            :aria-pressed="scopeType === 'link'"
            @click="scopeType = 'link'"
          >
            单条短链
          </button>
        </div>
        <div class="analytics-filter-scope">
          <RSelect
            v-model="scopeId"
            label="授权范围"
            :options="scopeOptions"
            :placeholder="scopeOptions.length ? '选择范围' : '暂无可用范围'"
            :disabled="!scopeOptions.length || metricsStatus === 'loading'"
            :hint="scopeHint"
          />
        </div>
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
      </section>
      <template #footer>
        <div class="analytics-dialog-actions">
          <RButton kind="secondary" @click="closeFilters">取消</RButton>
          <RButton
            :loading="metricsStatus === 'loading'"
            loading-text="读取快照"
            :disabled="!scopeId || !rangeCheck.ok"
            @click="loadMetrics"
            >应用范围</RButton
          >
        </div>
      </template>
    </RModal>

    <RModal
      :open="qualityOpen"
      title="数据说明"
      description="了解当前统计的完整度与计数口径。"
      :width="560"
      @close="qualityOpen = false"
    >
      <div v-if="metricsModel" class="analytics-quality-content">
        <dl class="analytics-quality-facts">
          <div>
            <dt>数据完整度</dt>
            <dd>{{ qualityLabel(metricsModel.meta.completeness) }}</dd>
          </div>
          <div>
            <dt>采集状态</dt>
            <dd>{{ qualityLabel(metricsModel.meta.collectionQuality?.status) }}</dd>
          </div>
          <div class="analytics-quality-time">
            <dt>快照生成时间</dt>
            <dd>{{ formatShanghaiTime(metricsModel.meta.generatedAt) }} · 北京时间</dd>
          </div>
        </dl>
        <p v-if="hasQualityWarning" class="analytics-alert analytics-alert--warning">
          部分数据尚未确认；缺失数值显示为「—」，不计作零。
        </p>
        <div class="analytics-quality-methods">
          <h3>核心指标口径</h3>
          <dl>
            <div v-for="item in metricCards" :key="item.key">
              <dt>{{ item.label }} {{ item.abbreviation }}</dt>
              <dd>{{ approximationLabel(item.key) }}</dd>
            </div>
          </dl>
        </div>
        <details v-if="hasQualityWarning" class="analytics-quality-details">
          <summary>查看缺失项与采集详情</summary>
          <p v-if="metricsModel.meta.missingMetrics.length">
            缺失项：{{ metricsModel.meta.missingMetrics.join('、') }}。
          </p>
          <p v-if="metricsModel.missingCoreMetrics.length">
            核心指标缺失：{{
              metricsModel.missingCoreMetrics.map((item) => item.toUpperCase()).join('、')
            }}。
          </p>
          <p v-if="metricsModel.meta.collectionQuality?.reasons?.length">
            采集说明：{{ metricsModel.meta.collectionQuality.reasons.join('、') }}。
          </p>
        </details>
      </div>
      <template #footer><RButton @click="qualityOpen = false">知道了</RButton></template>
    </RModal>

    <RModal
      :open="recordsOpen"
      title="脱敏访问记录"
      description="使用服务端快照和游标逐批读取；不提供随机页码或推断总数。"
      drawer
      :width="760"
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
          <div
            class="analytics-table-scroll analytics-record-table"
            role="region"
            aria-label="脱敏访问记录表"
            tabindex="0"
          >
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
                    {{ row.occurredAtDisplay
                    }}<small>{{ displayShortUrl(row.fullShortUrl) || row.linkId }}</small>
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
              ><span>{{ displayShortUrl(row.fullShortUrl) || row.linkId }}</span>
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
