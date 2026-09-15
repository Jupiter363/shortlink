<script>
let mapAssetPromise

function loadMapAsset() {
  if (!mapAssetPromise) {
    mapAssetPromise = import('../assets/china-provinces.json')
      .then((module) => module.default)
      .catch((error) => {
        mapAssetPromise = undefined
        throw error
      })
  }
  return mapAssetPromise
}
</script>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { formatCount, formatRatio } from '../domain/analytics.js'

const props = defineProps({
  regions: { type: Array, default: () => [] },
  activeCode: { type: String, default: null },
  selectedCode: { type: String, default: null },
  compact: { type: Boolean, default: false }
})
const emit = defineEmits(['hover', 'select'])

const id = useId()
const patternId = `province-map-missing-${id}`
const instructionsId = `province-map-instructions-${id}`
const asset = ref(null)
const loading = ref(true)
const failed = ref(false)
const tabCode = ref(null)
const hoveredCode = ref(null)
const focusedCode = ref(null)
const regionElements = new Map()
let loadVersion = 0

const shapes = computed(() => {
  const data = new Map(props.regions.map((region) => [String(region.code), region]))
  return (asset.value?.regions || []).map((shape) => {
    const values = data.get(String(shape.code))
    const count = Number.isFinite(values?.count) && values.count >= 0 ? values.count : null
    const state = count === null ? 'missing' : count === 0 ? 'zero' : 'value'
    return {
      ...shape,
      code: String(shape.code),
      count,
      ratio: Number.isFinite(values?.ratio) && values.ratio >= 0 ? values.ratio : null,
      state,
      color: values?.color || '#4571e8'
    }
  })
})
const selectedShape = computed(() =>
  shapes.value.find((region) => region.code === props.selectedCode)
)
const currentTabCode = computed(() => {
  if (shapes.value.some((region) => region.code === tabCode.value)) return tabCode.value
  if (selectedShape.value) return selectedShape.value.code
  return shapes.value[0]?.code
})

watch([hoveredCode, focusedCode], () => {
  emit('hover', hoveredCode.value || focusedCode.value || null)
})

async function load() {
  const version = ++loadVersion
  loading.value = true
  failed.value = false
  try {
    const map = await loadMapAsset()
    if (version !== loadVersion) return
    if (!map?.viewBox || !Array.isArray(map.regions) || map.regions.length !== 34) {
      mapAssetPromise = undefined
      throw new Error('Province map asset is incomplete')
    }
    asset.value = map
  } catch {
    if (version === loadVersion) failed.value = true
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function fill(region) {
  if (region.state === 'missing') return `url(#${patternId})`
  return region.state === 'zero' ? '#fafcff' : region.color
}

function description(region) {
  if (region.state === 'missing') return `${region.name}：未返回访问数据`
  const ratio = region.ratio === null ? '占比未提供' : `占比 ${formatRatio(region.ratio)}`
  return `${region.name}：${formatCount(region.count)} 次访问，${ratio}`
}

function setRegionElement(code, element) {
  if (element) regionElements.set(code, element)
  else regionElements.delete(code)
}

function onFocus(code) {
  hoveredCode.value = null
  tabCode.value = code
  focusedCode.value = code
}

function onBlur(code) {
  if (focusedCode.value === code) focusedCode.value = null
}

function onLeave(code) {
  if (hoveredCode.value === code) hoveredCode.value = null
}

function select(code) {
  emit('select', props.selectedCode === code ? null : code)
}

async function onKeydown(event, code) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    select(code)
    return
  }
  if (event.key === 'Escape') {
    if (props.selectedCode) emit('select', null)
    return
  }
  const index = shapes.value.findIndex((region) => region.code === code)
  const last = shapes.value.length - 1
  const next = {
    ArrowRight: Math.min(last, index + 1),
    ArrowDown: Math.min(last, index + 1),
    ArrowLeft: Math.max(0, index - 1),
    ArrowUp: Math.max(0, index - 1),
    Home: 0,
    End: last
  }[event.key]
  if (next === undefined || next < 0) return
  event.preventDefault()
  const nextCode = shapes.value[next].code
  hoveredCode.value = null
  tabCode.value = nextCode
  await nextTick()
  regionElements.get(nextCode)?.focus()
}

onMounted(load)
onBeforeUnmount(() => {
  loadVersion += 1
  regionElements.clear()
  if (hoveredCode.value || focusedCode.value) emit('hover', null)
})
</script>

