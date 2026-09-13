<script setup>
// element汉化
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<style scoped lang="less">
a {
 text-decoration: none !important;
}

</style>
