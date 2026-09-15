<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { metric, riskLevel, riskTone } from '../domain/riskModel.js'
import {
  buildRiskTrendGeometry,
  buildRiskTrendModel,
  formatRiskTrendTick
} from '../domain/riskTrend.js'

const props = defineProps({
  points: { type: Array, default: () => [] },
  unavailable: { type: Boolean, default: false }
})

const mode = ref('chart')
const stage = ref(null)
const width = ref(480)
const height = ref(140)
const hoverIndex = ref(null)
const focusIndex = ref(null)
const tabIndex = ref(0)
let observer

const model = computed(() => buildRiskTrendModel(props.points))
const rows = computed(() => model.value.rows)
const datedRows = computed(() => model.value.datedRows)
const invalidDateCount = computed(() => model.value.invalidDateCount)
const missingScoreCount = computed(() => model.value.missingScoreCount)
const activeIndex = computed(() => hoverIndex.value ?? focusIndex.value)
const active = computed(
  () => datedRows.value[activeIndex.value] || datedRows.value[datedRows.value.length - 1] || null
)
const chart = computed(() =>
  buildRiskTrendGeometry(datedRows.value, { width: width.value, height: height.value })
)

watch(stage, (element) => {
  observer?.disconnect()
  if (!element) return
  const resize = (rect) => {
    if (rect.width > 0) width.value = Math.max(160, Math.round(rect.width))
    if (rect.height > 0) height.value = Math.max(90, Math.round(rect.height))
  }
  resize(element.getBoundingClientRect())
  observer = new ResizeObserver(([entry]) => resize(entry.contentRect))
  observer.observe(element)
})
watch(
  () => [props.points, props.unavailable],
  () => {
    hoverIndex.value = null
    focusIndex.value = null
    tabIndex.value = 0
  }
)
onBeforeUnmount(() => observer?.disconnect())

function description(point) {
  return `${point.date}，风险分数 ${metric(point.score)}，${riskLevel(point.level)}`
}

function onFocus(index) {
  hoverIndex.value = null
  focusIndex.value = index
  tabIndex.value = index
}

function onKeydown(event, index) {
  const last = datedRows.value.length - 1
  const next = {
    ArrowRight: Math.min(last, index + 1),
    ArrowDown: Math.min(last, index + 1),
    ArrowLeft: Math.max(0, index - 1),
    ArrowUp: Math.max(0, index - 1),
    Home: 0,
    End: last
  }[event.key]
  if (next === undefined) return
  event.preventDefault()
  tabIndex.value = next
  event.currentTarget.parentElement.querySelector(`[data-risk-point="${next}"]`)?.focus()
}
</script>

