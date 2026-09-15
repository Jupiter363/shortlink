<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { dimensionView, formatCount, formatRatio, formatShanghaiDate } from '../domain/analytics.js'

const props = defineProps({
  model: { type: Object, required: true },
  title: { type: String, default: '访问维度' },
  dimensions: { type: Array, required: true }
})

const selectedKey = ref(props.dimensions[0]?.key || '')
const chartElement = ref(null)
const chartWidth = ref(300)
const hoverIndex = ref(null)
const focusIndex = ref(null)
const tabIndex = ref(0)
let chartObserver

watch(
  () => props.dimensions,
  (dimensions) => {
    if (!dimensions.some((dimension) => dimension.key === selectedKey.value))
      selectedKey.value = dimensions[0]?.key || ''
  }
)

watch(selectedKey, () => {
  hoverIndex.value = null
  focusIndex.value = null
  tabIndex.value = 0
})

const current = computed(() =>
  selectedKey.value && props.model ? dimensionView(props.model, selectedKey.value) : null
)
const isTime = computed(() => ['hour', 'weekday'].includes(selectedKey.value))
const qualityLabel = computed(
  () =>
    ({ PARTIAL: '部分数据', UNKNOWN: '完整度未知', EMPTY: '暂无数据' })[
      current.value?.quality.status
    ] || ''
)
const rows = computed(() => {
  const source = current.value?.rows || []
  if (isTime.value || selectedKey.value === 'newvisitor') return source
  return [...source].sort(
    (left, right) =>
      Number(Boolean(left.unknown)) - Number(Boolean(right.unknown)) || right.count - left.count
  )
})
const maxCount = computed(() => Math.max(0, ...rows.value.map((row) => row.count)))
const activeIndex = computed(() => hoverIndex.value ?? focusIndex.value)
const activeRow = computed(() => rows.value[activeIndex.value] || null)
const unit = computed(() => (selectedKey.value === 'newvisitor' ? '访客数' : '访问次数'))

const axisFormatter = new Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 1
})
const timeChart = computed(() => {
  const width = chartWidth.value
  const height = 116
  const top = 8
  const bottom = 94
  const left = Math.max(30, axisFormatter.format(maxCount.value).length * 8 + 10)
  const plotWidth = Math.max(24, width - left - 8)
  const scale = maxCount.value || 1
  const step = plotWidth / Math.max(1, rows.value.length)
  const barWidth = Math.max(1, step - Math.min(5, step * 0.3))
  const ticks = [...new Set([maxCount.value, Math.floor(maxCount.value / 2), 0])]
  return {
    width,
    height,
    left,
    bottom,
    ticks: ticks.map((value) => ({ value, y: bottom - (value / scale) * (bottom - top) })),
    bars: rows.value.map((row, index) => {
      const barHeight = (row.count / scale) * (bottom - top)
      return {
        ...row,
        x: left + index * step + (step - barWidth) / 2,
        y: bottom - barHeight,
        width: barWidth,
        height: barHeight,
        label: selectedKey.value === 'hour' ? row.label.slice(0, 2) : row.label,
        labelled: selectedKey.value === 'weekday' || [0, 6, 12, 18, 23].includes(index)
      }
    })
  }
})

watch(chartElement, (element) => {
  chartObserver?.disconnect()
  if (!element) return
  chartWidth.value = Math.max(160, Math.round(element.clientWidth))
  chartObserver = new ResizeObserver(([entry]) => {
    if (entry.contentRect.width > 0)
      chartWidth.value = Math.max(160, Math.round(entry.contentRect.width))
  })
  chartObserver.observe(element)
})

function focusBucket(index) {
  hoverIndex.value = null
  focusIndex.value = index
  tabIndex.value = index
}

function navigateBucket(event, index) {
  const last = rows.value.length - 1
  const next = {
    ArrowRight: Math.min(last, index + 1),
    ArrowLeft: Math.max(0, index - 1),
    Home: 0,
    End: last
  }[event.key]
  if (next === undefined) return
  event.preventDefault()
  tabIndex.value = next
  event.currentTarget.parentElement.querySelector(`[data-bucket-index="${next}"]`)?.focus()
}

function rowWidth(row) {
  return maxCount.value ? `${(row.count / maxCount.value) * 100}%` : '0%'
}

onBeforeUnmount(() => chartObserver?.disconnect())
</script>

