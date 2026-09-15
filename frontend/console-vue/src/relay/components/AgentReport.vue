<script setup>
import { computed } from 'vue'
import AgentAnswer from './AgentAnswer.vue'

const props = defineProps({
  result: { type: Object, required: true },
  entry: { type: Object, required: true },
  agentType: { type: String, default: 'campaign-analysis' },
  busy: Boolean
})
defineEmits(['followup', 'copy', 'export', 'details'])
const security = computed(() => props.agentType === 'security-risk')
const toolPreview = computed(() => props.result.toolCalls.slice(0, 4))
const evidencePreview = computed(() => props.result.cards.slice(0, 4))
</script>

<template>
  <article class="ap-report" :data-agent="agentType" aria-label="本次分析报告">
    <header class="ap-report-header">
      <div class="ap-report-heading">
        <span class="ap-report-symbol"
          ><RIcon :name="security ? 'shield' : 'chart'" :size="24"
        /></span>
        <div>
          <p class="ap-report-eyebrow">{{ entry.label }} · 响应已返回</p>
          <h2>{{ security ? '风险研判报告' : '投放分析报告' }}</h2>
        </div>
      </div>
      <div class="ap-report-actions">
        <RButton kind="secondary" :disabled="!result.answer" @click="$emit('copy')"
          ><RIcon name="copy" :size="16" />复制回答</RButton
        >
        <RButton kind="secondary" @click="$emit('export')"
          ><RIcon name="download" :size="16" />导出报告</RButton
        >
        <RButton :disabled="busy" @click="$emit('followup')"
          ><RIcon name="pencil" :size="16" />继续分析</RButton
        >
      </div>
    </header>
    <div class="ap-report-context">
      <p>
        <span>本次问题</span><strong>{{ entry.message || '未保留原始问题' }}</strong>
      </p>
      <div>
        <span
          >提交范围 <b>{{ entry.scopeLabel || '未单独记录，请核对原始问题' }}</b></span
        ><span
          >返回时间 <time>{{ entry.completedAt || '未提供' }}</time></span
        >
      </div>
    </div>
    <div class="ap-report-grid">
      <div class="ap-report-reading">
        <section
          v-if="result.warnings.length"
          class="ap-report-notice"
          aria-label="本次提示与数据缺口"
        >
          <header>
            <RIcon name="warning" :size="18" />
            <h3>需要留意</h3>
            <span>{{ result.warnings.length }} 项提示</span>
          </header>
          <ul>
            <li v-for="(warning, index) in result.warnings" :key="index">{{ warning }}</li>
          </ul>
        </section>
        <section class="ap-report-answer">
          <header>
            <h3>{{ security ? '研判意见' : '分析解读' }}</h3>
            <span>以本次返回的证据为依据</span>
          </header>
          <AgentAnswer :text="result.answer || '本次响应没有回答正文。'" />
        </section>
      </div>
      <aside class="ap-report-side" aria-label="报告依据与执行情况">
        <section class="ap-report-evidence">
          <header>
            <h3>证据目录</h3>
            <button type="button" @click="$emit('details', 'evidence')">
              查看全部<RIcon name="arrow-right" :size="14" />
            </button>
          </header>
          <p class="ap-side-caption">
            已返回 {{ result.cards.length }} 张证据卡<span v-if="result.cards.length > 4">
              · 预览前 4 张</span
            >
          </p>
          <ol v-if="evidencePreview.length" class="ap-evidence-index">
            <li v-for="(card, index) in evidencePreview" :key="card.key">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <div>
                <strong>{{ card.title }}</strong
                ><small v-if="card.message">{{ card.message }}</small
                ><small v-else-if="card.sourceTool">{{ card.sourceTool }}</small>
              </div>
            </li>
          </ol>
          <p v-else class="ap-side-empty">本次未返回证据卡，不能据此推断数据已完整。</p>
          <button class="ap-source-link" type="button" @click="$emit('details', 'sources')">
            <RIcon name="database" :size="16" /><span>来源说明</span
            ><strong>{{ result.dataSources.length }} 项</strong
            ><RIcon name="arrow-right" :size="14" />
          </button>
        </section>
        <section class="ap-report-execution">
          <header>
            <h3>执行概况</h3>
            <button type="button" @click="$emit('details', 'trace')">
              查看轨迹<RIcon name="arrow-right" :size="14" />
            </button>
          </header>
          <ul v-if="toolPreview.length" class="ap-tool-preview">
            <li v-for="tool in toolPreview" :key="tool.key">
              <span class="ap-tool-dot" :data-tone="tool.tone" aria-hidden="true" /><strong>{{
                tool.label
              }}</strong
              ><span :data-tone="tool.tone" class="ap-tool-state">{{ tool.outcome }}</span>
            </li>
          </ul>
          <p v-else class="ap-side-empty">本次未提供工具执行记录。</p>
          <p class="ap-side-caption">
            {{
              result.toolCalls.length > 4 ? `展示前 4 / ${result.toolCalls.length} 条 · ` : ''
            }}响应已返回，不代表所有工具均执行成功。
          </p>
        </section>
        <section v-if="result.pendingActions.length" class="ap-report-pending">
          <header><h3>动作结果与待核实事项</h3></header>
          <p>本次返回 {{ result.pendingActions.length }} 项，需分别核对执行与传播状态。</p>
          <button type="button" @click="$emit('details', 'evidence')">
            查看动作详情<RIcon name="arrow-right" :size="14" />
          </button>
        </section>
        <p v-if="security" class="ap-report-footnote">
          研判结论、人工审核与策略生效是不同事实，请按返回证据分别核验。
        </p>
      </aside>
    </div>
  </article>
</template>