<template>
  <div class="province-map-graphic" :class="{ 'is-compact': compact }" :aria-busy="loading">
    <div v-if="loading" class="province-map-status" role="status">地图加载中…</div>
    <div v-else-if="failed" class="province-map-status" role="status">
      <span>地图暂时无法加载，可继续查看省份排名。</span>
      <button type="button" class="province-map-retry" @click="load">重新加载地图</button>
    </div>
    <template v-else>
      <span :id="instructionsId" class="province-map-sr-only">
        方向键切换省份，Home 和 End 跳到首尾；Enter 或空格选择省份，Escape 取消选择。
      </span>
      <svg
        class="province-map-svg"
        :viewBox="asset.viewBox"
        preserveAspectRatio="xMidYMid meet"
        role="group"
        aria-label="省份访问次数分布地图"
        :aria-describedby="instructionsId"
        @click="emit('select', null)"
        @mouseleave="hoveredCode = null"
      >
        <defs>
          <pattern
            :id="patternId"
            width="7"
            height="7"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(35)"
          >
            <rect width="7" height="7" fill="#e8edf3" />
            <line x1="0" y1="0" x2="0" y2="7" stroke="#d9e1ec" stroke-width="2" />
          </pattern>
        </defs>
        <g class="province-map-regions">
          <path
            v-for="region in shapes"
            :key="region.code"
            :ref="(element) => setRegionElement(region.code, element)"
            :d="region.path"
            :fill="fill(region)"
            fill-rule="evenodd"
            class="province-map-region"
            :class="{
              'is-active': activeCode === region.code,
              'is-selected': selectedCode === region.code,
              'is-missing': region.state === 'missing'
            }"
            :data-province-code="region.code"
            :data-state="region.state"
            role="button"
            :aria-label="description(region)"
            :aria-pressed="selectedCode === region.code"
            :tabindex="currentTabCode === region.code ? 0 : -1"
            @mouseenter="hoveredCode = region.code"
            @mouseleave="onLeave(region.code)"
            @focus="onFocus(region.code)"
            @blur="onBlur(region.code)"
            @click.stop="select(region.code)"
            @keydown="onKeydown($event, region.code)"
          >
            <title>{{ description(region) }}</title>
          </path>
        </g>
        <g class="province-map-decorations" aria-hidden="true">
          <template v-for="(decoration, index) in asset.decorations || []" :key="index">
            <path :d="decoration.path" fill-rule="evenodd" />
            <text
              v-if="decoration.labelPosition"
              :x="decoration.labelPosition[0]"
              :y="decoration.labelPosition[1]"
              text-anchor="middle"
            >
              {{ decoration.name }}
            </text>
          </template>
        </g>
        <g
          v-if="selectedShape?.center && !compact"
          class="province-map-selection-label"
          aria-hidden="true"
        >
          <text
            :x="selectedShape.center[0]"
            :y="selectedShape.center[1]"
            text-anchor="middle"
            dominant-baseline="central"
          >
            {{ selectedShape.shortName || selectedShape.name }}
          </text>
        </g>
      </svg>
    </template>
  </div>
</template>

<style scoped>
.province-map-graphic {
  position: relative;
  display: grid;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
}
.province-map-svg {
  display: block;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: visible;
}
.province-map-region {
  stroke: #aabbd3;
  stroke-width: 0.7px;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
  cursor: pointer;
  outline: none;
  transition:
    stroke 120ms ease,
    stroke-width 120ms ease,
    filter 120ms ease;
}
.province-map-region.is-active,
.province-map-region.is-selected,
.province-map-region:focus-visible {
  stroke: #173da0;
  stroke-width: 1.8px;
  paint-order: stroke fill;
  filter: drop-shadow(0 0 1px #173da04d);
}
.province-map-region.is-selected {
  stroke-width: 2.4px;
}
.province-map-region:focus-visible {
  stroke-width: 2.8px;
  stroke-dasharray: 3 1.5;
}
.province-map-decorations {
  pointer-events: none;
}
.province-map-decorations path {
  fill: #eef2f7;
  stroke: #aabbd3;
  stroke-width: 0.65px;
  vector-effect: non-scaling-stroke;
}
.province-map-decorations text {
  fill: #64748b;
  font-size: 15px;
}
.province-map-selection-label {
  pointer-events: none;
}
.province-map-selection-label text {
  fill: #132e66;
  stroke: #fff;
  stroke-width: 4px;
  stroke-linejoin: round;
  paint-order: stroke;
  font-size: 19px;
  font-weight: 650;
}
.province-map-status {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  color: var(--muted);
  text-align: center;
  font-size: 12px;
  line-height: 1.6;
}
.province-map-retry {
  padding: 5px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface);
  color: var(--blue);
  font: inherit;
  cursor: pointer;
}
.province-map-retry:hover {
  background: var(--subtle);
}
.province-map-retry:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}
.province-map-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}
@media (prefers-reduced-motion: reduce) {
  .province-map-region {
    transition: none;
  }
}
</style>
