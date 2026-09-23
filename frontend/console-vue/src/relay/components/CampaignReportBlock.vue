<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AgentAnswer from './AgentAnswer.vue'
import CampaignReportChart from './CampaignReportChart.vue'
import { agentApi } from '../api/agentRisk.js'
import { errorMessage, safeText, sanitize } from '../domain/agentModel.js'
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
let controller
let generation = 0
const columns = computed(() =>
  Array.isArray(payload.value.columns) && payload.value.columns.length
    ? payload.value.columns
        .filter((column) => typeof column?.key === 'string')
        .map((column) => ({ key: column.key, label: column.label || column.key }))
    : Object.keys(rows.value[0] || {}).map((key) => ({ key, label: key }))
)
const known = computed(() => CAMPAIGN_BLOCK_KINDS.includes(props.block.kind))
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
      <span
        v-if="block.completeResult === false && ['METRIC', 'CHART', 'TABLE'].includes(block.kind)"
        class="cr-preview-tag"
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
        <div
          v-if="rows.length"
          class="cr-table-wrap"
          tabindex="0"
          role="region"
          :aria-label="`${block.title}，可横向滚动`"
        >
          <table>
            <thead>
              <tr>
                <th v-for="column in columns" :key="column.key" scope="col">{{ column.label }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in rows" :key="index">
                <td v-for="column in columns" :key="column.key">
                  {{ displayValue(row[column.key]) }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="cr-muted">本次返回的范围内没有明细行。</p>
        <footer class="cr-pagination">
          <span
            >第 {{ pageNumber }} 页 · 本页 {{ rows.length }} 条<span v-if="totalRows != null">
              / 共 {{ displayValue(totalRows) }} 条</span
            ></span
          >
          <div>
            <RButton
              v-if="cursorHistory.length"
              kind="text"
              :disabled="loading"
              @click="load('previous')"
              >上一页</RButton
            >
            <RButton v-if="nextCursor" kind="secondary" :disabled="loading" @click="load('next')">{{
              loading ? '正在载入' : '下一页'
            }}</RButton>
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
