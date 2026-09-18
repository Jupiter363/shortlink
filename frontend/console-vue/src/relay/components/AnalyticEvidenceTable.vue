<script setup>
import { computed, ref, watch } from 'vue'
import {
  analyticDimensionLabel,
  analyticDimensionValue,
  analyticNumber,
  analyticPage,
  analyticQuality,
  analyticRate,
  array,
  object
} from '../domain/agentModel.js'

const props = defineProps({ card: { type: Object, required: true } })
const metricKeys = ['pv', 'uv', 'uip']
const isRanking = computed(() => props.card.type === 'ranking')
const isDimension = computed(() => props.card.type === 'dimension_breakdown')
const rows = computed(() => array(props.card.rows))
const meta = computed(() => object(props.card.meta))
const dimensions = computed(() => array(props.card.dimensions))
const filters = computed(() => array(props.card.filters))
const currentPage = ref(1)
const pageSize = ref(25)
const page = computed(() => analyticPage(rows.value, currentPage.value, pageSize.value))
const visibleRows = computed(() => (isDimension.value ? page.value.rows : rows.value))
const resultIncomplete = computed(
  () =>
    isDimension.value &&
    (meta.value.resultComplete === false ||
      (typeof meta.value.totalRows === 'number' && meta.value.totalRows > rows.value.length))
)
watch([() => props.card, pageSize], () => {
  currentPage.value = 1
})
const pending = computed(() => props.card.status === 'PENDING')
const incomplete = computed(() => props.card.status === 'INCOMPLETE' || resultIncomplete.value)
const statusLabel = computed(() => {
  if (pending.value) return '查询处理中'
  if (incomplete.value) return '结果尚未齐全'
  return props.card.status === 'READY' ? '结果已返回' : '结果状态待核实'
})
const requestedMetrics = computed(() => {
  const metrics = object(props.card.metrics)
  return object(metrics.requested || metrics)
})
const hasMetrics = computed(() => metricKeys.some((key) => requestedMetrics.value[key] != null))
const rowIndex = computed(() => new Map(rows.value.map((row) => [row.key, row])))
const comparisonGroups = computed(() => {
  const groups = new Map()
  for (const change of array(props.card.comparisons)) {
    const key = JSON.stringify([change.targetKey, change.baselineKey])
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        target: rowIndex.value.get(change.targetKey),
        baseline: rowIndex.value.get(change.baselineKey),
        metrics: {},
        comparable: true,
        warnings: new Set()
      })
    }
    const group = groups.get(key)
    group.metrics[change.metric] = change
    group.comparable = group.comparable && change.comparable === true
    for (const warning of array(change.warnings)) group.warnings.add(warning)
  }
  return [...groups.values()].map((group) => ({ ...group, warnings: [...group.warnings] }))
})
const hasPartial = computed(
  () =>
    meta.value.completeness === 'PARTIAL' ||
    rows.value.some((row) => row.quality?.completeness === 'PARTIAL')
)
const quality = computed(() => analyticQuality(meta.value))
const metricLabel = computed(() => {
  const metric = String(meta.value.metric || '').toLowerCase()
  return metricKeys.includes(metric) ? metric.toUpperCase() : '指定指标'
})

function scopeLabel(row) {
  if (!row) return '对象未返回'
  return row.scopeLabel || row.label || row.fullShortUrl || row.linkId || row.gid || '对象未提供'
}

function periodLabel(row) {
  if (!row?.startDate || !row?.endDate) return '期间未提供'
  return row.startDate === row.endDate ? row.startDate : `${row.startDate} 至 ${row.endDate}`
}

function rateNote(group, metric) {
  const change = group.metrics[metric]
  if (!change) return '变化率未返回'
  if (change.rate == null) {
    return group.baseline?.[metric] === 0 ? '基期为 0，不计算变化率' : '变化率未提供'
  }
  return analyticRate(change.rate, true)
}

function filterLabel(filter) {
  const dimension = analyticDimensionLabel(filter.dimension)
  if (filter.operator === 'IS_UNKNOWN') return `${dimension}：未知`
  const values =
    array(filter.values)
      .map((value) => String(value))
      .join('、') || '未提供条件值'
  return filter.operator === 'IN'
    ? `${dimension}：${values}`
    : `${dimension} · ${filter.operator || '运算符未提供'}：${values}`
}
</script>

