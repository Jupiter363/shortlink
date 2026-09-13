<template>
  <div class="main-box">
    <el-progress color="#3464e0" type="circle" :stroke-width="10" :percentage="data1Precentage">
      <template #default>
        <div class="flex-box">
          <span class="percentage-value">{{ labels[0] }}: {{ data1Precentage }}%</span>
          <span class="percentage-label">{{ data[0] }} {{ labels[0] === '新访客' ? '人' : '次' }}</span>
        </div>
      </template>
    </el-progress>
    <el-progress color="#3464e0" type="circle" :stroke-width="10" :percentage="data2Precentage">
      <template #default>
        <div class="flex-box">
          <span class="percentage-value">{{ labels[1] }}: {{ data2Precentage }}%</span>
          <span class="percentage-label">{{ data[1] }} {{ labels[1] === '旧访客' ? '人' : '次' }}</span>
        </div>
      </template>
    </el-progress>
  </div>
</template>

<script setup>
import { watch, ref } from 'vue'
const props = defineProps({
  labels: {
    type: Array,
    default: () => ['数据1', '数据2']
  },
  data: {
    type: Array,
    // 和上面的labels对应
    default: () => [0, 0]
  },
  unknownCount: {
    type: Number,
    default: 0
  }
})
const data1Precentage = ref(0)
const data2Precentage = ref(0)
watch(
  () => [props.data, props.unknownCount],
  () => {
    const data1 = props.data[0]
    const data2 = props.data[1]
    const denominator = Number(data1) + Number(data2) + props.unknownCount
    // 分别计算不同的百分比
    data1Precentage.value = (function () {
      if (data1 === 0) {
        return 0
      } else {
        return Number(((data1 / denominator) * 100).toFixed(0))
      }
    })()
    data2Precentage.value = (function () {
      if (data2 === 0) {
        return 0
      } else {
        return Number(((data2 / denominator) * 100).toFixed(0))
      }
    })()
  },
  { immediate: true }
)
</script>

<style lang="scss" scoped>
.main-box {
  padding: 10px;
  display: flex;
  align-items: center;
  justify-content: space-around;
}

.flex-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  font-size: 14px;
  font-weight: 600;

  .percentage-value {
    margin-bottom: 5px;
  }
}
</style>
