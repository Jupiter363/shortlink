<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AgentAnswer from './AgentAnswer.vue'
import CampaignReportChart from './CampaignReportChart.vue'
import { agentApi } from '../api/agentRisk.js'
import {
  analyticDimensionLabel,
  analyticDimensionValue,
  analyticRate,
  errorMessage,
  pretty,
  safeText,
  sanitize
} from '../domain/agentModel.js'
import {
  CAMPAIGN_BLOCK_KINDS,
  displayValue,
  reportIdentity,
  sameReport
} from '../domain/campaignReport.js'

const props = defineProps({
  block: { type: Object, required: true },
  view: { type: Object, required: true },
  sessionId: { type: String, required: true }
})
const payload = computed(() => props.block.payload || {})
const metrics = computed(() => (Array.isArray(payload.value.items) ? payload.value.items : []))
const table = computed(() => ['TABLE', 'RESULT_LINK'].includes(props.block.kind))
const rows = ref([])
const cursor = ref(null)
const nextCursor = ref(null)
const cursorHistory = ref([])
const pageNumber = ref(1)
const totalRows = ref(null)
const loaded = ref(false)
const loading = ref(false)
const failure = ref('')
const showAllColumns = ref(false)
const embeddedPageSize = 25
const columnLabels = {
  linkId: '短链 ID',
  shortUri: '短码',
  fullShortUrl: '短链地址',
  pv: 'PV',
  uv: 'UV',
  uip: 'UIP',
  denied: '拒绝次数',
  domain: '域名',
  gid: '分组 ID',
  startInclusive: '开始时间戳',
  endExclusive: '结束时间戳',
  window: '统计窗口',
  dimensions: '维度',
  baseline: '基期',
  target: '目标期',
  presence: '期间覆盖',
  pvRatio: 'PV 占比',
  pvDelta: 'PV 变化量',
  pvRelativeChange: 'PV 变化率',
  pvShareChange: 'PV 占比变化'
}
const primaryColumns = new Set(['linkId', 'shortUri', 'pv', 'uv', 'uip', 'denied'])
const countKeys = ['pv', 'uv', 'uip', 'denied']
const presenceLabels = {
  BOTH: '两期均有记录',
  BASELINE_ONLY: '仅基期有记录',
  TARGET_ONLY: '仅目标期有记录'
}
const isRecord = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const isStructured = (value) => value !== null && typeof value === 'object'
let controller
let generation = 0
const embeddedRanking = computed(
  () =>
    props.block.kind === 'TABLE' &&
    payload.value.sourceComplete === true &&
    !payload.value.artifactId &&
    Array.isArray(payload.value.rows)
)
const visibleRows = computed(() =>
  embeddedRanking.value
    ? rows.value.slice(
        (pageNumber.value - 1) * embeddedPageSize,
        pageNumber.value * embeddedPageSize
      )
    : rows.value
)
const embeddedPages = computed(() => Math.max(1, Math.ceil(rows.value.length / embeddedPageSize)))
const hasUnknownDimensions = computed(() =>
  visibleRows.value.some((row) =>
    Object.entries(row).some(([key, value]) =>
      key === 'dimensions' && isRecord(value)
        ? Object.values(value).some((cell) => cell?.state === 'UNKNOWN')
        : isRecord(value) && value.state === 'UNKNOWN' && Object.hasOwn(value, 'value')
    )
  )
)
const columns = computed(() =>
  Array.isArray(payload.value.columns) && payload.value.columns.length
    ? payload.value.columns
        .filter((column) => typeof column?.key === 'string')
        .map((column) => ({
          key: column.key,
          label: columnLabels[column.key] || column.label || column.key
        }))
    : Object.keys(rows.value[0] || {}).map((key) => ({ key, label: columnLabels[key] || key }))
)
const compactColumns = computed(() => {
  const keys = new Set(columns.value.map((column) => column.key))
  return columns.value.length > 8 && ['linkId', 'pv', 'uv', 'uip'].every((key) => keys.has(key))
})
const displayColumns = computed(() =>
  (compactColumns.value && !showAllColumns.value
    ? [...primaryColumns]
        .map((key) => columns.value.find((column) => column.key === key))
        .filter(Boolean)
    : columns.value
  ).map((column) => ({
    ...column,
    structured: visibleRows.value.some((row) => isStructured(row[column.key]))
  }))
)
const tableRows = computed(() =>
  visibleRows.value.map((row) =>
    displayColumns.value.map((column) => {
      const value = row[column.key]
      return {
        key: column.key,
        structured: isStructured(value),
        entries: cellEntries(column.key, value),
        text: scalarValue(column.key, value),
        raw: isStructured(value) ? pretty(value) : safeText(value),
        summary: Array.isArray(value)
          ? `${value.length} 项数据`
          : `${Object.keys(isRecord(value) ? value : {}).length} 个字段`
      }
    })
  )
)
const known = computed(() => CAMPAIGN_BLOCK_KINDS.includes(props.block.kind))
function scalarValue(key, value) {
  if (isStructured(value)) return ''
  if (key === 'presence' && Object.hasOwn(presenceLabels, value)) return presenceLabels[value]
  if (typeof value === 'number' && Number.isFinite(value)) {
    if (key === 'pvRatio' || key === 'pvRelativeChange')
      return analyticRate(value, key === 'pvRelativeChange')
    if (key === 'pvShareChange') return `${displayValue(value * 100)} 个百分点`
  }
  return safeText(displayValue(value))
}
function cellEntries(key, value) {
  if (!isRecord(value)) return []
  if (key === 'dimensions')
    return Object.entries(value).map(([dimension, cell]) => ({
      label: analyticDimensionLabel(dimension),
      value: isRecord(cell)
        ? `${analyticDimensionValue(cell, dimension)}${cell.state === 'UNKNOWN' ? '（UNKNOWN）' : ''}`
        : scalarValue(dimension, cell)
    }))
  if (Object.hasOwn(value, 'state') && Object.hasOwn(value, 'value'))
    return [
      {
        label: analyticDimensionLabel(key),
        value: `${analyticDimensionValue(value, key)}${value.state === 'UNKNOWN' ? '（UNKNOWN）' : ''}`
      }
    ]
  if (countKeys.some((metric) => Object.hasOwn(value, metric)))
    return [
      ...['pv', 'uv', 'uip'],
      ...['denied', 'pvRatio'].filter((metric) => Object.hasOwn(value, metric))
    ].map((metric) => ({ label: columnLabels[metric], value: scalarValue(metric, value[metric]) }))
  return []
}
function reset() {
  generation += 1
  controller?.abort()
  rows.value = Array.isArray(payload.value.rows) ? payload.value.rows : []
  nextCursor.value = payload.value.nextCursor || null
  totalRows.value = payload.value.totalRows ?? payload.value.rowCount ?? null
  loaded.value = props.block.kind === 'TABLE'
  cursor.value = null
  cursorHistory.value = []
  pageNumber.value = 1
  failure.value = ''
  loading.value = false
  showAllColumns.value = false
}
watch(
  () => [
    props.view.reportRef.reportId,
    props.view.reportRef.revision,
    props.block.blockId,
    props.sessionId
  ],
  reset,
  { immediate: true }
)
async function load(direction = 'initial') {
  if (loading.value) return
  const requested =
    direction === 'next'
      ? nextCursor.value
      : direction === 'previous'
        ? cursorHistory.value.at(-1)
        : null
  if (direction === 'next' && !requested) return
  if (direction === 'previous' && !cursorHistory.value.length) return
  controller?.abort()
  controller = new AbortController()
  const mine = ++generation
  loading.value = true
  failure.value = ''
  try {
    const response = await agentApi.reportRows(
      {
        ...reportIdentity(props.view),
        sessionId: props.sessionId,
        blockId: props.block.blockId,
        cursor: requested,
        size: 25
      },
      controller.signal
    )
    if (mine !== generation) return
    if (
      response?.schemaVersion !== 'campaign-report-rows/v1' ||
      !sameReport(props.view, response) ||
      response.blockId !== props.block.blockId ||
      !Array.isArray(response.rows) ||
      response.rows.some((row) => !row || typeof row !== 'object' || Array.isArray(row))
    ) {
      throw new Error('明细版本与当前报告不一致，已停止载入。')
    }
    if (requested && response.nextCursor === requested)
      throw new Error('明细分页未向前推进，请稍后重新读取。')
    if (direction === 'next') {
      cursorHistory.value.push(cursor.value)
      pageNumber.value += 1
    } else if (direction === 'previous') {
      cursorHistory.value.pop()
      pageNumber.value -= 1
    } else {
      cursorHistory.value = []
      pageNumber.value = 1
    }
    cursor.value = requested
    rows.value = sanitize(response.rows)
    nextCursor.value = typeof response.nextCursor === 'string' ? response.nextCursor : null
    totalRows.value = response.totalRows ?? totalRows.value
    loaded.value = true
  } catch (error) {
    if (mine === generation && error?.name !== 'AbortError') failure.value = errorMessage(error)
  } finally {
    if (mine === generation) loading.value = false
  }
}
onBeforeUnmount(() => {
  generation += 1
  controller?.abort()
})
</script>