<template>
  <div class="ae-content">
    <div class="ae-context">
      <RBadge :tone="pending ? 'info' : incomplete ? 'warning' : 'unknown'">{{
        statusLabel
      }}</RBadge>
      <span v-if="isRanking">
        按 {{ metricLabel }} 排名 · 已展示 {{ rows.length }} 条
        <template v-if="meta.totalLinks != null">
          / 共 {{ analyticNumber(meta.totalLinks) }} 条短链</template
        >
      </span>
      <span v-else-if="isDimension"
        >{{ dimensions.map(analyticDimensionLabel).join(' × ') }} · 已返回
        {{ analyticNumber(rows.length) }} 组</span
      >
      <span v-else>{{ rows.length }} 组对象与期间 · PV / UV / UIP</span>
      <RBadge v-if="Object.keys(meta).length" :tone="quality.tone">{{ quality.label }}</RBadge>
    </div>

    <dl v-if="isDimension" class="ae-query-scope">
      <div>
        <dt>分析对象</dt>
        <dd>{{ scopeLabel(card) }}</dd>
      </div>
      <div>
        <dt>统计期间</dt>
        <dd>{{ periodLabel(card) }}</dd>
      </div>
      <div class="ae-filter-row">
        <dt>筛选条件</dt>
        <dd v-if="filters.length" class="ae-filter-list">
          <span v-for="(filter, index) in filters" :key="index" class="ae-filter">{{
            filterLabel(filter)
          }}</span>
          <small>同时满足以上条件；同一条件内匹配任一值</small>
        </dd>
        <dd v-else>未附加维度筛选</dd>
      </div>
    </dl>

    <p v-if="pending" class="ae-notice" role="status">
      查询仍在处理。可在当前会话继续询问“查看结果”，返回前不以空值推断流量。
    </p>
    <p v-else-if="incomplete" class="ae-notice" role="status">
      {{
        isDimension
          ? '维度查询尚未返回完整结果，当前内容不足以确认完整分布。'
          : '部分查询尚未返回可用结果，当前内容不足以确认完整对比或排名。'
      }}
    </p>
    <p v-if="hasPartial" class="ae-notice">
      当前包含部分采集数据。表中数值是已观测值，不代表完整流量。
    </p>
    <ul v-if="card.warnings?.length" class="ae-warnings" aria-label="结果说明">
      <li v-for="(warning, index) in card.warnings" :key="index">{{ warning }}</li>
    </ul>

    <div v-if="(isRanking || isDimension) && hasMetrics" class="ae-summary">
      <p>
        {{ isDimension ? '筛选范围内已观测总量' : '范围内已观测总量'
        }}<small
          >UV / UIP 按范围独立去重，{{ isDimension ? '不累加各维度组合' : '不累加短链明细' }}</small
        >
      </p>
      <dl>
        <div v-for="metric in metricKeys" :key="metric">
          <dt>{{ metric.toUpperCase() }}</dt>
          <dd>{{ analyticNumber(requestedMetrics[metric]) }}</dd>
        </div>
      </dl>
    </div>

    <div
      v-if="rows.length"
      class="ar-table-scroll ae-table-scroll"
      role="region"
      :aria-label="
        isDimension
          ? '联合维度分布，可横向滚动'
          : isRanking
            ? '短链排名，可横向滚动'
            : '对象与期间统计，可横向滚动'
      "
      tabindex="0"
    >
      <table>
        <caption>
          {{
            isDimension
              ? '联合维度的已观测分布'
              : isRanking
                ? '短链表现排名'
                : '各对象与期间的已观测数据'
          }}
        </caption>
        <thead>
          <tr>
            <th v-if="isRanking" scope="col" class="ae-rank">排名</th>
            <template v-if="isDimension">
              <th v-for="dimension in dimensions" :key="dimension" scope="col">
                {{ analyticDimensionLabel(dimension) }}
              </th>
            </template>
            <template v-else>
              <th scope="col">{{ isRanking ? '短链' : '分析对象' }}</th>
              <th scope="col">统计期间</th>
            </template>
            <th v-for="metric in metricKeys" :key="metric" scope="col" class="ae-number">
              {{ metric.toUpperCase() }}
            </th>
            <th v-if="isRanking || isDimension" scope="col" class="ae-number">PV 占比</th>
            <th v-if="!isDimension" scope="col">数据质量</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in visibleRows" :key="row.key || row.linkId || index">
            <td v-if="isRanking" class="ae-rank">{{ analyticNumber(row.rank) }}</td>
            <template v-if="isDimension">
              <th
                v-for="dimension in dimensions"
                :key="dimension"
                scope="row"
                class="ae-dimension"
                :class="{ 'ae-dimension-unknown': row.dimensions?.[dimension]?.state !== 'KNOWN' }"
              >
                {{ analyticDimensionValue(row.dimensions?.[dimension], dimension) }}
              </th>
            </template>
            <template v-else>
              <th scope="row" class="ae-scope">
                {{ scopeLabel(row) }}
                <small v-if="row.gid && (row.fullShortUrl || row.linkId || row.label)"
                  >分组 {{ row.gid }}</small
                >
              </th>
              <td class="ae-period">{{ periodLabel(row) }}</td>
            </template>
            <td v-for="metric in metricKeys" :key="metric" class="ae-number">
              {{ analyticNumber(row[metric]) }}
            </td>
            <td v-if="isRanking || isDimension" class="ae-number">
              {{ analyticRate(isDimension ? row.pvRatio : row.pvShare) }}
            </td>
            <td v-if="!isDimension" class="ae-quality">
              <RBadge :tone="analyticQuality(row.quality).tone">{{
                analyticQuality(row.quality).label
              }}</RBadge>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else-if="!pending && !incomplete" class="ae-empty">本次未返回可展示的统计行。</p>

    <div v-if="isDimension && rows.length" class="ae-pagination" aria-label="维度结果展示分页">
      <p aria-live="polite">
        第 {{ page.from }}–{{ page.to }} 组 / 已返回 {{ analyticNumber(page.total) }} 组<small
          v-if="meta.totalRows != null && meta.totalRows !== rows.length"
          >查询共 {{ analyticNumber(meta.totalRows) }} 组</small
        >
      </p>
      <div class="ae-page-controls">
        <label
          >每页
          <select v-model.number="pageSize" aria-label="每页展示的维度组合数">
            <option :value="25">25 组</option>
            <option :value="50">50 组</option>
          </select></label
        >
        <RButton
          kind="secondary"
          class="ae-page-button"
          :disabled="page.page <= 1"
          @click="currentPage = page.page - 1"
          >上一页</RButton
        >
        <span>{{ page.page }} / {{ page.pageCount }}</span>
        <RButton
          kind="secondary"
          class="ae-page-button"
          :disabled="page.page >= page.pageCount"
          @click="currentPage = page.page + 1"
          >下一页</RButton
        >
      </div>
    </div>
    <p v-if="isDimension" class="ae-footnote">
      每行是所选维度的联合分组。PV
      占比分母为筛选范围总量，包含未知值；维度覆盖率仅描述筛选后的范围。分页只切换已返回结果的展示。
    </p>

    <section
      v-if="!isRanking && !isDimension && comparisonGroups.length"
      class="ae-changes"
      aria-label="相对基准变化"
    >
      <div class="ae-section-heading">
        <h4>相对基准变化</h4>
        <p>差值 = 对比值 − 基准值；变化率以基准值计算</p>
      </div>
      <div
        class="ar-table-scroll ae-table-scroll"
        role="region"
        aria-label="指标变化，可横向滚动"
        tabindex="0"
      >
        <table>
          <caption class="ae-visually-hidden">
            对象与期间之间的 PV、UV、UIP 差值和变化率
          </caption>
          <thead>
            <tr>
              <th scope="col">对比对象 / 期间</th>
              <th scope="col">基准对象 / 期间</th>
              <th v-for="metric in metricKeys" :key="metric" scope="col" class="ae-number">
                {{ metric.toUpperCase() }} 变化
              </th>
              <th scope="col">可比性</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="group in comparisonGroups" :key="group.key">
              <th scope="row" class="ae-scope">
                {{ scopeLabel(group.target) }}<small>{{ periodLabel(group.target) }}</small>
              </th>
              <td class="ae-scope">
                {{ scopeLabel(group.baseline) }}<small>{{ periodLabel(group.baseline) }}</small>
              </td>
              <td v-for="metric in metricKeys" :key="metric" class="ae-number">
                <strong>{{ analyticNumber(group.metrics[metric]?.delta, true) }}</strong>
                <small>{{ rateNote(group, metric) }}</small>
              </td>
              <td class="ae-quality">
                <RBadge :tone="group.comparable ? 'info' : 'warning'">{{
                  group.comparable ? '可直接比较' : '仅供观察'
                }}</RBadge>
                <small v-for="warning in group.warnings" :key="warning">{{ warning }}</small>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <p class="ae-footnote">— 表示未提供或无法计算。日期按 Asia/Shanghai 统计。</p>
  </div>
