<script setup>
defineOptions({ inheritAttrs: false })
</script>

<template>
  <Teleport defer to="#workspace-page-header">
    <div class="operation-header" v-bind="$attrs"><slot /></div>
  </Teleport>
</template>
