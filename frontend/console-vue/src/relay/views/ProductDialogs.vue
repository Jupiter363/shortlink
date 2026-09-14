<script setup>
import { computed, inject, onBeforeUnmount, reactive, ref, watch } from 'vue'
import * as api from '../api/product.js'
import {
  absoluteShortUrl,
  createRequestId,
  createBody,
  updateBody,
  lifecycleBody,
  validOriginUrl,
  displayDate,
  inspectBatch,
  validateBatch,
  mapJob,
  appendCursorRows,
  TERMINAL_STATES,
  JOB_LABELS,
  isConflict,
  isAborted,
  errorMessage,
  prepareCreateAttempt
} from '../domain/product-model.js'
import './product.css'

const relay = inject('relay')
const { state, close, open, notify } = relay
const aliases = { createLink: 'create', editLink: 'edit', batchCreate: 'batch' }
const type = computed(() => aliases[state.modal.type] || state.modal.type)
const supported = new Set([
  'groupCreate',
  'groupRename',
  'groupDelete',
  'create',
  'edit',
  'recycle',
  'restore',
  'permanentDelete',
  'qr',
  'batch',
  'batchJob'
])
const visible = computed(() => supported.has(type.value))
const payload = computed(
  () => state.modal.payload?.link || state.modal.payload?.group || state.modal.payload || {}
)
const target = ref({})
const group = computed(
  () =>
    state.groups.find((item) => item.id === (payload.value.id || state.groupId)) || payload.value
)
const form = reactive({
  name: '',
  url: '',
  title: '',
  groupId: '',
  validity: 'forever',
  expires: '',
  confirmed: false,
  typedCode: ''
})
const busy = ref(false),
  error = ref(''),
  conflict = ref(false),
  titleState = ref('IDLE'),
  created = ref(null),
  qrSrc = ref('')
const batch = reactive({ urls: '', titles: '', groupId: '' })
const batchError = ref(''),
  pollError = ref(''),
  rowError = ref(''),
  loadingRows = ref(false),
  exportBusy = ref(false),
  cancelBusy = ref(false)
const singleRequestId = ref(''),
  batchRequestId = ref('')
const groupOptions = computed(() =>
  state.groups.map((item) => ({ value: item.id, label: item.name }))
)
const job = computed(() => state.batchJob || null)
const activeJob = computed(
  () => job.value?.kind === 'ASYNC' && !TERMINAL_STATES.has(job.value.state)
)
const isBatch = computed(() => ['batch', 'batchJob'].includes(type.value))
const batchInfo = computed(() => inspectBatch(batch, batchRequestId.value))
const modalTitle = computed(
  () =>
    ({
      groupCreate: '新建分组',
      groupRename: '重命名分组',
      groupDelete: '删除分组',
      create: created.value ? '短链接创建成功' : '创建短链接',
      edit: '编辑短链接',
      recycle: '移入回收站',
      restore: '恢复短链接',
      permanentDelete: '永久删除短链接',
      qr: '短链接二维码',
      batch: '批量创建短链接',
      batchJob: '批量创建结果'
    })[type.value]
)
const groupBlocked = computed(() => {
  if (type.value !== 'groupDelete') return ''
  if (group.value.id === state.defaultGroupId) return '这是账户的默认分组，无法删除。'
  if ((group.value.count ?? 0) > 0 || state.links.some((link) => link.groupId === group.value.id))
    return '这个分组仍包含短链接，请先处理其中的内容。'
  if (
    (state.activeJobs || []).some(
      (item) => item.groupId === group.value.id && !TERMINAL_STATES.has(item.state)
    )
  )
    return '这个分组仍有活动创建任务，任务结束后再试。'
  return ''
})
const resultRows = computed(() => job.value?.rows || [])
const progress = computed(() =>
  !job.value?.totalRows
    ? 0
    : Math.min(
        100,
        (((job.value.succeededRows || 0) +
          (job.value.failedRows || 0) +
          (job.value.invalidRows || 0)) /
          job.value.totalRows) *
          100
      )
)
const fmt = (value) =>
  value === null || value === undefined ? '—' : Number(value).toLocaleString()
const shortUrl = absoluteShortUrl
const rowLabel = (row) =>
  ({
    SUCCEEDED: '成功',
    INVALID: '格式无效',
    FAILED: '失败',
    CANCELLED: '未创建',
    VALIDATED: '待创建',
    RESERVED: '待创建'
  })[row.state || row.status] || '状态待确认'
