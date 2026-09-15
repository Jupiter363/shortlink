<script setup>
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

defineProps({ label: { type: String, required: true } })

const hintId = `analytics-method-${useId()}`
const trigger = ref(null)
const tooltip = ref(null)
const open = ref(false)
const pinned = ref(false)
const tooltipStyle = ref({ visibility: 'hidden' })
let closeTimer

function cancelClose() {
  clearTimeout(closeTimer)
}

function close() {
  cancelClose()
  pinned.value = false
  open.value = false
}

async function show() {
  cancelClose()
  if (open.value) return
  tooltipStyle.value = { visibility: 'hidden' }
  open.value = true
  await nextTick()
  if (!open.value || !trigger.value || !tooltip.value) return
  const anchor = trigger.value.getBoundingClientRect()
  const gap = 8
  const margin = 12
  const availableBelow = window.innerHeight - anchor.bottom - gap - margin
  const availableAbove = anchor.top - gap - margin
  const height = tooltip.value.getBoundingClientRect().height
  const below = height <= availableBelow || availableBelow >= availableAbove
  const maxHeight = Math.max(40, below ? availableBelow : availableAbove)
  const width = tooltip.value.getBoundingClientRect().width
  const left = Math.max(margin, Math.min(anchor.left, window.innerWidth - width - margin))
  const top = below ? anchor.bottom + gap : anchor.top - gap - Math.min(height, maxHeight)
  tooltipStyle.value = {
    left: `${left}px`,
    top: `${Math.max(margin, top)}px`,
    maxHeight: `${maxHeight}px`
  }
}

function scheduleClose() {
  cancelClose()
  if (pinned.value) return
  closeTimer = setTimeout(() => {
    if (document.activeElement === trigger.value) return
    close()
  }, 150)
}

function toggle() {
  if (pinned.value) close()
  else {
    pinned.value = true
    show()
  }
}

function onKeydown(event) {
  if (event.key === 'Escape') {
    close()
    return
  }
  const content = tooltip.value
  if (
    document.activeElement !== trigger.value ||
    !content ||
    content.scrollHeight <= content.clientHeight
  )
    return
  const offsets = {
    ArrowDown: 40,
    ArrowUp: -40,
    PageDown: content.clientHeight,
    PageUp: -content.clientHeight,
    Home: -content.scrollHeight,
    End: content.scrollHeight
  }
  if (!(event.key in offsets)) return
  event.preventDefault()
  content.scrollTop += offsets[event.key]
}

function onPointerdown(event) {
  if (trigger.value?.contains(event.target) || tooltip.value?.contains(event.target)) return
  close()
}

function onScroll(event) {
  if (!tooltip.value?.contains(event.target)) close()
}

function removeListeners() {
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('pointerdown', onPointerdown)
  document.removeEventListener('scroll', onScroll, true)
  window.removeEventListener('resize', close)
}

watch(open, (visible) => {
  removeListeners()
  if (!visible) return
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('pointerdown', onPointerdown)
  document.addEventListener('scroll', onScroll, true)
  window.addEventListener('resize', close)
})

onBeforeUnmount(() => {
  cancelClose()
  removeListeners()
})
</script>

<template>
  <button
    ref="trigger"
    class="analytics-method-trigger"
    type="button"
    :aria-label="label"
    :aria-describedby="open ? hintId : undefined"
    :aria-expanded="open"
    @mouseenter="show"
    @mouseleave="scheduleClose"
    @focus="show"
    @blur="scheduleClose"
    @click="toggle"
  >
    <span aria-hidden="true">?</span>
  </button>
  <Teleport to="body">
    <div
      v-if="open"
      :id="hintId"
      ref="tooltip"
      class="analytics-method-tooltip"
      role="tooltip"
      :style="tooltipStyle"
      @mouseenter="cancelClose"
      @mouseleave="scheduleClose"
    >
      <slot />
    </div>
  </Teleport>
</template>

<style scoped>
.analytics-method-trigger {
  display: inline-grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  cursor: help;
}
.analytics-method-trigger > span {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border: 1px solid currentColor;
  border-radius: 50%;
  font-family: system-ui, sans-serif;
  font-size: 11px;
  font-weight: 650;
  line-height: 1;
}
.analytics-method-trigger:hover,
.analytics-method-trigger[aria-expanded='true'] {
  background: var(--subtle);
  color: var(--blue);
}
.analytics-method-trigger:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 1px;
}
.analytics-method-tooltip {
  position: fixed;
  z-index: 50;
  width: min(328px, calc(100vw - 24px));
  padding: 14px 16px;
  overflow: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
  box-shadow: 0 8px 28px #18284724;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.analytics-method-tooltip :deep(strong) {
  display: block;
  margin-bottom: 6px;
  color: var(--text);
  font-size: 13px;
}
.analytics-method-tooltip :deep(p) {
  margin: 0 0 8px;
}
.analytics-method-tooltip :deep(p:last-child) {
  margin-bottom: 0;
}
</style>
