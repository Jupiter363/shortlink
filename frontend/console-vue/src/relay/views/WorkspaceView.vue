<script setup>
import { computed, inject, onMounted, ref } from 'vue'
import { sortGroups, absoluteShortUrl } from '../api/product.js'
import { displayDate } from '../domain/product-model.js'
import PageHeading from '../components/PageHeading.vue'
import './workspace-layout.css'

const relay = inject('relay'),
  state = relay.state
const recycle = computed(() => state.route === '/home/recycleBin')
const currentGroup = computed(() => state.groups.find((group) => group.id === state.groupId))
const detail = ref(null),
  groupsOpen = ref(false),
  settingsOpen = ref(false),
  metricMode = ref('total'),
  sorting = ref(false)
const orders = [
  { label: '创建时间', value: 'createTime' },
  { label: '累计 PV', value: 'totalPv' },
  { label: '累计 UV', value: 'totalUv' },
  { label: '累计 UIP', value: 'totalUip' },
  { label: '今日 PV', value: 'todayPv' },
  { label: '今日 UV', value: 'todayUv' },
  { label: '今日 UIP', value: 'todayUip' }
]
const format = (value) => {
  if (value == null) return '—'
  if (typeof value === 'bigint' || (typeof value === 'string' && /^[+-]?\d+$/.test(value))) {
    try {
      return BigInt(value).toLocaleString('zh-CN')
    } catch {
      /* Fall through for malformed input. */
    }
  }
  return Number(value).toLocaleString('zh-CN')
}
const metric = (link, key) =>
  format(link[metricMode.value === 'today' ? 'today' + key[0].toUpperCase() + key.slice(1) : key])
const status = (link) =>
  link.recycled
    ? ['unknown', '已回收']
    : {
        expired: ['danger', '已过期'],
        expiring: ['warning', '即将到期'],
        normal: ['success', '正常']
      }[link.status] || ['unknown', '状态未知']
const short = absoluteShortUrl
const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
const coverage = computed(() => state.list.statsMeta?.totalStatus)
const coverageIncomplete = computed(
  () => coverage.value && coverage.value !== 'COMPLETE' && coverage.value !== 'READY'
)
const currentOrderLabel = computed(
  () => orders.find((order) => order.value === state.list.orderTag)?.label || '创建时间'
)
function dialog(type, payload) {
  detail.value = null
  relay.open(type, payload)
}
async function chooseGroup(id) {
  if (state.groupId === id) {
    groupsOpen.value = false
    return
  }
  detail.value = null
  state.groupId = id
  groupsOpen.value = false
  await relay.refreshLinks({ reset: true })
}
async function changeSort(value) {
  detail.value = null
  state.list.orderTag = value
  await relay.refreshLinks({ reset: true })
}
async function changePage(delta) {
  detail.value = null
  await relay.refreshLinks({ targetCurrent: state.list.current + delta })
}
function stats(link) {
  state.analyticsScope = link
    ? {
        type: 'link',
        id: link.id,
        gid: link.groupId,
        groupId: link.groupId,
        code: link.code,
        fullShortUrl: link.fullShortUrl
      }
    : { type: 'group', id: state.groupId, gid: state.groupId, groupId: state.groupId }
  detail.value = null
  relay.go('/home/analytics')
}
async function moveGroup(direction) {
  const from = state.groups.findIndex((group) => group.id === state.groupId),
    to = from + direction
  if (sorting.value || from < 0 || to < 0 || to >= state.groups.length) return
  const reordered = [...state.groups],
    [item] = reordered.splice(from, 1)
  reordered.splice(to, 0, item)
  sorting.value = true
  try {
    await sortGroups(reordered)
    await relay.refreshGroups()
    relay.notify('分组顺序已更新', 'success')
  } catch (error) {
    relay.notify(error.message, 'danger')
  } finally {
    sorting.value = false
  }
}
async function refresh() {
  detail.value = null
  try {
    await relay.refreshWorkspace()
  } catch (error) {
    state.list.error = error.message
  }
}
onMounted(async () => {
  if (!state.groups.length) await refresh()
  else await relay.refreshLinks({ reset: true })
})
</script>

