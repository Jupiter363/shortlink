<script setup>
import { ref } from 'vue'

defineProps({
  session: { type: Object, required: true },
  copy: { type: Object, required: true },
  agentType: { type: String, required: true },
  scopeOptions: { type: Array, required: true },
  running: { type: Boolean, default: false },
  busy: { type: Boolean, default: false },
  compiledLength: { type: Number, required: true },
  answerCount: { type: Number, required: true },
  inputError: { type: String, default: '' }
})

const emit = defineEmits([
  'update:groupId',
  'update:prompt',
  'clear',
  'reuse',
  'submit',
  'stop',
  'preset',
  'keydown'
])
const input = ref(null)
defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <div class="ar-identity">
    <RRobot :role="copy.role" :expression="running ? 'waiting' : 'neutral'" :size="48" />
    <div>
      <strong>{{ copy.name }}</strong>
      <p>提问设置</p>
    </div>
  </div>
  <div class="ar-inline-scope">
    <RSelect
      :model-value="session.groupId"
      label="分析范围"
      :options="scopeOptions"
      :disabled="running"
      @update:model-value="emit('update:groupId', $event)"
    />
  </div>
  <RTextarea
    ref="input"
    :model-value="session.prompt"
    label="你想了解什么？"
    :placeholder="copy.placeholder"
    :maxlength="2000"
    :rows="7"
    :count="false"
    :disabled="running"
    @update:model-value="emit('update:prompt', $event)"
    @keydown="emit('keydown', $event)"
  />
  <div class="aw-draft-tools">
    <button type="button" :disabled="busy || !session.prompt" @click="emit('clear')">
      清空问题
    </button>
    <button type="button" :disabled="busy || !session.lastPrompt" @click="emit('reuse')">
      复用上次提问
    </button>
    <span>Ctrl / ⌘ + Enter</span>
  </div>
  <p
    v-if="inputError || (session.error && session.runState !== 'ERROR')"
    class="ar-alert ar-alert-danger"
    role="alert"
  >
    {{ inputError || session.error }}
  </p>
  <p v-if="agentType === 'security-risk'" class="ar-caption ar-security-notice">
    分析可能按服务端策略自动限流。
  </p>
  <div class="ar-submit-row">
    <RButton
      :loading="running"
      loading-text="等待完整响应"
      :disabled="compiledLength > 2000 || (!running && busy)"
      @click="emit('submit')"
      >开始分析</RButton
    >
    <p class="ar-count" :class="{ 'ar-text-danger': compiledLength > 2000 }">
      {{ compiledLength }} / 2000 字
    </p>
  </div>
  <RButton v-if="running" kind="secondary" @click="emit('stop')">停止等待</RButton>
  <details v-if="session.runState === 'READY'" class="ar-details ar-mobile-presets">
    <summary>示例问题</summary>
    <div class="ar-presets">
      <RButton
        v-for="preset in copy.presets"
        :key="preset"
        kind="text"
        :disabled="running || busy"
        @click="emit('preset', preset)"
        >{{ preset }}</RButton
      >
    </div>
    <p class="ar-caption">选择后只填入问题，确认范围后再开始分析。</p>
  </details>
  <div class="aw-session-summary">
    <div>
      <span>本会话完整回答</span><strong>{{ answerCount }} <small>次</small></strong>
    </div>
    <p>
      {{
        session.completedAt
          ? '最近完成 · ' + session.completedAt
          : '分析完成后，可查看证据、历史与导出报告。'
      }}
    </p>
  </div>
</template>