<template>
  <article class="analytics-dimension-panel" :aria-label="title">
    <header class="dimension-heading">
      <h2>{{ title }}</h2>
      <span
        v-if="qualityLabel"
        class="dimension-quality"
        :class="{ 'dimension-quality--partial': current?.quality.status === 'PARTIAL' }"
        >{{ qualityLabel }}</span
      >
    </header>

    <div class="dimension-tabs" role="group" :aria-label="`${title}维度`">
      <button
        v-for="dimension in dimensions"
        :key="dimension.key"
        type="button"
        :aria-pressed="selectedKey === dimension.key"
        @click="selectedKey = dimension.key"
      >
        {{ dimension.label }}
      </button>
    </div>

    <div class="dimension-body" role="region" tabindex="0" :aria-label="`${title}数据，可滚动查看`">
      <template v-if="current">
        <p v-if="selectedKey === 'ip'" class="dimension-topk-note">TopK 近似排行 · 非全部 IP</p>

        <template v-if="rows.length && isTime">
          <div class="dimension-chart-readout" aria-live="polite" aria-atomic="true">
            <template v-if="activeRow">
              <span>{{ activeRow.label }}</span>
              <strong>{{ formatCount(activeRow.count) }} 次</strong>
            </template>
            <span v-else>访问次数 · 上海时间</span>
          </div>
          <div ref="chartElement" class="dimension-time-chart">
            <svg
              :viewBox="`0 0 ${timeChart.width} ${timeChart.height}`"
              role="group"
              :aria-label="`${current.label}访问次数，使用左右方向键查看各时间段`"
              @mouseleave="hoverIndex = null"
            >
              <g class="dimension-axis" aria-hidden="true">
                <g v-for="tick in timeChart.ticks" :key="tick.value">
                  <line :x1="timeChart.left" :y1="tick.y" :x2="timeChart.width - 8" :y2="tick.y" />
                  <text :x="timeChart.left - 7" :y="tick.y + 4" text-anchor="end">
                    <title>{{ formatCount(tick.value) }}</title>
                    {{ axisFormatter.format(tick.value) }}
                  </text>
                </g>
              </g>
              <g>
                <g
                  v-for="(bar, index) in timeChart.bars"
                  :key="bar.label"
                  class="dimension-time-bucket"
                  :class="{ active: activeIndex === index }"
                  role="img"
                  :aria-label="`${bar.label}：${formatCount(bar.count)} 次访问`"
                  :data-bucket-index="index"
                  :tabindex="tabIndex === index ? 0 : -1"
                  @mouseenter="hoverIndex = index"
                  @focus="focusBucket(index)"
                  @blur="focusIndex = null"
                  @keydown="navigateBucket($event, index)"
                >
                  <title>{{ bar.label }} · {{ formatCount(bar.count) }} 次访问</title>
                  <rect
                    class="dimension-bucket-hit"
                    :x="bar.x"
                    y="0"
                    :width="bar.width"
                    :height="timeChart.bottom"
                  />
                  <rect
                    class="dimension-bucket-bar"
                    :x="bar.x"
                    :y="bar.y"
                    :width="bar.width"
                    :height="bar.height"
                    rx="2"
                  />
                  <text
                    v-if="bar.labelled"
                    :x="bar.x + bar.width / 2"
                    :y="timeChart.height - 4"
                    text-anchor="middle"
                    aria-hidden="true"
                  >
                    {{ bar.label }}
                  </text>
                </g>
              </g>
            </svg>
          </div>
        </template>

        <template v-else-if="rows.length">
          <div class="dimension-columns" aria-hidden="true">
            <span>{{ current.label }}</span
            ><span>{{ unit }}</span
            ><span>占比</span>
          </div>
          <ul class="dimension-ranking" :aria-label="`${current.label}分布`">
            <li
              v-for="(row, index) in rows"
              :key="`${row.label}-${index}`"
              :class="{ 'dimension-row--unknown': row.unknown }"
            >
              <div class="dimension-row-values">
                <span class="dimension-row-label">{{ row.label }}</span>
                <strong>{{ formatCount(row.count) }}</strong>
                <span class="dimension-row-ratio">{{ formatRatio(row.ratio) }}</span>
              </div>
              <div class="dimension-row-bar" aria-hidden="true">
                <span :style="{ width: rowWidth(row) }" />
              </div>
              <span v-if="row.error !== null && row.error !== undefined" class="dimension-row-error"
                >误差 ≤ {{ formatCount(row.error) }}</span
              >
            </li>
          </ul>
        </template>

        <p v-else class="dimension-empty">
          {{
            current.quality.status === 'EMPTY' ? '所选范围暂无访问。' : '尚未返回该维度的可用数据。'
          }}
        </p>

        <details class="dimension-method">
          <summary>统计口径</summary>
          <div>
            <p>{{ current.note }}</p>
            <template v-if="selectedKey === 'newvisitor'">
              <p v-if="current.quality.historyStart && current.quality.historyEnd">
                保留数据范围：{{ formatShanghaiDate(current.quality.historyStart) }} 至
                {{ formatShanghaiDate(current.quality.historyEnd) }}
              </p>
              <p v-else>尚未提供历史保留区间。</p>
              <p>
                首次观测覆盖率：{{ formatRatio(current.quality.coverage) }}
                <template v-if="current.quality.maxHistoryDays"
                  >· 最多 {{ current.quality.maxHistoryDays }} 天</template
                >
              </p>
            </template>
            <p v-if="current.quality.reason">{{ current.quality.reason }}</p>
          </div>
        </details>
      </template>
      <p v-else class="dimension-empty">尚未返回该维度的可用数据。</p>
    </div>
  </article>