const rowTone = (row) =>
  row.state === 'SUCCEEDED' || row.status === 'SUCCEEDED'
    ? 'success'
    : ['INVALID', 'FAILED'].includes(row.state || row.status)
      ? 'danger'
      : 'unknown'
const copy = (value) => relay.copy(value)
let modalGeneration = 0,
  pollGeneration = 0,
  pollTimer = 0,
  pollFailures = 0
let uiController = new AbortController(),
  titleController = null,
  pollController = null,
  rowsController = null
let singleAttempt = null,
  batchAttempt = null

function resetForm(link = payload.value) {
  target.value = { ...link }
  Object.assign(form, {
    name: type.value === 'groupCreate' ? '' : group.value.name || '',
    url: link.url || '',
    title: link.title || '',
    groupId: link.groupId || state.groupId || state.groups[0]?.id || '',
    validity: link.expires ? 'custom' : 'forever',
    expires: link.expires ? displayDate(link.expires).replace(' ', 'T') : '',
    confirmed: false,
    typedCode: ''
  })
  error.value = ''
  conflict.value = false
  titleState.value = 'IDLE'
  created.value = null
  busy.value = false
}
async function resolveQr() {
  qrSrc.value = ''
  const own = modalGeneration
  const value = shortUrl(target.value)
  if (!value) {
    error.value = '短链接地址不完整，请刷新后重试。'
    return
  }
  try {
    const src = await relay.qrDataUrl(value)
    if (own === modalGeneration) qrSrc.value = src
  } catch {
    if (own === modalGeneration) error.value = '二维码暂时生成失败，请重试。'
  }
}
watch(
  () => [state.modal.type, state.modal.payload],
  () => {
    modalGeneration++
    uiController.abort()
    titleController?.abort()
    uiController = new AbortController()
    resetForm()
    if (type.value === 'create') {
      singleRequestId.value = createRequestId('create')
      singleAttempt = null
    }
    if (type.value === 'qr') resolveQr()
    if (type.value === 'batch' && !activeJob.value) {
      batch.urls = ''
      batch.titles = ''
      batch.groupId = state.groupId || state.groups[0]?.id || ''
      batchError.value = ''
      batchRequestId.value = createRequestId('batch')
      batchAttempt = null
      state.batchJob = null
    }
    if (type.value === 'batchJob' && job.value?.kind === 'ASYNC') {
      if (TERMINAL_STATES.has(job.value.state)) {
        if (!job.value.rows?.length) loadRows(true)
      } else startPolling()
    }
  },
  { immediate: true }
)

async function fetchTitle() {
  if (!validOriginUrl(form.url)) {
    error.value = '先输入完整的 http:// 或 https:// 原始链接。'
    return
  }
  titleController?.abort()
  titleController = new AbortController()
  titleState.value = 'FETCHING'
  error.value = ''
  const own = modalGeneration,
    url = form.url
  try {
    const title = await api.fetchTitle(url, { signal: titleController.signal })
    if (own !== modalGeneration || form.url !== url) return
    if (!title?.trim()) throw new Error('没有获取到网页标题。')
    form.title = title.slice(0, 1024)
    titleState.value = 'READY'
  } catch (failure) {
    if (own === modalGeneration && !isAborted(failure)) titleState.value = 'FAILED'
  }
}
watch(
  () => form.url,
  () => {
    titleController?.abort()
    titleState.value = 'IDLE'
  }
)

