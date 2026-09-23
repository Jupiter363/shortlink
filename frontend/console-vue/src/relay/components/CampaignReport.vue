<script setup>
import { computed, ref } from 'vue'
import AgentAnswer from './AgentAnswer.vue'
import CampaignReportBlock from './CampaignReportBlock.vue'
import {
  campaignResultState,
  campaignStatus,
  canCancelCampaignResult,
  projectReportModules
} from '../domain/campaignReport.js'

const props = defineProps({
  result: { type: Object, required: true },
  entry: { type: Object, required: true },
  sessionId: { type: String, required: true },
  busy: Boolean,
  refreshing: Boolean,
  refreshError: { type: String, default: '' },
  historical: Boolean
})
defineEmits(['followup', 'copy', 'export', 'details', 'refresh', 'continue', 'cancel'])
const view = computed(() => props.result.report?.view)
const modules = computed(() => projectReportModules(view.value))
const expandedTitles = ref(new Set())
const expandedShared = ref(new Set())
const blockLabels = {
  METRIC: '指标',
  CHART: '图表',
  TABLE: '明细',
  ANALYSIS: '分析',
  LIMITATION: '分析边界',
  RECOMMENDATION: '建议',
  RESULT_LINK: '完整数据'
}
function toggleTitle(goalId) {
  if (expandedTitles.value.has(goalId)) expandedTitles.value.delete(goalId)
  else expandedTitles.value.add(goalId)
}
function toggleShared(key, event) {
  if (event.target !== event.currentTarget) return
  if (event.target.open) expandedShared.value.add(key)
  else expandedShared.value.delete(key)
}
function sharedKinds(blocks) {
  return [...new Set(blocks.map((block) => blockLabels[block.kind]))].join(' · ')
}
const state = computed(() => campaignResultState(props.result))
const status = computed(() => campaignStatus(state.value))
const nextAction = computed(() => props.result.progress?.nextAction)
const answered = computed(
  () => view.value?.modules.filter((module) => module.status === 'ANSWERED').length || 0
)
const canContinue = computed(
  () => ['CONTINUE', 'RETRY'].includes(nextAction.value?.kind) && props.result.continuation
)
const canCancel = computed(() => canCancelCampaignResult(props.result))
const pendingGoals = computed(() => {
  const goals = props.result.progress?.goals
  if (!Array.isArray(goals)) return []
  return goals.map((goal, index) => ({
    key: `${index}-${typeof goal?.goalId === 'string' ? goal.goalId : 'unknown'}`,
    title: typeof goal?.title === 'string' && goal.title.trim() ? goal.title : '待确认的分析目标',
    status: ['PENDING', 'NEEDS_INPUT', 'UNSUPPORTED', 'CANCELLED'].includes(goal?.status)
      ? goal.status
      : 'UNKNOWN',
    limitations: Array.isArray(goal?.limitations)
      ? goal.limitations.filter((value) => typeof value === 'string' && value.trim())
      : []
  }))
})
const limitations = computed(() => [
  ...new Set([...(props.result.warnings || []), ...(props.result.report?.limitations || [])])
])
</script>