<template>
  <div class="risk-trend-chart" :class="{ 'is-narrow': width < 280 }">
    <div class="risk-trend-toolbar">
      <div class="risk-trend-readout" aria-live="polite" aria-atomic="true">
        <template v-if="active && !unavailable && mode === 'chart'">
          <span>{{ active.date }}</span>
          <strong>{{ metric(active.score) }} 分</strong>
          <span :class="`risk-trend-level--${riskTone(active.level)}`">{{
            riskLevel(active.level)
          }}</span>
        </template>
        <span v-else>风险分数 · 按实际画像日期</span>
      </div>
      <div class="risk-trend-mode" role="group" aria-label="风险趋势展示方式">
        <button
          type="button"
          :aria-pressed="mode === 'chart'"
          :disabled="unavailable || !rows.length"
          @click="mode = 'chart'"
        >
          图
        </button>
        <button
          type="button"
          :aria-pressed="mode === 'table'"
          :disabled="unavailable || !rows.length"
          @click="mode = 'table'"
        >
          表
        </button>
      </div>
    </div>

    <p v-if="unavailable" class="risk-trend-empty" role="status">尚未生成风险画像。</p>
    <p v-else-if="!rows.length" class="risk-trend-empty" role="status">尚未返回风险趋势数据。</p>
    <div
      v-else-if="mode === 'table'"
      class="risk-trend-table-scroll"
      role="region"
      aria-label="风险趋势数据表"
    >
      <table class="risk-trend-table">
        <caption class="risk-trend-sr-only">
          已返回的风险画像日期与原始分数
        </caption>
        <thead>
          <tr>
            <th scope="col">日期</th>
            <th scope="col">风险分数</th>
            <th scope="col">等级</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="point in rows" :key="point.id">
            <th scope="row">{{ point.date }}</th>
            <td>{{ metric(point.score) }}</td>
            <td :class="`risk-trend-level--${riskTone(point.level)}`">
              {{ riskLevel(point.level) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else-if="!datedRows.length" class="risk-trend-empty" role="status">
      尚未返回可定位日期，请切换表格查看原始数据。
    </p>
    <div v-else ref="stage" class="risk-trend-stage">
      <svg
        :viewBox="`0 0 ${chart.width} ${chart.height}`"
        role="group"
        aria-label="风险分数趋势，方向键切换观测日期，Home 和 End 跳到首尾"
        @mouseleave="hoverIndex = null"
      >
        <g class="risk-trend-axis" aria-hidden="true">
          <g
            v-for="tick in chart.ticks"
            :key="tick.value"
            :class="{
              'is-threshold': tick.value === 40 || tick.value === 70,
              'is-high-threshold': tick.value === 70
            }"
          >
            <line :x1="chart.left" :x2="chart.width - 12" :y1="tick.y" :y2="tick.y" />
            <text v-if="tick.labelled" :x="chart.left - 8" :y="tick.y + 3.5" text-anchor="end">
              {{ formatRiskTrendTick(tick.value) }}
            </text>
          </g>
          <text
            v-for="point in chart.dates"
            :key="point.stamp"
            :x="point.x"
            :y="chart.height - 5"
            text-anchor="middle"
          >
            {{ point.date.slice(5) }}
          </text>
        </g>
        <g class="risk-trend-connections" aria-hidden="true">
          <line
            v-for="segment in chart.segments"
            :key="segment.to.id"
            :x1="segment.from.x"
            :y1="segment.from.y"
            :x2="segment.to.x"
            :y2="segment.to.y"
          />
        </g>
        <g>
          <g
            v-for="point in chart.points"
            :key="point.id"
            class="risk-trend-point"
            :class="[
              `risk-trend-point--${riskTone(point.level)}`,
              { active: activeIndex === point.index }
            ]"
            role="img"
            :aria-label="description(point)"
            :tabindex="tabIndex === point.index ? 0 : -1"
            :data-risk-point="point.index"
            @mouseenter="hoverIndex = point.index"
            @mouseleave="hoverIndex = null"
            @focus="onFocus(point.index)"
            @blur="focusIndex = null"
            @keydown="onKeydown($event, point.index)"
          >
            <title>{{ description(point) }}</title>
            <rect
              class="risk-trend-hit"
              :x="point.x - point.hitWidth / 2"
              :y="chart.top - 5"
              :width="point.hitWidth"
              :height="chart.bottom - chart.top + 12"
              rx="4"
            />
            <template v-if="point.score !== null">
              <rect
                class="risk-trend-bar"
                :x="point.x - point.barWidth / 2"
                :y="point.barY"
                :width="point.barWidth"
                :height="point.barHeight"
                rx="2"
              />
              <circle class="risk-trend-dot" :cx="point.x" :cy="point.y" r="3.2" />
            </template>
            <text
              v-else
              class="risk-trend-unknown"
              :x="point.x"
              :y="chart.top + 15"
              text-anchor="middle"
              aria-hidden="true"
            >
              —
            </text>
          </g>
        </g>
      </svg>
    </div>

    <p class="risk-trend-footnote">
      <template v-if="invalidDateCount">{{ invalidDateCount }} 项日期未识别，原值见表格。</template>
      <template v-else-if="missingScoreCount">— 表示未提供分数；缺少日期不补零。</template>
      <template v-else>仅展示已返回画像；缺少日期不补零。</template>
    </p>
  </div>
</template>