<template>
  <section class="cr-block" :data-kind="block.kind" :aria-label="block.title">
    <header class="cr-block-heading">
      <h4>{{ block.title }}</h4>
      <span v-if="block.completeResult === false && block.kind === 'TABLE'" class="cr-preview-tag"
        >部分内容</span
      >
    </header>
    <dl v-if="block.kind === 'METRIC'" class="cr-metrics">
      <div v-for="(item, index) in metrics" :key="index">
        <dt>{{ item.label }}</dt>
        <dd>
          {{ displayValue(item.value) }}<small v-if="item.unit">{{ item.unit }}</small>
        </dd>
        <p v-if="item.note">{{ item.note }}</p>
      </div>
    </dl>
    <CampaignReportChart v-if="block.kind === 'CHART'" :payload="payload" :title="block.title" />
    <div v-if="table" class="cr-table-area" :aria-busy="loading">
      <div v-if="block.kind === 'RESULT_LINK' && !loaded" class="cr-result-link">
        <RIcon name="database" :size="24" />
        <div>
          <strong>{{ payload.label || '查看完整数据' }}</strong>
          <p>
            {{
              totalRows == null
                ? '按当前报告版本读取明细'
                : `共 ${displayValue(totalRows)} 条，按页查看`
            }}
          </p>
        </div>
        <RButton kind="secondary" :disabled="loading" @click="load()">{{
          loading ? '正在载入' : '打开数据'
        }}</RButton>
      </div>
      <template v-if="loaded">
        <p v-if="hasUnknownDimensions" class="cr-muted">
          未知（UNKNOWN）表示维度信息缺失，不代表访问量为 0。
        </p>
        <button
          v-if="compactColumns"
          type="button"
          class="cr-column-toggle"
          :aria-expanded="showAllColumns"
          @click="showAllColumns = !showAllColumns"
        >
          {{ showAllColumns ? '收起辅助字段' : `查看全部 ${columns.length} 个字段` }}
        </button>
        <div
          v-if="visibleRows.length"
          class="cr-table-wrap"
          tabindex="0"
          role="region"
          :aria-label="`${block.title}，可横向滚动`"
        >
          <table>
            <thead>
              <tr>
                <th
                  v-for="column in displayColumns"
                  :key="column.key"
                  :class="{ 'cr-structured-column': column.structured }"
                  scope="col"
                >
                  {{ column.label }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in tableRows" :key="index">
                <td
                  v-for="cell in row"
                  :key="cell.key"
                  :class="{ 'cr-structured-column': cell.structured }"
                >
                  <template v-if="cell.structured">
                    <dl v-if="cell.entries.length" class="cr-cell-facts">
                      <div v-for="entry in cell.entries" :key="entry.label">
                        <dt>{{ entry.label }}</dt>
                        <dd>{{ entry.value }}</dd>
                      </div>
                    </dl>
                    <span v-else class="cr-muted">{{ cell.summary }}</span>
                    <details class="cr-cell-details">
                      <summary>完整字段</summary>
                      <pre
                        class="cr-raw-content"
                        tabindex="0"
                        :aria-label="`${columnLabels[cell.key] || cell.key}完整字段`"
                        >{{ cell.raw }}</pre>
                    </details>
                  </template>
                  <span v-else :title="cell.raw">{{ cell.text }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="cr-muted">本次返回的范围内没有明细行。</p>
        <footer class="cr-pagination">
          <span
            >第 {{ pageNumber }} 页 · 本页 {{ visibleRows.length }} 条<span
              v-if="totalRows != null"
            >
              / 共 {{ displayValue(totalRows) }} 条</span
            ></span
          >
          <div>
            <RButton v-if="embeddedRanking && pageNumber > 1" kind="text" @click="pageNumber -= 1"
              >上一页</RButton
            >
            <RButton
              v-if="embeddedRanking && pageNumber < embeddedPages"
              kind="secondary"
              @click="pageNumber += 1"
              >下一页</RButton
            >
            <RButton
              v-if="!embeddedRanking && cursorHistory.length"
              kind="text"
              :disabled="loading"
              @click="load('previous')"
              >上一页</RButton
            >
            <RButton
              v-if="!embeddedRanking && nextCursor"
              kind="secondary"
              :disabled="loading"
              @click="load('next')"
              >{{ loading ? '正在载入' : '下一页' }}</RButton
            >
          </div>
        </footer>
      </template>
      <p v-if="failure" class="cr-inline-error" role="alert">{{ failure }}</p>
    </div>
    <AgentAnswer v-if="block.text" :text="block.text" />
    <template v-if="!known"
      ><p class="cr-muted">此内容类型暂未支持图形展示，原始内容保留如下。</p>
      <pre class="cr-raw-content">{{ safeText(JSON.stringify(payload, null, 2)) }}</pre>
    </template>
    <details v-if="block.evidenceArtifactIds?.length" class="cr-evidence-refs">
      <summary>{{ block.evidenceArtifactIds.length }} 项证据依据</summary>
      <ul>
        <li v-for="id in block.evidenceArtifactIds" :key="id">
          <code>{{ id }}</code>
        </li>
      </ul>
    </details>
  </section>
</template>