<template>
  <article class="cr-report" aria-label="投放分析报告">
    <header class="cr-report-header">
      <div class="cr-title-group">
        <RRobot role="navigator" :size="48" />
        <div>
          <p>
            领航员的分析手记<span v-if="view"> · 第 {{ view.reportRef.revision }} 版</span>
          </p>
          <h2>投放分析报告</h2>
        </div>
        <RBadge :tone="historical && view ? 'info' : status.tone">{{
          historical && view ? '已保存报告' : status.label
        }}</RBadge>
      </div>
      <div class="cr-report-actions">
        <RButton kind="secondary" @click="$emit('copy')"
          ><RIcon name="copy" :size="16" />复制分析</RButton
        >
        <RButton kind="secondary" :disabled="!view" @click="$emit('export')"
          ><RIcon name="download" :size="16" />导出本版</RButton
        >
        <RButton v-if="!historical" :disabled="busy" @click="$emit('followup')"
          >继续提问<RIcon name="arrow-right" :size="16"
        /></RButton>
      </div>
    </header>
    <div class="cr-question">
      <span>本次问题</span><strong>{{ entry.message || '未保留原始问题' }}</strong>
      <p>
        {{ entry.scopeLabel || '分析范围以本次证据为准'
        }}<span v-if="entry.completedAt"> · {{ entry.completedAt }}</span>
      </p>
    </div>
    <section
      v-if="!historical && (state !== 'SUCCESS' || refreshing || refreshError)"
      class="cr-progress"
      role="status"
      :aria-busy="refreshing"
    >
      <div>
        <strong>{{ status.label }}</strong>
        <p v-if="state === 'NEEDS_INPUT'">补充下面所需的信息后，可以接着分析。</p>
        <p v-else-if="state === 'PARTIAL'">已取得的分析保留在下方，其余目标仍待完成。</p>
        <p v-else-if="state === 'WAITING'">
          任务已登记，正在等待可核验的结果。页面可见时会自动更新进度，也可手动刷新。
        </p>
        <p v-else-if="state === 'UNKNOWN'">当前进度尚不能确认所有目标已完成。</p>
        <p v-else-if="state === 'CANCELLED'">
          本次分析已停止；已提交的统计任务可能仍在完成或等待回收。
        </p>
        <p v-if="nextAction?.requiredInputs?.length">
          待补充：{{ nextAction.requiredInputs.join('、') }}
        </p>
        <p v-if="refreshError" class="cr-inline-error">{{ refreshError }}</p>
      </div>
      <div class="cr-progress-actions">
        <RButton
          v-if="result.continuation"
          kind="secondary"
          :disabled="refreshing || busy"
          @click="$emit('refresh')"
          >{{ refreshing ? '读取进度中' : '刷新进度' }}</RButton
        >
        <RButton v-if="canContinue" :disabled="refreshing || busy" @click="$emit('continue')">{{
          nextAction.kind === 'RETRY' ? '重新推进本次分析' : '推进本次分析'
        }}</RButton>
        <RButton v-if="state === 'NEEDS_INPUT'" :disabled="busy" @click="$emit('followup')"
          >补充信息</RButton
        >
        <RButton
          v-if="canCancel"
          kind="secondary"
          :disabled="refreshing || busy"
          @click="$emit('cancel')"
          >停止本次分析</RButton
        >
      </div>
    </section>
    <p v-if="result.reportError" class="cr-inline-error" role="alert">{{ result.reportError }}</p>
    <template v-if="view">
      <div class="cr-goal-summary">
        <strong>{{ answered }} / {{ view.modules.length }} 个目标已回答</strong>
        <span>数据与解读按分析目标排列</span
        ><button v-if="!historical" type="button" @click="$emit('details', 'trace')">
          执行记录<RIcon name="arrow-right" :size="14" />
        </button>
      </div>
      <section
        v-for="(module, index) in modules"
        :key="module.goalId"
        class="cr-module"
        :aria-label="module.title"
      >
        <header class="cr-module-heading">
          <span class="cr-module-number">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="cr-module-title">
            <h3 :class="{ 'is-expanded': expandedTitles.has(module.goalId) }">
              {{ module.title }}
            </h3>
            <button
              type="button"
              :aria-expanded="expandedTitles.has(module.goalId)"
              @click="toggleTitle(module.goalId)"
            >
              {{ expandedTitles.has(module.goalId) ? '收起目标原文' : '查看完整目标' }}
            </button>
          </div>
          <RBadge :tone="campaignStatus(module.status).tone">{{
            campaignStatus(module.status).label
          }}</RBadge>
        </header>
        <component
          :is="group.sharedFrom ? 'details' : 'div'"
          v-for="group in module.displayGroups"
          :key="group.key"
          :class="group.sharedFrom ? 'cr-shared-content' : 'cr-module-content'"
          @toggle="toggleShared(group.key, $event)"
        >
          <summary v-if="group.sharedFrom" class="cr-shared-summary">
            <RIcon name="database" :size="18" />
            <span>
              <strong
                >沿用目标 {{ String(group.sharedFrom.number).padStart(2, '0') }} 的
                {{ group.blocks.length }} 项内容</strong
              >
              <small>{{ sharedKinds(group.blocks) }}</small>
            </span>
            <span class="cr-shared-toggle">{{
              expandedShared.has(group.key) ? '收起内容' : '展开查看'
            }}</span>
          </summary>
          <template v-if="!group.sharedFrom || expandedShared.has(group.key)">
            <div
              v-for="row in group.rows"
              :key="row.key"
              class="cr-block-row"
              :class="{ 'is-paired': row.paired, 'is-text-first': row.textFirst }"
            >
              <div
                v-for="column in row.columns"
                :key="column.blocks[0].blockId"
                class="cr-block-column"
              >
                <CampaignReportBlock
                  v-for="block in column.blocks"
                  :key="block.blockId"
                  :block="block"
                  :view="view"
                  :session-id="sessionId"
                />
              </div>
            </div>
          </template>
        </component>
        <p v-if="!module.blocks.length" class="cr-module-empty">此目标还没有可展示的分析内容。</p>
        <ul v-if="module.limitations.length" class="cr-module-limitations">
          <li v-for="(limitation, limitIndex) in module.limitations" :key="limitIndex">
            {{ limitation }}
          </li>
        </ul>
      </section>
    </template>
    <section v-else class="cr-pending-answer">
      <div v-if="pendingGoals.length" class="cr-goal-progress">
        <h3>本次分析目标</h3>
        <p>尚未形成正式报告，以下状态来自已登记的分析计划。</p>
        <ol>
          <li v-for="(goal, index) in pendingGoals" :key="goal.key">
            <span class="cr-module-number">{{ String(index + 1).padStart(2, '0') }}</span>
            <div>
              <div class="cr-goal-progress-heading">
                <h4>{{ goal.title }}</h4>
                <RBadge :tone="campaignStatus(goal.status).tone">{{
                  campaignStatus(goal.status).label
                }}</RBadge>
              </div>
              <ul v-if="goal.limitations.length">
                <li v-for="(limitation, limitIndex) in goal.limitations" :key="limitIndex">
                  {{ limitation }}
                </li>
              </ul>
            </div>
          </li>
        </ol>
      </div>
      <AgentAnswer :text="result.answer || '报告尚未生成，已保留本次分析进度。'" />
    </section>
    <footer v-if="limitations.length" class="cr-report-limitations">
      <h3><RIcon name="warning" :size="18" />本次分析边界</h3>
      <ul>
        <li v-for="(limitation, index) in limitations" :key="index">{{ limitation }}</li>
      </ul>
    </footer>
  </article>
</template>
