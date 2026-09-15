<script setup>
import { ref } from 'vue'
import { pretty } from '../domain/agentModel.js'

defineProps({
  result: { type: Object, required: true },
  view: { type: String, default: 'evidence' }
})
const emit = defineEmits(['navigate-risk'])
const debugOpen = ref(false)
</script>

<template>
  <div class="ap-result-details">
    <section v-if="view === 'evidence'" class="ar-panel">
      <h2>回答证据</h2>
      <p v-if="!result.cards.length" class="ar-muted">
        本次响应未返回证据卡片，不能据此推断数据已完整。
      </p>
      <article v-for="card in result.cards" :key="card.key" class="ar-evidence-card">
        <h3>{{ card.title }}</h3>
        <p v-if="card.message">{{ card.message }}</p>
        <p v-if="card.summary && typeof card.summary === 'string'">{{ card.summary }}</p>
        <div
          v-if="card.type === 'access_records'"
          class="ar-table-scroll"
          role="region"
          aria-label="脱敏访问记录，可横向滚动"
          tabindex="0"
        >
          <table>
            <caption>
              脱敏访问记录
            </caption>
            <thead>
              <tr>
                <th>时间</th>
                <th>来源</th>
                <th>地域 / 运营商</th>
                <th>设备 / 系统</th>
                <th>访客</th>
                <th>响应</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in card.rows" :key="index">
                <td>{{ row.createTime }}</td>
                <td>{{ row.ip }}</td>
                <td>
                  {{ row.locale }}<small>{{ row.network }}</small>
                </td>
                <td>
                  {{ row.device }}<small>{{ row.os }} / {{ row.browser }}</small>
                </td>
                <td>{{ row.visitorType }}</td>
                <td>
                  {{ row.status }}<small>{{ row.eventType }}</small>
                </td>
              </tr>
              <tr v-if="!card.rows.length">
                <td colspan="6">未返回访问记录</td>
              </tr>
            </tbody>
          </table>
        </div>
        <template v-else>
          <details class="ar-details">
            <summary>指标与证据数据</summary>
            <pre v-if="card.metrics" class="ar-json">{{ pretty(card.metrics) }}</pre>
            <pre v-if="card.evidence" class="ar-json">{{ pretty(card.evidence) }}</pre>
            <pre v-if="card.reasonCodes" class="ar-json">{{ pretty(card.reasonCodes) }}</pre>
          </details>
          <details class="ar-details">
            <summary>完整证据卡片</summary>
            <pre class="ar-json">{{ pretty(card) }}</pre>
          </details></template
        >
      </article>
      <details class="ar-details">
        <summary>数据来源（{{ result.dataSources.length }}）</summary>
        <pre class="ar-json">{{ pretty(result.dataSources) }}</pre>
      </details>
    </section>
    <section v-if="view === 'evidence' && result.pendingActions.length" class="ar-panel">
      <h2>动作与待处理事项</h2>
      <p class="ar-caption">
        以下为 Agent
        返回的实际结果。自动限流、人工审核与现有策略停用分别核验；已提交不代表传播完成。
      </p>
      <article
        v-for="(action, index) in result.pendingActions"
        :key="index"
        class="ar-evidence-card"
      >
        <h3>{{ action.title || action.type }}</h3>
        <RBadge :tone="action.status === 'executed' ? 'info' : 'warning'">{{
          {
            executed: '命令已提交 · 传播待核实',
            pending_confirmation: '待人工核实',
            not_fully_applied: '未完全执行'
          }[action.status] || '结果未知'
        }}</RBadge>
        <details v-if="card.metrics || card.evidence || card.reasonCodes" class="ar-details">
          <summary>动作详情</summary>
          <pre class="ar-json">{{ pretty(action) }}</pre>
        </details>
      </article>
      <RButton kind="secondary" @click="emit('navigate-risk')">到风险中心核验</RButton>
    </section>
    <section v-if="view === 'trace'" class="ar-panel">
      <h2>工具执行记录</h2>
      <p class="ar-caption">响应返回的工具记录，保留失败和未提供的状态。</p>
      <p v-if="!result.toolCalls.length" class="ar-muted">本次响应未提供工具执行记录。</p>
      <ol class="ar-timeline">
        <li v-for="(tool, index) in result.toolCalls" :key="tool.key">
          <span class="ar-step">{{ index + 1 }}</span>
          <div>
            <strong>{{ tool.label }}</strong
            ><small v-if="tool.durationMs != null">{{ tool.durationMs }} ms</small>
            <details class="ar-details">
              <summary>执行详情</summary>
              <pre class="ar-json">{{ pretty(tool) }}</pre>
            </details>
          </div>
          <RBadge :tone="tool.tone">{{ tool.outcome }}</RBadge>
        </li>
      </ol>
      <details class="ar-details">
        <summary>Graph 节点轨迹（{{ result.traceEvents.length }}）</summary>
        <p v-if="!result.traceEvents.length" class="ar-muted">本次响应未提供 Graph 节点轨迹。</p>
        <div class="ar-graph">
          <article v-for="node in result.traceEvents" :key="node.key">
            <strong>{{ node.label }}</strong
            ><RBadge tone="unknown">{{ node.status }}</RBadge
            ><small v-if="node.timing?.durationMs != null">{{ node.timing.durationMs }} ms</small>
            <details>
              <summary>节点详情</summary>
              <pre class="ar-json">{{ pretty(node) }}</pre>
            </details>
          </article>
        </div>
      </details>
      <details class="ar-details" :open="debugOpen" @toggle="debugOpen = $event.currentTarget.open">
        <summary>脱敏调试数据</summary>
        <pre class="ar-json">{{ pretty(result) }}</pre>
      </details>
    </section>
    <section v-if="view === 'sources'" class="ar-panel">
      <h2>数据来源（{{ result.dataSources.length }}）</h2>
      <p v-if="!result.dataSources.length" class="ar-muted">本次响应未提供数据来源。</p>
      <pre class="ar-json">{{ pretty(result.dataSources) }}</pre>
    </section>
  </div>
</template>