async function refreshConflict() {
  if (busy.value) return
  busy.value = true
  try {
    await relay.refreshWorkspace()
    const latest = state.links.find((link) => link.id === target.value.id)
    if (!latest) {
      error.value = '当前列表中已找不到这条短链接。请关闭后重新定位最新内容。'
      return
    }
    resetForm(latest)
    notify('已加载最新内容，请确认后重新提交。', 'info')
  } catch (failure) {
    error.value = errorMessage(failure, '最新内容暂时无法加载。')
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (busy.value) return
  error.value = ''
  const action = type.value,
    own = modalGeneration
  let body,
    previousGroups = [],
    createDispatched = false
  try {
    if (
      ['groupCreate', 'groupRename'].includes(action) &&
      (!form.name.trim() || form.name.length > 32)
    )
      throw new Error('分组名称需要 1–32 个字符。')
    if (action === 'groupCreate' && state.groups.length >= 20)
      throw new Error('最多可创建 20 个分组。')
    if (action === 'groupDelete' && groupBlocked.value) throw new Error(groupBlocked.value)
    if (
      ['groupDelete', 'recycle', 'restore', 'permanentDelete'].includes(action) &&
      !form.confirmed
    )
      throw new Error('请先确认本次操作。')
    if (action === 'permanentDelete' && form.typedCode !== target.value.code)
      throw new Error('输入的短码与当前短链接不一致。')
    if (action === 'create') {
      singleAttempt = prepareCreateAttempt(createBody(form, singleRequestId.value), singleAttempt)
      body = singleAttempt.body
    }
    if (action === 'edit') body = updateBody(form, target.value)
    if (['recycle', 'restore', 'permanentDelete'].includes(action)) lifecycleBody(target.value)
    busy.value = true
    const options = { signal: uiController.signal }
    let result
    if (action === 'groupCreate') {
      previousGroups = state.groups.map((item) => item.id)
      await api.createGroup(form.name.trim(), options)
    }
    if (action === 'groupRename') await api.renameGroup(group.value.id, form.name.trim(), options)
    if (action === 'groupDelete') await api.deleteGroup(group.value.id, options)
    if (action === 'create') {
      createDispatched = true
      result = await api.createLink(body, options)
    }
    if (action === 'edit') await api.updateLink(body, options)
    if (action === 'recycle') await api.recycleLink(target.value, options)
    if (action === 'restore') await api.restoreLink(target.value, options)
    if (action === 'permanentDelete') await api.deleteLink(target.value, options)
    if (own !== modalGeneration) return
    if (singleAttempt && action === 'create') singleAttempt.uncertain = false
    if (action === 'create') created.value = { ...result, title: body.describe }
    notify(
      {
        groupCreate: '分组已创建',
        groupRename: '分组名称已更新',
        groupDelete: '分组已删除',
        create: '短链接已创建',
        edit: '短链接已更新',
        recycle: '已移入回收站',
        restore: '短链接已恢复',
        permanentDelete: '短链接已永久删除'
      }[action],
      'success'
    )
    // Refresh failures do not turn a confirmed mutation into a retryable create request.
    try {
      await relay.refreshGroups()
      if (action === 'groupCreate') {
        const newGroup = state.groups.find((item) => !previousGroups.includes(item.id))
        if (newGroup) state.groupId = newGroup.id
      }
      if (!state.groups.some((item) => item.id === state.groupId))
        state.groupId = state.defaultGroupId || state.groups[0]?.id || ''
      await relay.refreshLinks({ reset: true })
    } catch {
      notify('操作已完成，列表刷新暂未成功，请手动刷新。', 'warning')
    }
    if (action !== 'create' && own === modalGeneration) close()
  } catch (failure) {
    if (own !== modalGeneration || isAborted(failure)) return
    conflict.value =
      ['edit', 'recycle', 'restore', 'permanentDelete'].includes(action) && isConflict(failure)
    if (
      createDispatched &&
      singleAttempt &&
      (!failure.status || failure.status === 408 || failure.status >= 500)
    )
      singleAttempt.uncertain = true
    error.value = conflict.value
      ? '这条短链接已经被更新。请刷新最新内容，确认后再次提交。'
      : errorMessage(failure, '操作结果暂时无法确认，请刷新或重试原来的内容。')
  } finally {
    if (own === modalGeneration) busy.value = false
  }
}

function updateActive() {
  const value = state.batchJob
  state.activeJobs = (state.activeJobs || []).filter((item) => item.id !== value?.id)
  if (value?.kind === 'ASYNC' && !TERMINAL_STATES.has(value.state)) state.activeJobs.push(value)
}
function stopPolling() {
  pollGeneration++
  clearTimeout(pollTimer)
  pollController?.abort()
}
async function loadRows(reset = false) {
  if (!job.value || job.value.kind !== 'ASYNC' || loadingRows.value) return
  const id = job.value.id,
    owner = state.session.username,
    after = reset ? 0 : job.value.after || 0
  loadingRows.value = true
  rowError.value = ''
  rowsController?.abort()
  rowsController = new AbortController()
  try {
    const rows = await api.getBatchRows(id, { after, limit: 20 }, { signal: rowsController.signal })
    if (job.value?.id !== id || state.session.username !== owner) return
    const merged = appendCursorRows(reset ? [] : job.value.rows || [], rows, after)
    Object.assign(state.batchJob, merged)
  } catch (failure) {
    if (!isAborted(failure) && job.value?.id === id)
      rowError.value = errorMessage(failure, '逐行结果暂时无法加载，请重试。')
  } finally {
    loadingRows.value = false
  }
}
async function pollJob(own) {
  const current = state.batchJob
  if (
    own !== pollGeneration ||
    !current?.jobId ||
    current.owner !== state.session.username ||
    !state.session.loggedIn
  )
    return
  const id = current.id
  try {
    const result = await api.getBatchStatus(id, { signal: pollController.signal })
    if (own !== pollGeneration || state.batchJob?.id !== id) return
    state.batchJob = mapJob(result, state.batchJob)
    updateActive()
    pollError.value = ''
    pollFailures = 0
    if (TERMINAL_STATES.has(state.batchJob.state)) {
      await loadRows(true)
      if (
        own !== pollGeneration ||
        state.batchJob?.id !== id ||
        current.owner !== state.session.username
      )
        return
      try {
        await relay.refreshWorkspace()
      } catch {
        notify('任务已结束，列表暂未刷新成功。', 'warning')
      }
      return
    }
    pollTimer = setTimeout(() => pollJob(own), 2000)
  } catch (failure) {
    if (own !== pollGeneration || isAborted(failure)) return
    pollError.value = errorMessage(failure, '任务状态暂时无法确认，已保留当前任务。')
    if (++pollFailures <= 2 && failure.status !== 401 && failure.status !== 403)
      pollTimer = setTimeout(() => pollJob(own), 5000)
  }
}
function startPolling() {
  stopPolling()
  pollFailures = 0
  pollController = new AbortController()
  pollJob(pollGeneration)
}

async function startBatch() {
  if (busy.value || activeJob.value) return
  batchError.value = ''
  const own = modalGeneration
  let dispatched = false
  try {
    const info = inspectBatch(batch, batchRequestId.value)
    batchAttempt = prepareCreateAttempt(validateBatch(info), batchAttempt)
    busy.value = true
    dispatched = true
    const result = await api.createBatch(batchAttempt.body, { signal: uiController.signal })
    if (own !== modalGeneration) return
    batchAttempt.uncertain = false
    if (result?.jobId) {
      state.batchJob = mapJob(
        { ...result, totalRows: info.count },
        {
          owner: state.session.username,
          groupId: batch.groupId,
          requestId: batchAttempt.body.requestId,
          rows: [],
          after: 0,
          hasMore: true
        }
      )
      updateActive()
      startPolling()
    } else {
      if (
        !Array.isArray(result?.baseLinkInfos) ||
        result.baseLinkInfos.length !== info.count ||
        Number(result.total) !== info.count
      )
        throw new Error('创建响应不完整，请重试本次请求确认结果。')
      const rows = result.baseLinkInfos.map((row, index) => ({
        row: index + 1,
        state: 'SUCCEEDED',
        status: 'SUCCEEDED',
        title: row.describe || '',
        url: row.originUrl || '',
        fullShortUrl: row.fullShortUrl || '',
        shortUrl: absoluteShortUrl(row.fullShortUrl),
        error: ''
      }))
      state.batchJob = {
        id: batchAttempt.body.requestId,
        requestId: batchAttempt.body.requestId,
        owner: state.session.username,
        groupId: batch.groupId,
        kind: 'SYNC',
        state: 'SUCCEEDED',
        totalRows: result.total,
        validRows: result.total,
        invalidRows: 0,
        succeededRows: result.total,
        failedRows: 0,
        rows,
        hasMore: false,
        after: rows.length,
        error: ''
      }
      notify(`已创建 ${result.total} 条短链接`, 'success')
      try {
        await relay.refreshWorkspace()
      } catch {
        notify('创建已成功，列表暂未刷新。', 'warning')
      }
    }
  } catch (failure) {
    if (own !== modalGeneration || isAborted(failure)) return
    if (
      dispatched &&
      batchAttempt &&
      (!failure.status || failure.status === 408 || failure.status >= 500)
    )
      batchAttempt.uncertain = true
    batchError.value = errorMessage(failure, '创建结果暂未确认，请重试同一次提交。')
  } finally {
    if (own === modalGeneration) busy.value = false
  }
}

async function cancelJob() {
  if (!activeJob.value || cancelBusy.value) return
  const id = job.value.id
  cancelBusy.value = true
  try {
    const result = await api.cancelBatch(id)
    if (state.batchJob?.id !== id) return
    state.batchJob = mapJob(result, state.batchJob)
    updateActive()
    notify(
      state.batchJob.state === 'CANCELLED'
        ? '任务已取消，已创建的短链接会保留。'
        : '已收到服务端任务状态。',
      'info'
    )
    if (TERMINAL_STATES.has(state.batchJob.state)) {
      stopPolling()
      await loadRows(true)
      try {
        await relay.refreshWorkspace()
      } catch {
        /* Confirmed cancel is retained. */
      }
    } else startPolling()
  } catch (failure) {
    if (state.batchJob?.id === id) {
      pollError.value = errorMessage(failure, '取消结果尚未确认，正在重新查询任务状态。')
      startPolling()
    }
  } finally {
    cancelBusy.value = false
  }
}
async function exportResults() {
  if (!job.value || !TERMINAL_STATES.has(job.value.state) || exportBusy.value) return
  exportBusy.value = true
  try {
    if (job.value.kind === 'SYNC') await api.exportSyncBatch(job.value.requestId)
    else await api.exportAsyncBatch(job.value.id)
    notify('创建结果已导出', 'success')
  } catch (failure) {
    notify(
      failure.status === 429
        ? '导出繁忙，请稍后重试。'
        : errorMessage(failure, '导出暂时失败，结果仍然保留。'),
      'warning'
    )
  } finally {
    exportBusy.value = false
  }
}
async function downloadQr() {
  if (!qrSrc.value) return
  const extension = qrSrc.value.startsWith('data:image/svg+xml') ? 'svg' : 'png'
  const anchor = document.createElement('a')
  anchor.href = qrSrc.value
  anchor.download = `shortlink-${target.value.code}.${extension}`
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
}
function viewCreatedStats() {
  if (created.value) {
    state.analyticsScope = {
      type: 'link',
      id: created.value.id,
      code: created.value.code,
      gid: created.value.groupId,
      fullShortUrl: created.value.fullShortUrl
    }
    close()
    relay.go('/home/analytics')
  }
}
watch(
  () => state.session.token,
  () => {
    stopPolling()
    rowsController?.abort()
    if (state.batchJob?.owner !== state.session.username || !state.session.loggedIn) {
      state.batchJob = null
      state.activeJobs = []
    } else if (activeJob.value) startPolling()
  }
)
onBeforeUnmount(() => {
  modalGeneration++
  uiController.abort()
  titleController?.abort()
  stopPolling()
  rowsController?.abort()
})
</script>

<template>
  <RModal :open="visible" :title="modalTitle" :drawer="isBatch" @close="!busy && close()">
    <div
      v-if="['groupCreate', 'groupRename', 'groupDelete'].includes(type)"
      class="product-dialog-fields"
    >
      <template v-if="type !== 'groupDelete'"
        ><p class="product-lead">
          {{
            type === 'groupCreate'
              ? '把相同用途的短链接放在一起，分享和观察都更清楚。'
              : '修改分组名称，不会改变其中的短链接。'
          }}
        </p>
        <RField v-model="form.name" label="分组名称" hint="最多 32 个字符" :disabled="busy" />
        <p v-if="type === 'groupCreate'" class="product-helper">
          已创建 {{ state.groups.length }} / 20 个分组
        </p></template
      >
      <template v-else
        ><div class="product-confirm-icon"><RIcon name="folder" /></div>
        <p class="product-confirm-copy">
          删除分组 <strong>{{ group.name }}</strong
          >？<br />删除后无法找回这个分组。
        </p>
        <div v-if="groupBlocked" class="product-alert product-alert-warning">
          <RIcon name="warning" />{{ groupBlocked }}
        </div>
        <RCheckbox v-else v-model="form.confirmed" label="我确认删除这个空分组" :disabled="busy"
      /></template>
    </div>
    <div v-else-if="['create', 'edit'].includes(type)" class="product-dialog-fields">
      <div v-if="created" class="product-created">
        <RRobot role="base" expression="success" :size="132" />
        <h3>新的连接，准备出发</h3>
        <p>{{ created.title }}</p>
        <div class="product-created-url">
          <span>{{ shortUrl(created) }}</span
          ><RButton kind="secondary" @click="copy(shortUrl(created))"
            ><RIcon name="copy" />复制</RButton
          >
        </div>
        <div class="product-created-actions">
          <RButton kind="secondary" @click="open('qr', created)"
            ><RIcon name="qr-code" />二维码</RButton
          ><RButton kind="text" @click="viewCreatedStats"
            >查看统计<RIcon name="arrow-right"
          /></RButton>
        </div>
      </div>
      <template v-else
        ><p class="product-lead">
          {{
            type === 'create'
              ? '一个简洁的链接，让好内容更容易抵达。'
              : '更新目标链接与描述，原有短码保持不变。'
          }}
        </p>
        <RField
          v-model="form.url"
          label="原始链接"
          type="url"
          placeholder="https://example.com/your-page"
          :disabled="busy"
        />
        <div class="product-title-label">
          <span>短链描述</span
          ><RButton
            kind="text"
            :loading="titleState === 'FETCHING'"
            :disabled="busy"
            @click="fetchTitle"
            ><RIcon name="sparkle" />获取网页标题</RButton
          >
        </div>
        <RField v-model="form.title" label="描述" :maxlength="1024" :disabled="busy" />
        <p v-if="titleState === 'FAILED'" class="product-inline-warning">
          <RIcon name="warning" />标题获取失败，可以直接手工填写描述。
        </p>
        <p v-else-if="titleState === 'READY'" class="product-inline-success">
          <RIcon name="check" />已填入网页标题，可继续编辑。
        </p>
        <RSelect v-model="form.groupId" :options="groupOptions" label="所属分组" :disabled="busy" />
        <div class="product-two-fields">
          <RSelect
            v-model="form.validity"
            :options="[
              { value: 'forever', label: '永久有效' },
              { value: 'custom', label: '自定义有效期' }
            ]"
            label="有效期"
            :disabled="busy"
          /><RDateTime
            v-if="form.validity === 'custom'"
            v-model="form.expires"
            type="datetime-local"
            label="到期时间（北京时间）"
            :disabled="busy"
          />
        </div>
        <div class="product-domain-info">
          <RIcon name="link" />
          <div>
            <strong>{{ type === 'edit' ? shortUrl(target) : '创建成功后展示完整短链接' }}</strong
            ><span>短链域名由服务端分配，无需手动填写。</span>
          </div>
        </div></template
      >
    </div>
    <div
      v-else-if="['recycle', 'restore', 'permanentDelete'].includes(type)"
      class="product-dialog-fields"
    >
      <div
        class="product-confirm-icon"
        :class="{ 'product-confirm-danger': type === 'permanentDelete' }"
      >
        <RIcon :name="type === 'restore' ? 'arrow-counter-clockwise' : 'trash'" />
      </div>
      <p class="product-confirm-copy">
        <strong>{{ target.title }}</strong
        ><span class="product-confirm-url">{{ shortUrl(target) }}</span>
      </p>
      <p class="product-lead">
        {{
          type === 'restore'
            ? '恢复后，短链接将重新出现在原分组中；跳转仍按原有效期和风险策略执行。'
            : type === 'recycle'
              ? '移入回收站后，短链接将暂停跳转。之后可以在回收站恢复。'
              : '永久删除后，跳转将失效并停止统计，且无法恢复。请仔细确认。'
        }}
      </p>
      <RField
        v-if="type === 'permanentDelete'"
        v-model="form.typedCode"
        label="输入短码以确认"
        :hint="target.code"
        :disabled="busy"
      /><RCheckbox
        v-model="form.confirmed"
        :label="
          type === 'permanentDelete'
            ? '我了解后果，确认永久删除'
            : type === 'restore'
              ? '确认恢复这条短链接'
              : '确认将这条短链接移入回收站'
        "
        :disabled="busy"
      />
    </div>
    <div v-else-if="type === 'qr'" class="product-qr">
      <div class="product-qr-paper">
        <img v-if="qrSrc" :src="qrSrc" :alt="'短链接二维码：' + shortUrl(target)" />
        <div v-else class="product-qr-unavailable">
          <RIcon name="qr-code" />
          <p>正在准备二维码</p>
          <RButton v-if="error" kind="text" @click="resolveQr">重新生成</RButton>
        </div>
      </div>
      <h3>{{ target.title }}</h3>
      <p class="product-qr-url">{{ shortUrl(target) }}</p>
      <p class="product-helper">扫描二维码，打开这条短链接。</p>
      <div class="product-qr-actions">
        <RButton kind="secondary" @click="copy(shortUrl(target))"
          ><RIcon name="copy" />复制短链接</RButton
        ><RButton kind="primary" :disabled="!qrSrc" @click="downloadQr"
          ><RIcon name="download" />下载二维码</RButton
        >
      </div>
    </div>
    <div v-else-if="isBatch" class="batch-workspace">
      <template v-if="!job"
        ><p class="product-lead">一次放入多个目的地，统一创建、清楚核对。</p>
        <div class="batch-contract">
          <span><strong>2–500 行</strong>同步返回</span
          ><span><strong>501–50,000 行</strong>异步任务</span
          ><span><strong>≤ 8 MiB</strong>请求体上限</span>
        </div>
        <RSelect
          v-model="batch.groupId"
          :options="groupOptions"
          label="创建到分组"
          :disabled="busy"
        />
        <div class="batch-editor">
          <RTextarea
            v-model="batch.urls"
            label="原始链接 · 每行一条"
            :maxlength="12000000"
            :disabled="busy"
          /><RTextarea
            v-model="batch.titles"
            label="描述 · 与链接逐行对应，可全部留空"
            :maxlength="12000000"
            :disabled="busy"
          />
        </div>
        <div class="batch-validation">
          <span :class="{ 'is-invalid': batchInfo.count < 2 || batchInfo.count > 50000 }"
            >{{ fmt(batchInfo.count) }} 行链接</span
          ><span :class="{ 'is-invalid': batchInfo.tooLarge }"
            >{{ (batchInfo.bytes / 1024 / 1024).toFixed(2) }} MiB / 8 MiB</span
          ><RBadge v-if="batchInfo.count >= 2" :tone="batchInfo.isAsync ? 'info' : 'success'">{{
            batchInfo.isAsync ? '异步创建' : '同步创建'
          }}</RBadge>
        </div>
        <div v-if="batchInfo.mismatch" class="product-alert product-alert-danger" role="alert">
          链接与描述的行数不一致，请按行对应后再提交。
        </div>
        <div v-if="batchInfo.tooLarge" class="product-alert product-alert-danger" role="alert">
          请求体超过 8 MiB。请减少内容，当前无法提交。
        </div>
        <p v-if="batchInfo.invalidCount" class="product-inline-warning">
          有 {{ batchInfo.invalidCount }} 行链接格式需要检查。
        </p>
        <p class="product-helper">直接粘贴链接和描述。每条描述最多 1024 个字符。</p>
        <div v-if="batchError" class="product-alert product-alert-danger" role="alert">
          {{ batchError }}
        </div></template
      >
      <template v-else
        ><div class="batch-job-heading">
          <div>
            <RBadge
              :tone="
                job.state === 'FAILED'
                  ? 'danger'
                  : job.state === 'CANCELLED'
                    ? 'unknown'
                    : job.state === 'PARTIAL_SUCCESS'
                      ? 'warning'
                      : TERMINAL_STATES.has(job.state)
                        ? 'success'
                        : 'info'
              "
              >{{ JOB_LABELS[job.state] }}</RBadge
            >
            <h3>{{ job.kind === 'SYNC' ? '同步创建结果' : '批量创建任务' }}</h3>
          </div>
          <RRobot
            v-if="TERMINAL_STATES.has(job.state)"
            role="base"
            :expression="job.state === 'FAILED' ? 'recovery' : 'success'"
            :size="74"
          />
        </div>
        <p class="batch-job-id">
          {{ job.kind === 'SYNC' ? '创建凭据' : '任务编号' }}<code>{{ job.id }}</code>
        </p>
        <ol v-if="job.kind === 'ASYNC'" class="batch-stages">
          <li :class="{ 'is-current': job.state === 'VALIDATING' }">校验中</li>
          <li :class="{ 'is-current': job.state === 'READY' }">等待执行</li>
          <li :class="{ 'is-current': job.state === 'RUNNING' }">创建中</li>
          <li :class="{ 'is-current': TERMINAL_STATES.has(job.state) }">
            {{ TERMINAL_STATES.has(job.state) ? JOB_LABELS[job.state] : '结果' }}
          </li>
        </ol>
        <div
          class="batch-progress"
          role="progressbar"
          :aria-valuenow="progress"
          aria-valuemax="100"
          aria-valuemin="0"
        >
          <span :style="{ width: progress + '%' }"></span>
        </div>
        <dl class="batch-metrics">
          <div>
            <dt>总行数</dt>
            <dd>{{ fmt(job.totalRows) }}</dd>
          </div>
          <div>
            <dt>有效 / 无效</dt>
            <dd>{{ fmt(job.validRows) }} / {{ fmt(job.invalidRows) }}</dd>
          </div>
          <div>
            <dt>成功</dt>
            <dd>{{ fmt(job.succeededRows) }}</dd>
          </div>
          <div>
            <dt>失败</dt>
            <dd>{{ fmt(job.failedRows) }}</dd>
          </div>
        </dl>
        <p v-if="job.state === 'VALIDATING'" class="product-helper">
          正在校验格式和逐行对应关系，尚未开始创建。
        </p>
        <p v-else-if="job.state === 'READY'" class="product-helper">
          校验已完成，任务正在等待执行。
        </p>
        <p v-else-if="job.state === 'RUNNING'" class="product-helper">
          正在创建短链接。可以关闭此面板，任务会继续。
        </p>
        <div v-if="job.error" class="product-alert product-alert-danger">{{ job.error }}</div>
        <div v-if="job.state === 'CANCELLED'" class="product-alert product-alert-warning">
          任务已取消，成功创建的短链接会保留。未创建内容以逐行结果为准。
        </div>
        <div v-if="pollError" class="product-alert product-alert-warning">
          {{ pollError }}<RButton kind="text" @click="startPolling">重新查询</RButton>
        </div>
        <template v-if="TERMINAL_STATES.has(job.state)"
          ><div class="batch-result-heading">
            <h4>逐行创建结果</h4>
            <span>已加载 {{ resultRows.length }} 条</span>
          </div>
          <p v-if="loadingRows" role="status">正在加载结果…</p>
          <div v-if="rowError" class="product-alert product-alert-warning">
            {{ rowError
            }}<RButton kind="text" @click="loadRows(!resultRows.length)">重新加载</RButton>
          </div>
          <div v-if="resultRows.length" class="batch-results">
            <table>
              <thead>
                <tr>
                  <th>行号</th>
                  <th>链接与描述</th>
                  <th>结果</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in resultRows" :key="row.row">
                  <td>{{ row.row }}</td>
                  <td>
                    <strong>{{ row.title || '第 ' + row.row + ' 行' }}</strong
                    ><span v-if="row.url">{{ row.url }}</span
                    ><button v-if="row.shortUrl" type="button" @click="copy(row.shortUrl)">
                      {{ row.shortUrl }}<RIcon name="copy" /></button
                    ><span v-else-if="row.error" class="batch-row-error">{{ row.error }}</span>
                  </td>
                  <td>
                    <RBadge :tone="rowTone(row)">{{ rowLabel(row) }}</RBadge>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else-if="!loadingRows && !rowError" class="product-helper">
            本次任务尚未返回逐行结果。
          </p>
          <RButton v-if="job.hasMore" kind="secondary" :loading="loadingRows" @click="loadRows()"
            >继续加载 20 条结果</RButton
          >
          <p class="product-helper">
            {{
              job.kind === 'SYNC'
                ? '可导出本次已提交的 XLSX 创建结果。'
                : '任务已结束，可导出完整 CSV。'
            }}
          </p></template
        ></template
      >
    </div>
    <div v-if="error || conflict" class="product-feedback">
      <div v-if="error" class="product-alert product-alert-danger" role="alert">{{ error }}</div>
      <RButton v-if="conflict" kind="secondary" :loading="busy" @click="refreshConflict">
        刷新最新内容
      </RButton>
    </div>
    <template #footer
      ><template v-if="isBatch"
        ><RButton kind="secondary" :disabled="busy" @click="close">{{
          activeJob ? '关闭，保留任务' : '关闭'
        }}</RButton
        ><RButton
          v-if="!job"
          kind="primary"
          :loading="busy"
          :disabled="
            batchInfo.count < 2 ||
            batchInfo.count > 50000 ||
            batchInfo.mismatch ||
            batchInfo.tooLarge
          "
          @click="startBatch"
          >{{ batchInfo.isAsync ? '提交创建任务' : '批量创建' }}</RButton
        ><RButton v-else-if="activeJob" kind="danger" :loading="cancelBusy" @click="cancelJob"
          >取消任务</RButton
        ><RButton v-else kind="primary" :loading="exportBusy" @click="exportResults"
          ><RIcon name="download" />{{ job.kind === 'SYNC' ? '导出 XLSX' : '导出 CSV' }}</RButton
        ></template
      ><template v-else-if="type === 'qr' || created"
        ><RButton kind="primary" @click="close">完成</RButton></template
      ><template v-else
        ><RButton kind="secondary" :disabled="busy" @click="close">取消</RButton
        ><RButton
          :kind="
            ['permanentDelete', 'groupDelete', 'recycle'].includes(type) ? 'danger' : 'primary'
          "
          :loading="busy"
          :disabled="!!groupBlocked || conflict"
          @click="submit"
          >{{
            {
              groupCreate: '创建分组',
              groupRename: '保存名称',
              groupDelete: '删除分组',
              create: '创建短链接',
              edit: '保存修改',
              recycle: '移入回收站',
              restore: '确认恢复',
              permanentDelete: '永久删除'
            }[type]
          }}</RButton
        ></template
      ></template
    >
  </RModal>
</template>
