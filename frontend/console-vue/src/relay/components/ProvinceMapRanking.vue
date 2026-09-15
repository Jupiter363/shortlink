<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { formatCount, formatRatio } from '../domain/analytics.js'

const props = defineProps({
  data: { type: Object, required: true },
  activeCode: { type: String, default: '' },
  selectedCode: { type: String, default: '' }
})
const emit = defineEmits(['hover', 'select'])
const pointerCode = ref(null)
const focusCode = ref(null)
function focusRegion(code) {
  pointerCode.value = null
  focusCode.value = code
}
watch([pointerCode, focusCode], () => emit('hover', pointerCode.value || focusCode.value || null))
onBeforeUnmount(() => {
  if (pointerCode.value || focusCode.value) emit('hover', null)
})
const ranked = computed(() =>
  props.data.regions
    .filter((region) => region.state !== 'missing')
    .sort((a, b) => b.count - a.count || a.code.localeCompare(b.code))
)
const unlocated = computed(() => [
  ...props.data.unknown.map((row) => ({ ...row, explanation: '无法识别省份' })),
  ...props.data.unmapped.map((row) => ({
    ...row,
    explanation: row.reason === 'conflict' ? '省份名称重复，未合并' : '未匹配地图'
  }))
])
</script>

<template>
  <div class="province-ranking">
    <div class="province-ranking-columns" aria-hidden="true">
      <span>省份</span><span>访问次数</span><span>占比</span>
    </div>
    <ol v-if="ranked.length" aria-label="省份访问排行">
      <li v-for="(region, index) in ranked" :key="region.code">
        <button
          type="button"
          :class="{ active: activeCode === region.code }"
          :aria-pressed="selectedCode === region.code"
          :aria-label="`${region.name}，${formatCount(region.count)} 次，占比 ${formatRatio(region.ratio)}`"
          @mouseenter="pointerCode = region.code"
          @mouseleave="pointerCode = null"
          @focus="focusRegion(region.code)"
          @blur="focusCode = null"
          @click="$emit('select', region.code)"
        >
          <span class="province-ranking-name"
            ><span class="province-rank">{{ index + 1 }}</span
            >{{ region.shortName }}</span
          >
          <strong>{{ formatCount(region.count) }}</strong>
          <span>{{ formatRatio(region.ratio) }}</span>
          <span class="province-ranking-track" aria-hidden="true">
            <span
              :style="{ width: `${data.maxCount ? (region.count / data.maxCount) * 100 : 0}%` }"
            />
          </span>
        </button>
      </li>
    </ol>
    <p v-else class="province-ranking-empty">尚未返回可匹配省份的数据。</p>
    <div v-if="unlocated.length" class="province-unlocated">
      <p>未定位到地图</p>
      <ul aria-label="未定位省份的数据">
        <li v-for="(row, index) in unlocated" :key="`${row.label}-${index}`">
          <span
            >{{ row.label }}<small>{{ row.explanation }}</small></span
          >
          <strong>{{ formatCount(row.count) }}</strong>
          <span>{{ formatRatio(row.ratio) }}</span>
        </li>
      </ul>
    </div>
    <p class="province-ranking-footnote">未返回的地区不计为零；占比沿用当前统计口径。</p>
  </div>
</template>

<style scoped>
.province-ranking {
  min-width: 0;
  font-size: 12px;
  line-height: 18px;
  font-variant-numeric: tabular-nums;
}
.province-ranking-columns,
.province-ranking button,
.province-unlocated li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 48px;
  align-items: baseline;
  gap: 8px;
}
.province-ranking-columns {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 4px 8px 8px;
  background: var(--surface);
  color: var(--muted);
}
.province-ranking-columns > :not(:first-child),
.province-ranking button > strong,
.province-ranking button > span:nth-child(3),
.province-unlocated li > :not(:first-child) {
  text-align: right;
  white-space: nowrap;
}
.province-ranking ol,
.province-ranking ul {
  list-style: none;
  padding: 0;
  margin: 0;
}
.province-ranking button {
  width: 100%;
  margin-bottom: 3px;
  padding: 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  font: inherit;
  cursor: pointer;
  text-align: left;
}
.province-ranking button:hover,
.province-ranking button.active {
  background: var(--subtle);
}
.province-ranking button:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: -2px;
}
.province-ranking button[aria-pressed='true'] {
  box-shadow: inset 3px 0 var(--blue);
}
.province-ranking-name {
  display: flex;
  gap: 8px;
  align-items: baseline;
  min-width: 0;
  overflow-wrap: anywhere;
}
.province-rank {
  width: 16px;
  flex: none;
  color: var(--muted);
  font-size: 11px;
}
.province-ranking-track {
  grid-column: 1 / -1;
  display: block;
  height: 3px;
  margin-left: 24px;
  background: #eff2f7;
  border-radius: 2px;
}
.province-ranking-track > span {
  display: block;
  height: 100%;
  background: var(--blue);
  border-radius: inherit;
}
.province-unlocated {
  margin-top: 14px;
  padding: 12px 8px 0;
  border-top: 1px solid var(--line);
}
.province-unlocated > p {
  margin: 0 0 10px;
  color: var(--muted);
}
.province-unlocated li {
  margin-bottom: 10px;
}
.province-unlocated small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 11px;
}
.province-ranking-empty,
.province-ranking-footnote {
  margin: 8px;
  color: var(--muted);
}
.province-ranking-footnote {
  font-size: 11px;
  margin-top: 14px;
}
</style>