<style scoped>
.risk-trend-chart {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.risk-trend-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 26px;
  flex: none;
}
.risk-trend-readout {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  overflow: hidden;
  color: var(--muted);
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.risk-trend-readout strong {
  color: var(--text);
  font-size: 12px;
  font-weight: 650;
}
.risk-trend-chart.is-narrow .risk-trend-toolbar {
  align-items: flex-start;
}
.risk-trend-chart.is-narrow .risk-trend-readout {
  display: grid;
  grid-template-columns: auto auto;
  gap: 0 6px;
  white-space: normal;
}
.risk-trend-chart.is-narrow .risk-trend-readout > :first-child {
  grid-column: 1 / -1;
}
.risk-trend-mode {
  display: flex;
  gap: 2px;
  padding: 2px;
  flex: none;
  border-radius: 6px;
  background: var(--subtle, #f0f3f9);
}
.risk-trend-mode button {
  min-width: 27px;
  padding: 2px 5px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--muted);
  font: inherit;
  font-size: 11px;
  line-height: 18px;
  cursor: pointer;
}
.risk-trend-mode button[aria-pressed='true'] {
  background: var(--surface, #fff);
  color: #244f45;
  box-shadow: 0 1px 3px #18284712;
}
.risk-trend-mode button:disabled {
  cursor: default;
  opacity: 0.5;
}
.risk-trend-mode button:focus-visible,
.risk-trend-table-scroll:focus-visible {
  outline: 2px solid #2d8471;
  outline-offset: 2px;
}
.risk-trend-stage {
  flex: 1;
  min-height: 0;
  min-width: 0;
}
.risk-trend-stage svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}
.risk-trend-axis line {
  stroke: var(--line);
  stroke-width: 1;
  stroke-dasharray: 3 4;
}
.risk-trend-axis .is-threshold line {
  stroke: #be8426;
  opacity: 0.3;
}
.risk-trend-axis .is-high-threshold line {
  stroke: #c44949;
}
.risk-trend-axis text {
  fill: var(--muted);
  font-size: 10px;
  font-variant-numeric: tabular-nums;
}
.risk-trend-connections line {
  stroke: #8a9790;
  stroke-width: 1.2;
  opacity: 0.6;
}
.risk-trend-point {
  --point-color: var(--muted);
  outline: none;
}
.risk-trend-point--success {
  --point-color: #2d8471;
}
.risk-trend-point--warning {
  --point-color: #be8426;
}
.risk-trend-point--danger {
  --point-color: #c44949;
}
.risk-trend-hit {
  fill: transparent;
  stroke: transparent;
  stroke-width: 1.5;
}
.risk-trend-bar {
  fill: var(--point-color);
  opacity: 0.15;
  pointer-events: none;
}
.risk-trend-dot {
  fill: var(--surface, #fff);
  stroke: var(--point-color);
  stroke-width: 1.7;
  pointer-events: none;
}
.risk-trend-unknown {
  fill: var(--muted);
  font-size: 12px;
  pointer-events: none;
}
.risk-trend-point.active .risk-trend-bar {
  opacity: 0.3;
}
.risk-trend-point.active .risk-trend-dot {
  fill: var(--point-color);
}
.risk-trend-point:focus-visible .risk-trend-hit {
  stroke: var(--point-color);
  stroke-dasharray: 3 2;
}
.risk-trend-empty {
  display: grid;
  place-items: center;
  flex: 1;
  margin: 0;
  padding: 12px;
  color: var(--muted);
  font-size: 12px;
  text-align: center;
  line-height: 1.7;
}
.risk-trend-footnote {
  flex: none;
  margin: 0;
  color: var(--muted);
  font-size: 10px;
  line-height: 16px;
}
.risk-trend-table-scroll {
  flex: none;
  min-height: 0;
  overflow: visible;
}
.risk-trend-chart:has(.risk-trend-table) {
  height: auto;
}
.risk-trend-table {
  width: 100%;
  border-collapse: collapse;
  color: var(--text);
  text-align: left;
  font-size: 12px;
  line-height: 18px;
  font-variant-numeric: tabular-nums;
}
.risk-trend-table th,
.risk-trend-table td {
  padding: 5px 8px;
  border-bottom: 1px solid var(--line);
  overflow-wrap: anywhere;
}
.risk-trend-table thead th {
  position: sticky;
  top: 0;
  background: var(--surface, #fff);
  color: var(--muted);
  font-weight: 500;
}
.risk-trend-table tbody th {
  font-weight: 400;
}
.risk-trend-table th:nth-child(2),
.risk-trend-table td:nth-child(2) {
  text-align: right;
}
.risk-trend-level--success {
  color: #2d8471;
}
.risk-trend-level--warning {
  color: #be8426;
}
.risk-trend-level--danger {
  color: #c44949;
}
.risk-trend-level--unknown {
  color: var(--muted);
}
.risk-trend-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
