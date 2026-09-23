<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import echarts from '../../plugins/echarts.js'
import { displayValue } from '../domain/campaignReport.js'

const props = defineProps({
  payload: { type: Object, required: true },
  title: { type: String, default: '数据图表' }
})
const canvas = ref(null)
const labels = computed(() =>
  Array.isArray(props.payload.labels) ? props.payload.labels.map(String) : []
)
const series = computed(() =>
  Array.isArray(props.payload.series)
    ? props.payload.series.filter((item) => Array.isArray(item.values))
    : []
)
const supported = computed(
  () =>
    ['BAR', 'LINE'].includes(props.payload.chartType) && labels.value.length && series.value.length
)
let chart
let observer
function render() {
  if (!canvas.value || !supported.value) {
    chart?.dispose()
    chart = null
    return
  }
  chart ||= echarts.init(canvas.value)
  chart.setOption(
    {
      animation: !globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches,
      color: ['#3158df', '#d59720', '#33826e', '#9462b0'],
      tooltip: { trigger: 'axis', renderMode: 'richText', confine: true },
      legend: { type: 'scroll', bottom: 0, textStyle: { color: '#425572', fontSize: 12 } },
      grid: { left: 14, right: 18, top: 30, bottom: 46, containLabel: true },
      xAxis: {
        type: 'category',
        data: labels.value,
        axisLine: { lineStyle: { color: '#d6e2f3' } },
        axisTick: { show: false },
        axisLabel: { color: '#536884', hideOverlap: true }
      },
      yAxis: {
        type: 'value',
        name: String(props.payload.unit || ''),
        axisLabel: { color: '#536884' },
        splitLine: { lineStyle: { color: '#edf2f8', type: 'dashed' } }
      },
      series: series.value.map((item) => ({
        name: String(item.name || '数据'),
        type: props.payload.chartType === 'LINE' ? 'line' : 'bar',
        barMaxWidth: 34,
        symbolSize: 6,
        connectNulls: false,
        data: labels.value.map((_, index) =>
          typeof item.values[index] === 'number' && Number.isFinite(item.values[index])
            ? item.values[index]
            : null
        )
      }))
    },
    true
  )
}
onMounted(() => {
  render()
  observer = new ResizeObserver(() => chart?.resize())
  if (canvas.value) observer.observe(canvas.value)
})
watch(() => props.payload, render, { deep: true, flush: 'post' })
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div class="cr-chart">
    <div
      v-show="supported"
      ref="canvas"
      class="cr-chart-canvas"
      role="img"
      :aria-label="`${title}，完整数值见下方数据表`"
    />
    <p v-if="!supported" class="cr-muted">当前图表类型无法绘制，数值仍保留在下方。</p>
    <details class="cr-chart-data">
      <summary>查看图表数值</summary>
      <div class="cr-table-wrap" tabindex="0" role="region" :aria-label="`${title}数据表`">
        <table>
          <thead>
            <tr>
              <th scope="col">项目</th>
              <th v-for="(item, index) in series" :key="index" scope="col">{{ item.name }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(label, index) in labels" :key="index">
              <th scope="row">{{ label }}</th>
              <td v-for="(item, seriesIndex) in series" :key="seriesIndex">
                {{ displayValue(item.values[index]) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