<template>
  <section class="workspace-page operation-page" :aria-busy="state.list.loading">
    <PageHeading class="page-heading">
      <div>
        <h1>{{ recycle ? '回收站' : '短链接' }}</h1>
        <p>
          {{
            recycle
              ? '确认不再使用后再永久删除，操作不可撤销。'
              : '把每一次点击，连接到正确的目的地。'
          }}
        </p>
      </div>
    </PageHeading>
    <div class="workspace-columns">
      <aside
        v-if="!recycle"
        class="group-rail paper strong-paper"
        tabindex="0"
        aria-label="短链接分组"
      >
        <div class="group-heading">
          <h2><RIcon name="folder" class="workspace-detail-icon" />我的分组</h2>
          <RIconButton icon="gear" label="分组管理" @click="groupsOpen = true" />
        </div>
        <div class="group-list" role="region" aria-label="分组列表" tabindex="0">
          <button
            v-for="group in state.groups"
            :key="group.id"
            :class="['group-item', { active: group.id === state.groupId }]"
            @click="chooseGroup(group.id)"
          >
            <span>{{ group.name }}</span
            ><b class="mono">{{ format(group.count) }}</b>
          </button>
        </div>
        <div class="group-footer">
          <RButton kind="text" :disabled="state.groups.length >= 20" @click="dialog('groupCreate')"
            >新建分组</RButton
          ><span class="muted">{{ state.groups.length }} / 20</span>
        </div>
        <div class="group-capacity">
          <meter
            min="0"
            max="20"
            :value="state.groups.length"
            :aria-label="`分组容量：已使用 ${state.groups.length}，上限 20`"
          />
          <p>
            {{
              state.groups.length >= 20
                ? '分组容量已满'
                : `还可创建 ${20 - state.groups.length} 个分组`
            }}
          </p>
        </div>
      </aside>
      <section
        class="link-workspace"
        tabindex="0"
        :aria-label="recycle ? '回收站操作区' : '短链接操作区'"
      >
        <div class="list-toolbar">
          <h2
            v-if="!recycle"
            class="workspace-scope-label"
            :title="currentGroup?.name || '未选择分组'"
            :aria-label="`当前分组：${currentGroup?.name || '未选择分组'}`"
          >
            <RIcon name="folder" class="workspace-detail-icon" />
            <span class="scope-caption">{{ currentGroup?.name || '未选择分组' }}</span>
          </h2
          >
          <h2 v-else>已回收短链 · {{ format(state.list.total) }} 条</h2>
          <RButton v-if="!recycle" :disabled="!state.groupId" @click="dialog('create')"
            >创建短链</RButton
          ><RButton
            v-if="!recycle"
            kind="secondary"
            :disabled="!state.groupId"
            @click="dialog('batch')"
            >批量创建</RButton
          >
          <RButton kind="text" :loading="state.list.loading" @click="refresh">刷新</RButton>
          <RButton
            v-if="!recycle"
            kind="text"
            class="workspace-settings-trigger"
            aria-label="显示与排序"
            :title="`显示与排序：${metricMode === 'today' ? '今日' : '累计'}指标 · ${currentOrderLabel}`"
            @click="settingsOpen = true"
            ><RIcon name="gear" /><span class="workspace-settings-label">显示与排序</span></RButton
          >
        </div>
        <div
          :key="`${state.groupId}-${state.list.current}-${state.list.orderTag}`"
          class="workspace-results operation-body"
          role="region"
          :aria-label="recycle ? '已回收短链接列表' : '短链接列表'"
          tabindex="0"
        >
          <div v-if="state.list.error" class="paper workspace-message" role="alert">
            <RRobot expression="recovery" :size="100" />
            <h2>暂时无法加载短链接</h2>
            <p>{{ state.list.error }}</p>
            <RButton @click="refresh">重新加载</RButton>
          </div>
          <div v-else-if="state.list.loading" class="paper workspace-message" role="status">
            <RRobot expression="waiting" :size="100" />
            <p>正在读取短链接…</p>
          </div>
          <div v-else-if="!state.links.length" class="empty-workspace paper">
            <RRobot role="base" expression="neutral" :size="96" />
            <h2>{{ recycle ? '回收站是空的' : '这个分组还没有短链接' }}</h2>
            <p>
              {{
                recycle ? '已回收的链接会显示在这里。' : '创建第一条短链接，让新的连接从这里开始。'
              }}
            </p>
            <RButton v-if="!recycle" @click="dialog(state.groupId ? 'create' : 'groupCreate')">{{
              state.groupId ? '创建短链接' : '新建分组'
            }}</RButton>
          </div>
          <template v-else>
            <div class="link-table-wrap">
              <table class="link-table">
                <thead>
                  <tr>
                    <th scope="col">短链接</th>
                    <th scope="col">创建时间 / 有效期</th>
                    <th scope="col">
                      <span class="metric-heading"
                        ><RIcon name="chart" class="workspace-detail-icon" />{{
                          metricMode === 'today' ? '今日' : '累计'
                        }}
                        PV / UV / UIP</span
                      >
                    </th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="link in state.links" :key="link.id">
                    <td>
                      <button class="link-title" :title="link.title" @click="detail = link">
                        <RIcon name="globe" /><strong>{{ link.title }}</strong></button
                      ><button
                        class="short-code mono"
                        :aria-label="`复制短链接 ${link.fullShortUrl}`"
                        :title="`复制短链接 ${link.fullShortUrl}`"
                        @click="relay.copy(short(link))"
                      >
                        {{ link.fullShortUrl }}
                      </button>
                      <p v-if="link.url" class="link-destination" :title="link.url">
                        <RIcon name="link" /><span>{{ link.url }}</span>
                      </p>
                    </td>
                    <td>
                      <time class="link-created"
                        ><RIcon name="clock" class="workspace-detail-icon" />{{
                          link.created || '时间未知'
                        }}</time
                      >
                      <div class="link-validity">
                        <RBadge :tone="status(link)[0]">{{ status(link)[1] }}</RBadge>
                        <span>{{ link.expires ? displayDate(link.expires) : '长期有效' }}</span>
                      </div>
                    </td>
                    <td>
                      <div class="metric-cell mono">
                        <span v-for="key in ['pv', 'uv', 'uip']" :key="key"
                          >{{ key.toUpperCase() }} <b>{{ metric(link, key) }}</b></span
                        >
                      </div>
                    </td>
                    <td>
                      <div v-if="recycle" class="row-actions">
                        <RButton kind="text" @click="dialog('restore', link)">恢复</RButton
                        ><RButton kind="danger" @click="dialog('permanentDelete', link)"
                          >永久删除</RButton
                        >
                      </div>
                      <div v-else class="row-actions">
                        <RButton kind="text" @click="stats(link)">统计</RButton
                        ><RButton kind="text" @click="dialog('edit', link)">编辑</RButton
                        ><RButton kind="text" @click="detail = link">更多</RButton>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="link-cards">
              <article v-for="link in state.links" :key="link.id" class="paper">
                <h2>
                  <button class="link-card-title" :title="link.title" @click="detail = link">
                    {{ link.title }}
                  </button>
                </h2>
                <button
                  class="short-code mono"
                  :aria-label="`复制短链接 ${link.fullShortUrl}`"
                  :title="`复制短链接 ${link.fullShortUrl}`"
                  @click="relay.copy(short(link))"
                >
                  {{ link.fullShortUrl }}
                </button>
                <div class="link-card-summary">
                  <RBadge :tone="status(link)[0]">{{ status(link)[1] }}</RBadge>
                  <p class="mono card-metrics">
                    {{ metricMode === 'today' ? '今日' : '累计' }} PV {{ metric(link, 'pv') }} · UV
                    {{ metric(link, 'uv') }}
                  </p>
                </div>
                <div class="control-row">
                  <RButton kind="text" @click="detail = link">查看详情</RButton
                  ><RButton v-if="!recycle" kind="text" @click="stats(link)">统计</RButton
                  ><RButton v-if="!recycle" kind="text" @click="dialog('edit', link)">编辑</RButton
                  ><RButton v-else kind="text" @click="dialog('restore', link)">恢复</RButton>
                </div>
              </article>
            </div>
          </template>
          <div v-if="state.batchJob" class="batch-notice">
            <div>
              <strong>批量创建任务</strong>
              <p class="muted">
                {{ state.batchJob.state }} · 成功 {{ format(state.batchJob.succeededRows) }} /
                {{ format(state.batchJob.totalRows) }}
              </p>
            </div>
            <RButton kind="secondary" @click="dialog('batchJob')">查看任务</RButton>
          </div>
        </div>
        <footer
          v-if="state.links.length && !state.list.loading && !state.list.error"
          class="list-footer"
        >
          <span class="list-total"
            >{{ format(state.list.total) }} 条<span class="list-page-size">
              · 每页 {{ state.list.size }} 条</span
            ></span
          >
          <div>
            <RButton
              kind="text"
              :disabled="state.list.current <= 1 || state.list.loading"
              @click="changePage(-1)"
              >上一页</RButton
            ><span class="mono">{{ state.list.current }} / {{ Math.max(1, state.list.pages) }}</span
            ><RButton
              kind="text"
              :disabled="state.list.current >= state.list.pages || state.list.loading"
              @click="changePage(1)"
              >下一页</RButton
            >
          </div>
        </footer>
      </section>
    </div>
    <RModal
      :open="settingsOpen"
      :title="recycle ? '指标覆盖说明' : '显示与排序'"
      @close="settingsOpen = false"
    >
      <div class="workspace-settings">
        <template v-if="!recycle">
          <RSelect
            :model-value="state.list.orderTag"
            label="排序"
            :options="orders"
            :disabled="state.list.loading"
            @update:model-value="changeSort"
          />
          <div class="metric-switch">
            <span>访问指标</span>
            <RButton
              :kind="metricMode === 'total' ? 'primary' : 'text'"
              :aria-pressed="metricMode === 'total'"
              @click="metricMode = 'total'"
              >累计</RButton
            ><RButton
              :kind="metricMode === 'today' ? 'primary' : 'text'"
              :aria-pressed="metricMode === 'today'"
              @click="metricMode = 'today'"
              >今日</RButton
            >
          </div>
          <p v-if="metricMode === 'today'" class="muted">{{ today }} · 北京时间</p>
        </template>
        <div v-if="coverageIncomplete" class="workspace-coverage-note" role="status">
          <strong>有数据缺口</strong>
          <p>累计指标覆盖状态：{{ coverage }}。未返回或尚未确认的数据以「—」显示。</p>
        </div>
        <RButton @click="settingsOpen = false">完成</RButton>
      </div>
    </RModal>
    <RModal
      :open="groupsOpen"
      title="分组管理"
      class="workspace-group-dialog"
      drawer
      @close="groupsOpen = false"
      ><div class="workspace-group-options" role="group" aria-label="选择短链接分组">
        <RButton
          v-for="group in state.groups"
          :key="group.id"
          :kind="group.id === state.groupId ? 'primary' : 'secondary'"
          class="workspace-group-choice"
          :aria-pressed="group.id === state.groupId"
          @click="chooseGroup(group.id)"
          ><span class="workspace-group-name">{{ group.name }}</span
          ><span class="workspace-group-count mono">{{ format(group.count) }} 条</span></RButton
        >
      </div>
      <div class="workspace-group-actions drawer-nav">
        <RButton
          kind="text"
          :disabled="state.groups.length >= 20"
          @click="
            () => {
              groupsOpen = false
              dialog('groupCreate')
            }
          "
          >新建分组</RButton
        ><RButton
          v-if="currentGroup"
          kind="text"
          @click="
            () => {
              groupsOpen = false
              dialog('groupRename', currentGroup)
            }
          "
          >重命名当前分组</RButton
        ><RButton
          v-if="currentGroup && currentGroup.id !== state.defaultGroupId"
          kind="danger"
          @click="
            () => {
              groupsOpen = false
              dialog('groupDelete', currentGroup)
            }
          "
          >删除当前分组</RButton
        >
        <div class="control-row">
          <RButton
            kind="text"
            :disabled="sorting || state.groups[0]?.id === state.groupId"
            @click="moveGroup(-1)"
            >上移</RButton
          ><RButton
            kind="text"
            :disabled="sorting || state.groups.at(-1)?.id === state.groupId"
            @click="moveGroup(1)"
            >下移</RButton
          >
        </div>
      </div></RModal
    >
    <RModal :open="!!detail" title="短链接详情" drawer @close="detail = null"
      ><template v-if="detail"
        ><h2 class="workspace-detail-title">{{ detail.title }}</h2>
        <p class="short-code mono">{{ detail.fullShortUrl }}</p>
        <p class="link-url">{{ detail.url }}</p>
        <dl class="detail-grid">
          <div>
            <dt>创建时间</dt>
            <dd>{{ detail.created || '未知' }}</dd>
          </div>
          <div>
            <dt>有效期</dt>
            <dd>{{ detail.expires ? displayDate(detail.expires) : '长期有效' }}</dd>
          </div>
          <div>
            <dt>累计访问次数 PV</dt>
            <dd>{{ format(detail.pv) }}</dd>
          </div>
          <div>
            <dt>访客数 UV / 独立 IP 数 UIP</dt>
            <dd>{{ format(detail.uv) }} / {{ format(detail.uip) }}</dd>
          </div>
        </dl>
        <div class="drawer-nav">
          <RButton @click="relay.copy(short(detail))">复制短链接</RButton
          ><RButton v-if="!detail.recycled" kind="secondary" @click="dialog('qr', detail)"
            >查看二维码</RButton
          ><RButton v-if="!detail.recycled" kind="secondary" @click="stats(detail)"
            >访问统计</RButton
          ><RButton v-if="!detail.recycled" kind="secondary" @click="dialog('edit', detail)"
            >编辑短链接</RButton
          ><RButton v-if="detail.recycled" kind="secondary" @click="dialog('restore', detail)"
            >恢复短链接</RButton
          ><RButton
            :kind="detail.recycled ? 'danger' : 'text'"
            @click="dialog(detail.recycled ? 'permanentDelete' : 'recycle', detail)"
            >{{ detail.recycled ? '永久删除' : '移入回收站' }}</RButton
          >
        </div></template
      ></RModal
    >
  </section>
</template>