</template>

<style scoped>
.analytics-dimension-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: #fff;
  color: var(--text);
}

.dimension-heading {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dimension-heading h2 {
  margin: 0;
  font-size: 16px;
  line-height: 24px;
}

.dimension-quality {
  flex: none;
  color: var(--muted);
  font-size: 12px;
  line-height: 20px;
}

.dimension-quality--partial {
  color: #946015;
}

.dimension-tabs {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 4px;
}

.dimension-tabs button {
  min-width: 0;
  min-height: 32px;
  padding: 5px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 12px;
  line-height: 22px;
  cursor: pointer;
}

.dimension-tabs button:hover {
  background: #f3f5fa;
  color: var(--text);
}

.dimension-tabs button[aria-pressed='true'] {
  background: #edf2ff;
  color: var(--blue);
  font-weight: 700;
}

.dimension-tabs button:focus-visible,
.dimension-body:focus-visible,
.dimension-method summary:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}

.dimension-body {
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: #cbd3e1 transparent;
}

.dimension-chart-readout {
  display: flex;
  min-height: 20px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
  color: var(--muted);
  font-size: 12px;
  line-height: 20px;
}

.dimension-chart-readout strong {
  color: var(--text);
}

.dimension-time-chart {
  width: 100%;
  min-width: 0;
}

.dimension-time-chart svg {
  display: block;
  width: 100%;
  height: 116px;
  overflow: visible;
}

.dimension-time-chart text {
  fill: var(--muted);
  font-family: inherit;
  font-size: 12px;
}

.dimension-axis line {
  stroke: var(--line);
  stroke-width: 1;
}

.dimension-bucket-bar {
  fill: var(--blue);
}

.dimension-bucket-hit {
  fill: transparent;
}

.dimension-time-bucket {
  outline: none;
}

.dimension-time-bucket.active .dimension-bucket-hit,
.dimension-time-bucket:focus-visible .dimension-bucket-hit {
  fill: #edf2ff;
  stroke: var(--blue);
  stroke-width: 1;
}

.dimension-columns,
.dimension-row-values {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 48px;
  align-items: baseline;
  gap: 10px;
  font-size: 12px;
  line-height: 18px;
}

.dimension-columns {
  margin-bottom: 8px;
  color: var(--muted);
}

.dimension-columns > :not(:first-child),
.dimension-row-values > :not(:first-child) {
  text-align: right;
}

.dimension-ranking {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.dimension-row-label {
  min-width: 0;
  overflow-wrap: anywhere;
}

.dimension-row-values strong {
  font-weight: 650;
  white-space: nowrap;
}

.dimension-row-ratio {
  color: var(--muted);
  white-space: nowrap;
}

.dimension-row-bar {
  height: 4px;
  margin-top: 5px;
  border-radius: 2px;
  background: #f1f4f8;
}

.dimension-row-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--blue);
}

.dimension-row--unknown .dimension-row-bar span {
  background: #9aa6b8;
}

.dimension-row--unknown .dimension-row-label,
.dimension-row-error {
  color: var(--muted);
}

.dimension-row-error {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 18px;
}

.dimension-topk-note,
.dimension-empty {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 12px;
  line-height: 20px;
}

.dimension-empty {
  padding: 22px 0;
}

.dimension-method {
  margin-top: 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 20px;
}

.dimension-method summary {
  width: fit-content;
  max-width: 100%;
  cursor: pointer;
}

.dimension-method > div {
  padding-top: 6px;
  overflow-wrap: anywhere;
}

.dimension-method p {
  margin: 0 0 6px;
}

.dimension-chart-readout,
.dimension-time-chart text,
.dimension-row-values,
.dimension-row-error {
  font-variant-numeric: tabular-nums;
}

@media (max-width: 380px) {
  .dimension-tabs button {
    padding-inline: 8px;
  }

  .dimension-columns,
  .dimension-row-values {
    column-gap: 6px;
  }
}
</style>