</template>

<style scoped>
.ae-content {
  display: grid;
  min-width: 0;
  gap: 12px;
}
.ae-context {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  color: var(--muted);
  font-size: 12px;
}
.ae-query-scope {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 20px;
  margin: 0;
  padding: 12px 0;
  border-block: 1px solid var(--line);
}
.ae-query-scope > div {
  min-width: 0;
}
.ae-query-scope dt {
  margin-bottom: 4px;
  color: var(--muted);
  font-size: 11px;
}
.ae-query-scope dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.ae-filter-row {
  grid-column: 1 / -1;
}
.ae-filter-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
}
.ae-filter {
  padding: 3px 8px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--surface);
  font-size: 12px;
}
.ae-filter-list small {
  width: 100%;
  color: var(--muted);
}
.ae-notice {
  padding: 10px 12px;
  border-left: 3px solid var(--yellow);
  background: var(--surface);
  color: var(--warning);
}
.ae-warnings {
  margin: 0;
  padding-left: 20px;
  color: var(--muted);
  font-size: 12px;
}
.ae-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 24px;
  padding: 14px 0;
  border-block: 1px solid var(--line);
}
.ae-summary small {
  display: block;
  color: var(--muted);
  font-size: 12px;
}
.ae-summary dl {
  display: flex;
  gap: 24px;
  margin: 0;
}
.ae-summary dt {
  color: var(--muted);
  font-size: 11px;
}
.ae-summary dd {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.ae-table-scroll {
  max-width: 100%;
  margin-top: 0;
}
.ae-table-scroll:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 3px;
}
.ae-table-scroll table {
  min-width: 730px;
}
.ae-table-scroll tbody th {
  background: transparent;
  color: var(--text);
}
.ae-table-scroll :is(th, td) {
  padding: 12px 10px;
}
.ae-table-scroll .ae-number {
  min-width: 70px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.ae-number strong {
  font-weight: 650;
}
.ae-number small {
  min-width: 84px;
  font-size: 11px;
}
.ae-rank {
  width: 48px;
  font-variant-numeric: tabular-nums;
}
.ae-scope {
  min-width: 130px;
  max-width: 230px;
  word-break: break-word;
}
.ae-scope small {
  font-weight: 400;
}
.ae-period {
  min-width: 145px;
  font-size: 12px;
}
.ae-dimension {
  min-width: 110px;
  max-width: 230px;
}
.ae-table-scroll .ae-dimension-unknown {
  color: var(--muted);
  font-weight: 400;
}
.ae-quality {
  min-width: 130px;
  max-width: 230px;
}
.ae-quality :deep(.r-badge) {
  white-space: normal;
}
.ae-changes {
  min-width: 0;
  margin-top: 8px;
}
.ae-pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 20px;
  font-size: 12px;
}
.ae-pagination p {
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.ae-pagination small {
  display: block;
}
.ae-page-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.ae-page-controls label {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ae-page-controls select {
  min-height: 34px;
  padding: 4px 6px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
}
.ae-page-controls select:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}
.ae-page-controls .ae-page-button {
  min-height: 34px;
  padding: 5px 10px;
  font-size: 12px;
}
.ae-section-heading {
  margin-bottom: 12px;
}
.ae-section-heading h4 {
  margin: 0;
  font-size: 14px;
}
.ae-section-heading p,
.ae-footnote,
.ae-empty {
  color: var(--muted);
  font-size: 12px;
}
.ae-empty {
  padding: 16px 0;
}
.ae-visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@media (max-width: 640px) {
  .ae-query-scope {
    grid-template-columns: minmax(0, 1fr);
  }
  .ae-summary dl {
    justify-content: space-between;
    width: 100%;
  }
}
</style>
