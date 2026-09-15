<script setup>
import { computed, ref, watch } from 'vue'
import ProvinceMapGraphic from './ProvinceMapGraphic.vue'
import ProvinceMapRanking from './ProvinceMapRanking.vue'
import { buildProvinceMap } from '../domain/provinceMap.js'
import { formatCount, formatRatio } from '../domain/analytics.js'
import mapSourceUrl from '../assets/china-provinces.SOURCE.md?url'

const props = defineProps({
  dimension: { type: Object, required: true },
  context: { type: String, default: '' }
})
const mode = ref('map')
const expanded = ref(false)
const hovered = ref(null)
const selected = ref(null)
const data = computed(() => buildProvinceMap(props.dimension))
const activeCode = computed(() => hovered.value || selected.value || '')
const active = computed(() => data.value.regions.find((region) => region.code === activeCode.value))
const matched = computed(() => data.value.regions.filter((region) => region.state !== 'missing'))
const unlocatedCount = computed(() => data.value.unknown.reduce((sum, row) => sum + row.count, 0))
const emptyLabel = computed(() =>
  props.dimension.quality.status === 'EMPTY' ? '所选范围暂无访问' : '暂无可定位的省份数据'
)

function selectRegion(code) {
  selected.value = selected.value === code ? null : code
  hovered.value = null
}
function closeMap() {
  expanded.value = false
  hovered.value = null
}
function changeMode(value) {
  mode.value = value
  hovered.value = null
}
function openMap() {
  expanded.value = true
  hovered.value = null
}
function clearSelection() {
  selected.value = null
  hovered.value = null
}
watch(
  () => props.dimension,
  () => {
    selected.value = null
    hovered.value = null
    expanded.value = false
  }
)
</script>

<template>
  <div class="province-panel">
    <div class="province-toolbar">
      <div class="province-mode" role="group" aria-label="省份展示方式">
        <button type="button" :aria-pressed="mode === 'map'" @click="changeMode('map')">
          地图
        </button>
        <button type="button" :aria-pressed="mode === 'ranking'" @click="changeMode('ranking')">
          排名
        </button>
      </div>
      <button class="province-expand" type="button" aria-haspopup="dialog" @click="openMap">
        查看大图 <span aria-hidden="true">↗</span>
      </button>
    </div>
    <template v-if="mode === 'map'">
      <div class="province-preview">
        <ProvinceMapGraphic
          :regions="data.regions"
          :active-code="activeCode"
          :selected-code="selected || ''"
          compact
          @hover="hovered = $event"
          @select="selectRegion"
        />
        <span v-if="!matched.length && !active" class="province-empty-overlay">{{
          emptyLabel
        }}</span>
      </div>
      <div class="province-preview-readout" aria-live="polite" aria-atomic="true">
        <template v-if="active">
          <strong>{{ active.shortName }}</strong>
          <span v-if="active.state === 'missing'">未提供数据</span>
          <span v-else>{{ formatCount(active.count) }} 次 · {{ formatRatio(active.ratio) }}</span>
        </template>
        <template v-else-if="matched.length && data.maxCount > 0">
          <span>访问次数</span
          ><span class="province-mini-scale" aria-hidden="true"
            ><i
              v-for="bin in data.legend"
              :key="bin.min"
              :style="{ background: bin.color }" /></span
          ><span>{{ formatCount(data.maxCount) }}</span>
        </template>
        <span v-else-if="matched.length">浅色表示已返回的 0 次访问</span>
        <span v-else>灰纹表示未提供数据</span>
      </div>
    </template>
    <div v-else class="province-preview-ranking">
      <ProvinceMapRanking
        :data="data"
        :active-code="activeCode"
        :selected-code="selected || ''"
        @hover="hovered = $event"
        @select="selectRegion"
      />
    </div>

    <RModal
      :open="expanded"
      title="省份访问分布"
      :description="context"
      :width="1000"
      class="province-map-modal"
      @close="closeMap"
    >
      <template v-if="expanded">
        <div class="province-map-summary">
          <span
            >已匹配 <strong>{{ matched.length }}</strong> 个省级地区</span
          >
          <span v-if="data.unknown.length"
            >无法识别省份 <strong>{{ formatCount(unlocatedCount) }}</strong> 次</span
          >
          <span v-if="data.unmapped.length">{{ data.unmapped.length }} 项数据未匹配地图</span>
        </div>
        <div class="province-map-layout">
          <section class="province-map-main" aria-label="省份热点地图">
            <div class="province-map-stage">
              <ProvinceMapGraphic
                :regions="data.regions"
                :active-code="activeCode"
                :selected-code="selected || ''"
                @hover="hovered = $event"
                @select="selectRegion"
              />
              <span v-if="!matched.length && !active" class="province-empty-overlay">{{
                emptyLabel
              }}</span>
            </div>
            <div class="province-map-readout" aria-live="polite" aria-atomic="true">
              <div v-if="active" class="province-active-values">
                <strong>{{ active.name }}</strong>
                <span v-if="active.state === 'missing'">未提供数据</span>
                <template v-else
                  ><span
                    ><b>{{ formatCount(active.count) }}</b> 次访问</span
                  ><span
                    >占比 <b>{{ formatRatio(active.ratio) }}</b></span
                  ></template
                >
              </div>
              <span v-else
                ><span class="province-mouse-hint">悬停查看数值，点击固定省份</span
                ><span class="province-touch-hint">点击省份查看访问数据</span></span
              >
              <button v-if="selected" type="button" @click="clearSelection">清除选择</button>
            </div>
            <div class="province-map-legend" aria-label="访问次数图例">
              <span v-for="bin in data.legend" :key="bin.min"
                ><i :style="{ background: bin.color }" />{{ bin.label }} 次</span
              >
              <span><i class="province-zero" />0 次</span>
              <span><i class="province-missing" />未提供数据</span>
            </div>
            <p class="province-map-note">{{ dimension.note }} 占比沿用当前统计口径。</p>
            <p class="province-map-source">
              底图：Apache ECharts 4.9.0 · 南海诸岛为附图 ·
              <a :href="mapSourceUrl" target="_blank" rel="noopener">来源与许可</a>
            </p>
          </section>
          <section class="province-map-side" aria-labelledby="province-ranking-title">
            <h3 id="province-ranking-title">
              省份排行<span>{{ matched.length }} 个地区</span>
            </h3>
            <div
              class="province-map-ranking-scroll"
              tabindex="0"
              role="region"
              aria-label="省份排名与未定位数据，可滚动查看"
            >
              <ProvinceMapRanking
                :data="data"
                :active-code="activeCode"
                :selected-code="selected || ''"
                @hover="hovered = $event"
                @select="selectRegion"
              />
            </div>
          </section>
        </div>
      </template>
    </RModal>
  </div>
</template>

<style scoped>
.province-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.province-toolbar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.province-mode {
  display: inline-flex;
  padding: 2px;
  gap: 2px;
  background: #f0f3f9;
  border-radius: 7px;
}
.province-mode button,
.province-expand,
.province-map-readout button {
  padding: 2px 8px;
  min-height: 24px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  font: inherit;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
  color: var(--muted);
}
.province-touch-hint {
  display: none;
}
.province-mode button[aria-pressed='true'] {
  background: #fff;
  color: var(--blue);
  box-shadow: 0 1px 3px #18284714;
}
.province-expand {
  color: var(--blue);
  padding-inline: 4px;
  white-space: nowrap;
}
.province-expand:hover,
.province-map-readout button:hover {
  background: var(--subtle);
}
.province-panel button:focus-visible,
.province-map-readout button:focus-visible,
.province-map-ranking-scroll:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 2px;
}
.province-preview {
  position: relative;
  min-height: 60px;
  flex: 1;
}
.province-empty-overlay {
  position: absolute;
  width: max-content;
  top: 48%;
  left: 50%;
  max-width: 85%;
  transform: translate(-50%, -50%);
  padding: 5px 9px;
  border-radius: 6px;
  background: #fffffff0;
  color: var(--muted);
  font-size: 12px;
  line-height: 18px;
  text-align: center;
  pointer-events: none;
  box-shadow: 0 2px 8px #1828470d;
}
.province-preview-readout {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  height: 20px;
  flex: none;
  font-size: 11px;
  line-height: 18px;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}
.province-preview-readout strong {
  color: var(--text);
}
.province-mini-scale {
  display: flex;
  gap: 2px;
}
.province-mini-scale i {
  width: 14px;
  height: 6px;
  border-radius: 1px;
}
.province-preview-ranking {
  overflow: auto;
  min-height: 0;
  flex: 1;
  scrollbar-width: thin;
}
.province-map-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin-bottom: 18px;
  font-size: 12px;
  color: var(--muted);
}
.province-map-summary strong {
  color: var(--text);
  font-size: 14px;
}
.province-map-layout {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(260px, 2fr);
  gap: 24px;
}
.province-map-main {
  min-width: 0;
}
.province-map-stage {
  position: relative;
  height: 306px;
  border-radius: 12px;
  background: #f8faff;
}
.province-map-readout {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 0;
  font-size: 12px;
  color: var(--muted);
}
.province-active-values {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-variant-numeric: tabular-nums;
}
.province-active-values strong,
.province-active-values b {
  color: var(--text);
  font-weight: 650;
}
.province-map-readout button {
  flex: none;
  color: var(--blue);
}
.province-map-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  font-size: 11px;
  line-height: 16px;
  color: var(--muted);
}
.province-map-legend > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.province-map-legend i {
  display: inline-block;
  width: 12px;
  height: 9px;
  border: 1px solid #a5b4cc;
  border-radius: 2px;
}
.province-map-legend .province-zero {
  background: #fff;
}
.province-map-legend .province-missing {
  background: repeating-linear-gradient(135deg, #e2e7ef, #e2e7ef 2px, #f2f4f8 2px, #f2f4f8 4px);
}
.province-map-note {
  margin: 12px 0 0;
  font-size: 11px;
  line-height: 18px;
  color: var(--muted);
}
.province-map-source {
  margin: 6px 0 0;
  font-size: 10px;
  line-height: 16px;
  color: var(--muted);
}
.province-map-side {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-left: 1px solid var(--line);
  padding-left: 20px;
}
.province-map-side h3 {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin: 0 0 10px;
  font-size: 14px;
  line-height: 22px;
}
.province-map-side h3 span {
  color: var(--muted);
  font-weight: 400;
  font-size: 12px;
}
.province-map-ranking-scroll {
  flex: 1;
  min-height: 0;
  max-height: 440px;
  overflow: auto;
  scrollbar-width: thin;
}
@media (max-width: 760px) {
  .province-mouse-hint {
    display: none;
  }
  .province-touch-hint {
    display: inline;
  }
  .province-map-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 20px;
  }
  .province-map-stage {
    height: 240px;
  }
  .province-map-side {
    border-left: 0;
    border-top: 1px solid var(--line);
    padding: 18px 0 0;
  }
  .province-map-ranking-scroll {
    flex: none;
    max-height: none;
    overflow: visible;
  }
  .province-map-summary {
    gap: 6px 12px;
    margin-bottom: 12px;
  }
  .province-active-values {
    gap: 4px 8px;
  }
}
</style>
