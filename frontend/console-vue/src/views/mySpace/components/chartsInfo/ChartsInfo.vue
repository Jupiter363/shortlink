<template>
  <el-dialog v-model="dialogVisible" :title="props.title" width="70%" :before-close="handleClose" @opened="refreshCharts">
    <template #header>
      <div style="display: flex">
        <img v-if="!isGroup" :src="getImgUrl(props.favicon)" width="25" height="25" alt="" />
        <div style="display: flex; flex-direction: column; margin-left: 5px">
          <span style="font-size: 25px; line-height: 25px; font-weight: 550">{{
            props.title
          }}</span>
          <span v-if="!isGroup" style="margin-top: 5px; font-size: 15px">{{
            props.originUrl
          }}</span>
        </div>
      </div>
      <span v-if="isGroup" style="margin: 5px 0 0 5px">共{{ props.nums }}条短链接</span>
    </template>
    <div style="position: absolute; right: 30px; z-index: 999">
      <el-date-picker v-model="dateValue" :clearable="true" type="daterange" range-separator="To" start-placeholder="开始时间"
        end-placeholder="结束时间" value-format="YYYY-MM-DD" :shortcuts="shortcuts" />
    </div>
    <!-- 具体展示内容 -->
    <el-tabs v-model="showPane">
      <!-- 切换， name用于确定展示哪个标签，和showPane对应 -->
      <el-tab-pane name="访问数据" label="访问数据">
        <!-- 数据图表 -->
        <div class="content-box scroll-box" style="height: calc(100vh - 280px); overflow: scroll">
          <p v-if="props.info?.loading" class="dimension-note" style="width: 100%" role="status">正在加载统计快照…</p>
          <p v-if="props.info?.error" class="dimension-note" style="width: 100%" role="alert">{{ props.info.error }}</p>
          <p v-if="props.info?.meta?.completeness && props.info.meta.completeness !== 'COMPLETE'" class="dimension-note" style="width: 100%">统计仍在异步汇总，当前快照可能不完整；未知维度不计作真实分类。</p>
          <!-- 访问曲线 -->
          <TitleContent class="chart-item" style="width: 800px" title="访问曲线" @onMounted="initLineChart">
            <template v-slot:titleButton>
              <div>
                <el-button @click="isLine = !isLine">切换为曲线</el-button>
              </div>
            </template>
            <template #content>
              <div class="list-chart">
                <div v-show="isLine" class="top10" style="padding-top: 20px">
                  <div class="key-value" style="margin-top: 10px">
                    <span>访问次数</span>
                    <span>{{ totalPv }}</span>
                  </div>
                  <div class="key-value" style="margin-top: 10px">
                    <span>访问人数</span>
                    <span>{{ totalUv }}</span>
                  </div>
                  <div class="key-value" style="margin-top: 10px">
                    <span>访问IP数</span>
                    <span>{{ totalUip }}</span>
                  </div>
                </div>
                <!-- 折线图 -->
                <div v-show="isLine" class="lineChart"></div>
                <!-- 表格 -->
                <div v-show="!isLine" style="padding: 20px">
                  <el-table :data="visitsData" border style="width: 100%; height: 210px; overflow: scroll"
                    :header-cell-style="{ background: '#eef1f6', color: '#606266' }">
                    <el-table-column prop="date" label="时间" width="160" />
                    <el-table-column prop="pv" label="访问次数" width="160" />
                    <el-table-column prop="uv" label="访问人数" width="160" />
                    <el-table-column prop="uip" label="访问IP数" width="160" />
                  </el-table>
                </div>
              </div>
            </template>
          </TitleContent>
          <!-- 地图 -->
          <TitleContent class="chart-item" style="width: 800px" title="访问地区" @onMounted="initMap">
            <template #titleButton>
              <!-- <el-button @click="isChina = !isChina">切换为世界地图</el-button> -->
            </template>
            <template #content>
              <div class="list-chart">
                <div v-show="isChina" class="top10">
                  <span style="font-size: 14px">TOP 10 省份</span>
                  <p class="dimension-note">{{ qualityText('localeCnStats') }}</p>
                  <div>
                    <span v-if="!chinaMapData?.length"
                      style="font-size: 14px; color: black; font-weight: 100">暂无可定位到国内省份的访问</span>
                  </div>
                  <div class="top-item" v-for="(item, index) in chinaMapData" :key="item.name">
                    <div v-if="index <= 9" class="key-value">
                      <span>{{ index + 1 + '. ' + item.name }}</span>
                      <span>{{ (item.ratio * 100).toFixed(2) }}%</span>
                      <span>{{ item.value }}次</span>
                    </div>
                  </div>
                </div>
                <div v-if="!isChina" class="top10">
                  <span>TOP 10 国家</span>
                  <template v-for="(item, index) in worldMapData" :key="item.name">
                    <div v-if="index <= 9" class="key-value">
                      <span>{{ item.name }}</span>
                      <span>{{ item.value }}</span>
                    </div>
                  </template>
                </div>
                <div v-show="isChina" class="chinaMap"></div>
                <div v-if="!isChina" class="worldMap"></div>
              </div>
            </template>
          </TitleContent>
          <!-- 24小时分布 -->
          <TitleContent class="chart-item" title="24小时分布" style="width: 800px">
            <template #content>
              <BarChart style="height: 100%; width: 100%" :chartData="{
                xAxis: [
                  0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21,
                  22, 23
                ],
                value: props.info?.hourStats || new Array(24).fill(0)
              }"></BarChart>
            </template>
          </TitleContent>
          <!-- 高频访问IP -->
          <TitleContent class="chart-item" title="高频访问IP" style="width: 390px">
            <template #content>
              <KeyValue :dataLists="topIpRows" style="height: 100%; width: 100%"></KeyValue>
            </template>
          </TitleContent>
          <!-- 一周分布 -->
          <TitleContent class="chart-item" title="一周分布" style="width: 390px">
            <template #content>
              <BarChart style="height: 100%; width: 100%" :chartData="{
                xAxis: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
                value: props.info?.weekdayStats || new Array(7).fill(0)
              }"></BarChart>
            </template>
          </TitleContent>

          <!-- 访问来源TOP5 -->
          <!-- <TitleContent class="chart-item" title="访问来源 TOP5" style="width: 390px">
            <template #content>
              <KeyValue :data-lists="IPdataList" style="height: 100%; width: 100%;"></KeyValue>
            </template>
          </TitleContent> -->
          <!-- 操作系统 -->
          <TitleContent class="chart-item" title="操作系统" style="width: 390px">
            <template #content>
              <ProgressLine style="height: 100%; width: 100%" :dataLists="props.info?.osStats"></ProgressLine>
            </template>
          </TitleContent>
          <!-- 访问浏览器 -->
          <TitleContent class="chart-item" title="访问浏览器" style="width: 390px">
            <template #content>
              <ProgressLine style="height: 100%; width: 100%" :dataLists="props.info?.browserStats"></ProgressLine>
            </template>
          </TitleContent>
          <!-- 访客类型 -->
          <TitleContent class="chart-item" title="访客类型 · 按历史首次观测" style="width: 390px">
            <template #content>
              <p class="dimension-note">在所选短链或分组的保留历史中判断，不代表用户终身首次访问。</p>
              <p class="dimension-note">{{ historyText }}</p>
              <ProgressPie v-if="userTypeList.some(value => value > 0)" style="width: 100%" :labels="['新访客', '旧访客']" :data="userTypeList" :unknown-count="Number(props.info?.dimensionQuality?.uvTypeStats?.unknownUv || 0)"></ProgressPie>
              <p v-else class="dimension-note">历史覆盖不足或没有可判定的访客，暂不区分新老。</p>
            </template>
          </TitleContent>
          <!-- 访问网络 -->
          <TitleContent class="chart-item" title="网络归属 · 运营商 / ISP" style="width: 390px">
            <template #content>
              <p class="dimension-note">按 IP 离线归属解析；无法由此判断 Wi-Fi、4G 或 5G。</p>
              <p class="dimension-note">{{ qualityText('networkStats') }}</p>
              <ProgressLine style="width: 100%" :dataLists="props.info?.networkStats" label-field="network" :show-icon="false"></ProgressLine>
            </template>
          </TitleContent>
          <TitleContent class="chart-item" title="国家 / 地区" style="width: 390px">
            <template #content>
              <p class="dimension-note">{{ qualityText('countryStats') }}</p>
              <ProgressLine style="width: 100%" :dataLists="countryRows" label-field="label" :show-icon="false"></ProgressLine>
            </template>
          </TitleContent>
          <!-- 访问设备 -->
          <TitleContent class="chart-item" title="访问设备" style="width: 390px">
            <template #content>
              <ProgressPie style="height: 100%; width: 100%" :labels="['电脑', '移动设备']" :data="deviceList"></ProgressPie>
            </template>
          </TitleContent>
        </div>
      </el-tab-pane>
      <el-tab-pane name="历史记录" label="历史记录">
        <el-table :data="accessRows" style="width: 100%; height: calc(100vh - 300px)">
          <el-table-column prop="createTime" label="访问时间（北京时间）" width="185" />
          <el-table-column prop="ip" label="访问IP（脱敏）" width="160" />
          <el-table-column prop="locale" label="访客地区"> </el-table-column>
          <el-table-column prop="device" label="设备信息">
            <template #default="scope">
              <div class="flex-box">
                <img :src="getUrl1(scope?.row?.browser)" width="20" alt="" />
                <img :src="getUrl2(scope?.row?.os)" width="20" alt="" />
                <img :src="getUrl3(scope?.row?.device)" width="20" alt="" />
              </div>
            </template>
          </el-table-column>

          <el-table-column prop="network" label="运营商 / ISP" />
          <el-table-column prop="visitorType" label="访客类型（历史观测）" />
        </el-table>
        <!-- 分页器 -->
        <div class="pagination-block">
          <el-button :disabled="!recordsPage.hasPrevious || recordsPage.loading" @click="changeRecordPage(-1)">上一页</el-button>
          <span style="margin: 0 12px">第 {{ recordsPage.current || 1 }} 页 · 本页 {{ accessRows.length }} 条</span>
          <el-button :disabled="!recordsPage.hasNext || recordsPage.loading" @click="changeRecordPage(1)">下一页</el-button>
          <el-select :model-value="recordsPage.size || 10" :disabled="recordsPage.loading" style="width: 120px; margin-left: 12px" aria-label="每页访问记录数" @change="changeRecordSize">
            <el-option v-for="size in [10, 15, 20, 30]" :key="size" :label="`${size} 条 / 页`" :value="size" />
          </el-select>
          <p v-if="recordsPage.error" class="dimension-note" role="alert">{{ recordsPage.error }}</p>
          <p v-else class="dimension-note">分页沿用同一统计快照，切换范围后重新查询。</p>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import { ref, watch, computed, nextTick, onBeforeUnmount } from 'vue'
import { toAccessRecordRow } from '@/utils/agentAccessRecords.js'
import TitleContent from './TitleContent.vue'
import echarts from '@/plugins/echarts.js'
import chinaMap from '@/assets/maps/china.json'
import BarChart from './BarChart.vue'
import KeyValue from './KeyValue.vue'
import ProgressLine from './ProgressLine.vue'
import ProgressPie from './ProgressPie.vue'
import edge from '@/assets/png/edge.png'
import Andriod from '@/assets/png/Andriod.png'
import Chorme from '@/assets/png/Chorme.png'
import firefox from '@/assets/png/firefox.png'
import iOS from '@/assets/png/iOS.png'
import macOS from '@/assets/png/macOS.png'
import other from '@/assets/png/other.png'
import Safair from '@/assets/png/Safair.png'
import WeChat from '@/assets/png/WeChat.png'
import Windows from '@/assets/png/Windows.png'
import linux from '@/assets/png/linux.png'
import PC from '@/assets/png/电脑.png'
import Mobile from '@/assets/png/移动设备.png'
import defaultImg from '@/assets/png/短链默认图标.png'
import opera from '@/assets/png/opera.png'
import IE from '@/assets/png/IE.png'
import { getTodayFormatDate, getLastWeekFormatDate } from '@/utils/plugins.js'

echarts.registerMap('china', chinaMap)

// 选择时间
const shortcuts = [
  {
    text: '今天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 0)
      return [start, end]
    }
  },
  {
    text: '昨天',
    value: () => {
      const start = new Date()
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 1)
      return [start, start]
    }
  },
  {
    text: '近七天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 7)
      return [start, end]
    }
  },
  {
    text: '近三十天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setTime(start.getTime() - 3600 * 1000 * 24 * 30)
      return [start, end]
    }
  }
]
const getImgUrl = (url) => {
  return url ?? defaultImg
}
const dailyXAxis = ref()
// const dailyXAxis = ref([])
const showPane = ref('访问数据')
// 浏览器
const getUrl1 = (img) => {
  if (img) {
    img = img.toLowerCase()
  }
  if (img?.includes('edge')) {
    return edge
  } else if (img?.includes('chrome')) {
    return Chorme
  } else if (img?.includes('fire')) {
    return firefox
  } else if (img?.includes('safari')) {
    return Safair
  } else if (img?.includes('wechat') || img?.includes('微信')) {
    return WeChat
  } else if (img?.includes('opera')) {
    return opera
  }
  else if (img?.includes('internet')) {
    return IE
  }
  else {
    return other
  }
}
// 操作系统
const getUrl2 = (img) => {
  if (img) {
    img = img.toLowerCase()
  }
  if (img?.includes('andriod')) {
    return Andriod
  } else if (img?.includes('ios')) {
    return iOS
  } else if (img?.includes('mac')) {
    return macOS
  } else if (img?.includes('windows')) {
    return Windows
  } else if (img?.includes('linux')) {
    return linux
  } else {
    return other
  }
}
// 访问设备（pc或者移动设备）
const getUrl3 = (img) => {
  if (img) {
    img = img.toLowerCase()
  }
  if (img?.includes('pc')) {
    return PC
  } else {
    return Mobile
  }
}
const dateValue = ref([getLastWeekFormatDate(), getTodayFormatDate()])
const emit = defineEmits(['changeTime', 'changePage', 'closed'])
watch(
  () => dateValue.value,
  (newValue) => {
    // 解决首次关闭数据统计页面需要点两次关闭键的bug
    if (!dialogVisible.value) {
      return
    }
    emit('changeTime', newValue)
  }
)
const props = defineProps({
  title: {
    type: String,
    default: '默认标题'
  },
  info: Object,
  tableInfo: Object,
  isGroup: Boolean,
  nums: Number,
  favicon: String,
  originUrl: String
})
const countryNames = new Intl.DisplayNames(['zh-CN'], { type: 'region' })
const countryRows = computed(() => (props.info?.countryStats || []).map(row => ({
  ...row, label: /^[A-Z]{2}$/.test(row.country) ? countryNames.of(row.country) : row.country
})))
const qualityText = (key) => {
  const quality = props.info?.dimensionQuality?.[key]
  if (!quality) return ''
  const counts = `已识别 ${quality.knownCount || 0} 次，未知 ${quality.unknownCount || 0} 次`
  return quality.reason === 'GEO_VERSION_CONFLICT' ? `${counts}；地址库版本存在冲突` : counts
}
const historyText = computed(() => {
  const quality = props.info?.dimensionQuality?.uvTypeStats
  if (!quality) return ''
  const date = value => value ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai' }).format(Number(value)) : '未确定'
  return `回溯上限：${date(quality.historyStart)} 至 ${date(quality.historyEnd)}（最多 ${quality.maxHistoryDays || 180} 天）；实际最早观测：${date(quality.earliestObservedAt)}；未知 ${quality.unknownUv || 0} 位访客。${quality.status === 'UNKNOWN' ? '历史完整性尚未确认。' : ''}`
})
const topIpRows = computed(() => (props.info?.topIpStats || []).map(row => ({ ...row, ip: toAccessRecordRow(row).ip })))
const accessRows = computed(() => (props.tableInfo?.data?.data?.records || []).map(toAccessRecordRow))
const recordsPage = computed(() => props.tableInfo?.data?.data || {})
const changeRecordPage = delta => emit('changePage', {
  current: (recordsPage.value.current || 1) + delta,
  size: recordsPage.value.size || 10
})
const changeRecordSize = size => emit('changePage', { current: 1, size })
// const title = ref(props.title)
// const info = ref(props.info)

// watch(
//   () => {
//     props.info
//   },
//   () => {
//     console.log(props.info, props.title)
//     info.value = props.info
//     title.value = props.title
//   },
//   {
//     deep: true,
//   }
// )
const dialogVisible = ref(false)
const handleClose = () => {
  unVisible()
  emit('closed')
  showPane.value = '访问数据'
  dateValue.value = [getLastWeekFormatDate(), getTodayFormatDate()]
  document.querySelector('.scroll-box').scrollTop = 0
}
const isVisible = () => {
  dialogVisible.value = true
}
const unVisible = () => {
  dialogVisible.value = false
}
const refreshCharts = async () => {
  await nextTick()
  if (!dialogVisible.value) return
  initLineChart()
  initMap()
}
onBeforeUnmount(() => {
  for (const selector of ['.chinaMap', '.worldMap', '.lineChart']) {
    const element = document.querySelector(selector)
    if (element) echarts.getInstanceByDom(element)?.dispose()
  }
})
defineExpose({
  unVisible,
  isVisible
})

// 中国地图中展示的数据
const chinaMapData = ref([
  // {
  //   name: '北京',
  //   value: 0
  // }
])
// 中国地图中的总次数
const chinaTotalNum = ref(0)
// 世界地图中展示的数据
const worldMapData = computed(() => countryRows.value.map(row => ({ name: row.label, value: row.cnt })))
const isChina = ref(true)
const initChinaMap = () => {
  // 中国地图
  const chinaMapDom = document.querySelector('.chinaMap')
  if (!chinaMapDom || !dialogVisible.value) return
  const chinaMap = echarts.getInstanceByDom(chinaMapDom) || echarts.init(chinaMapDom)
  const option = {
    tooltip: {
      formatter: function (params) {
        if (params.value) {
          return params.name + ' : ' + params.value
        } else {
          return params.name + ' : 暂无已知访问'
        }
      }
    },
    visualMap: {
      min: 0,
      max: chinaTotalNum.value,
      left: 'left',
      top: 'bottom',
      text: ['高', '低'],
      calculable: false,
      orient: 'horizontal',
      inRange: {
        // 最小值到最大值的颜色
        color: ['#e0ffff', '#006edd'],
        symbolSize: [30, 100]
      }
    },
    series: {
      type: 'map',
      map: 'china',
      itemStyle: {
        // 鼠标移入后的样式
        emphasis: {
          // 鼠标移入后的颜色
          areaColor: '#78dffc'
        }
      },
      data: chinaMapData.value
    }
  }
  chinaMap.setOption(option)
}
// 世界地图默认展示英文国家名，在echarts的options中配置nameMap进行转化
let nameMap = {
  Afghanistan: '阿富汗',
  Singapore: '新加坡',
  Angola: '安哥拉',
  Albania: '阿尔巴尼亚',
  'United Arab Emirates': '阿联酋',
  Argentina: '阿根廷',
  Armenia: '亚美尼亚',
  'French Southern and Antarctic Lands': '法属南半球和南极领地',
  Australia: '澳大利亚',
  Austria: '奥地利',
  Azerbaijan: '阿塞拜疆',
  Burundi: '布隆迪',
  Belgium: '比利时',
  Benin: '贝宁',
  'Burkina Faso': '布基纳法索',
  Bangladesh: '孟加拉国',
  Bulgaria: '保加利亚',
  'The Bahamas': '巴哈马',
  'Bosnia and Herzegovina': '波斯尼亚和黑塞哥维那',
  Belarus: '白俄罗斯',
  Belize: '伯利兹',
  Bermuda: '百慕大',
  Bolivia: '玻利维亚',
  Brazil: '巴西',
  Brunei: '文莱',
  Bhutan: '不丹',
  Botswana: '博茨瓦纳',
  'Central African Republic': '中非共和国',
  Canada: '加拿大',
  Switzerland: '瑞士',
  Chile: '智利',
  China: '中国',
  'Ivory Coast': '象牙海岸',
  Cameroon: '喀麦隆',
  'Democratic Republic of the Congo': '刚果民主共和国',
  'Republic of the Congo': '刚果共和国',
  Colombia: '哥伦比亚',
  'Costa Rica': '哥斯达黎加',
  Cuba: '古巴',
  'Northern Cyprus': '北塞浦路斯',
  Cyprus: '塞浦路斯',
  'Czech Republic': '捷克共和国',
  Germany: '德国',
  Djibouti: '吉布提',
  Denmark: '丹麦',
  'Dominican Republic': '多明尼加共和国',
  Algeria: '阿尔及利亚',
  Ecuador: '厄瓜多尔',
  Egypt: '埃及',
  Eritrea: '厄立特里亚',
  Spain: '西班牙',
  Estonia: '爱沙尼亚',
  Ethiopia: '埃塞俄比亚',
  Finland: '芬兰',
  Fiji: '斐',
  'Falkland Islands': '福克兰群岛',
  France: '法国',
  Gabon: '加蓬',
  'United Kingdom': '英国',
  Georgia: '格鲁吉亚',
  Ghana: '加纳',
  Guinea: '几内亚',
  Gambia: '冈比亚',
  'Guinea Bissau': '几内亚比绍',
  Greece: '希腊',
  Greenland: '格陵兰',
  Guatemala: '危地马拉',
  'French Guiana': '法属圭亚那',
  Guyana: '圭亚那',
  Honduras: '洪都拉斯',
  Croatia: '克罗地亚',
  Haiti: '海地',
  Hungary: '匈牙利',
  Indonesia: '印度尼西亚',
  India: '印度',
  Ireland: '爱尔兰',
  Iran: '伊朗',
  Iraq: '伊拉克',
  Iceland: '冰岛',
  Israel: '以色列',
  Italy: '意大利',
  Jamaica: '牙买加',
  Jordan: '约旦',
  Japan: '日本',
  Kazakhstan: '哈萨克斯坦',
  Kenya: '肯尼亚',
  Kyrgyzstan: '吉尔吉斯斯坦',
  Cambodia: '柬埔寨',
  Kosovo: '科索沃',
  Kuwait: '科威特',
  Laos: '老挝',
  Lebanon: '黎巴嫩',
  Liberia: '利比里亚',
  Libya: '利比亚',
  'Sri Lanka': '斯里兰卡',
  Lesotho: '莱索托',
  Lithuania: '立陶宛',
  Luxembourg: '卢森堡',
  Latvia: '拉脱维亚',
  Morocco: '摩洛哥',
  Moldova: '摩尔多瓦',
  Madagascar: '马达加斯加',
  Mexico: '墨西哥',
  Macedonia: '马其顿',
  Mali: '马里',
  Myanmar: '缅甸',
  Montenegro: '黑山',
  Mongolia: '蒙古',
  Mozambique: '莫桑比克',
  Mauritania: '毛里塔尼亚',
  Malawi: '马拉维',
  Malaysia: '马来西亚',
  Namibia: '纳米比亚',
  'New Caledonia': '新喀里多尼亚',
  Niger: '尼日尔',
  Nigeria: '尼日利亚',
  Nicaragua: '尼加拉瓜',
  Netherlands: '荷兰',
  Norway: '挪威',
  Nepal: '尼泊尔',
  'New Zealand': '新西兰',
  Oman: '阿曼',
  Pakistan: '巴基斯坦',
  Panama: '巴拿马',
  Peru: '秘鲁',
  Philippines: '菲律宾',
  'Papua New Guinea': '巴布亚新几内亚',
  Poland: '波兰',
  'Puerto Rico': '波多黎各',
  'North Korea': '北朝鲜',
  Portugal: '葡萄牙',
  Paraguay: '巴拉圭',
  Qatar: '卡塔尔',
  Romania: '罗马尼亚',
  Russia: '俄罗斯',
  Rwanda: '卢旺达',
  'Western Sahara': '西撒哈拉',
  'Saudi Arabia': '沙特阿拉伯',
  Sudan: '苏丹',
  'South Sudan': '南苏丹',
  Senegal: '塞内加尔',
  'Solomon Islands': '所罗门群岛',
  'Sierra Leone': '塞拉利昂',
  'El Salvador': '萨尔瓦多',
  Somaliland: '索马里兰',
  Somalia: '索马里',
  'Republic of Serbia': '塞尔维亚',
  Suriname: '苏里南',
  Slovakia: '斯洛伐克',
  Slovenia: '斯洛文尼亚',
  Sweden: '瑞典',
  Swaziland: '斯威士兰',
  Syria: '叙利亚',
  Chad: '乍得',
  Togo: '多哥',
  Thailand: '泰国',
  Tajikistan: '塔吉克斯坦',
  Turkmenistan: '土库曼斯坦',
  'East Timor': '东帝汶',
  'Trinidad and Tobago': '特里尼达和多巴哥',
  Tunisia: '突尼斯',
  Turkey: '土耳其',
  'United Republic of Tanzania': '坦桑尼亚',
  Uganda: '乌干达',
  Ukraine: '乌克兰',
  Uruguay: '乌拉圭',
  'United States': '美国',
  Uzbekistan: '乌兹别克斯坦',
  Venezuela: '委内瑞拉',
  Vietnam: '越南',
  Vanuatu: '瓦努阿图',
  'West Bank': '西岸',
  Yemen: '也门',
  'South Africa': '南非',
  Zambia: '赞比亚',
  Korea: '韩国',
  Tanzania: '坦桑尼亚',
  Zimbabwe: '津巴布韦',
  Congo: '刚果',
  'Central African Rep.': '中非',
  Serbia: '塞尔维亚',
  'Bosnia and Herz.': '波黑',
  'Czech Rep.': '捷克',
  'W. Sahara': '西撒哈拉',
  'Lao PDR': '老挝',
  'Dem.Rep.Korea': '朝鲜',
  'Falkland Is.': '福克兰群岛',
  'Timor-Leste': '东帝汶',
  'Solomon Is.': '所罗门群岛',
  Palestine: '巴勒斯坦',
  'N. Cyprus': '北塞浦路斯',
  Aland: '奥兰群岛',
  'Fr. S. Antarctic Lands': '法属南半球和南极陆地',
  Mauritius: '毛里求斯',
  Comoros: '科摩罗',
  'Eq. Guinea': '赤道几内亚',
  'Guinea-Bissau': '几内亚比绍',
  'Dominican Rep.': '多米尼加',
  'Saint Lucia': '圣卢西亚',
  Dominica: '多米尼克',
  'Antigua and Barb.': '安提瓜和巴布达',
  'U.S. Virgin Is.': '美国原始岛屿',
  Montserrat: '蒙塞拉特',
  Grenada: '格林纳达',
  Barbados: '巴巴多斯',
  Samoa: '萨摩亚',
  Bahamas: '巴哈马',
  'Cayman Is.': '开曼群岛',
  'Faeroe Is.': '法罗群岛',
  'IsIe of Man': '马恩岛',
  Malta: '马耳他共和国',
  Jersey: '泽西',
  'Cape Verde': '佛得角共和国',
  'Turks and Caicos Is.': '特克斯和凯科斯群岛',
  'St. Vin. and Gren.': '圣文森特和格林纳丁斯'
}
const initWorldMap = async () => {
  const { default: worldMapDefinition } = await import('@/assets/maps/world.json')
  if (!echarts.getMap('world')) {
    echarts.registerMap('world', worldMapDefinition)
  }
  // 世界地图
  const worldMapDom = document.querySelector('.worldMap')
  if (!worldMapDom || !dialogVisible.value) return
  const worldMap = echarts.getInstanceByDom(worldMapDom) || echarts.init(worldMapDom)
  const option = {
    tooltip: {
      formatter: '{b0}: {c0}'
    },
    nameMap,
    visualMap: {
      min: 0,
      max: 1000,
      left: 'left',
      top: 'bottom',
      text: ['高', '低'],
      calculable: false,
      orient: 'horizontal',
      inRange: {
        // 最小值到最大值的颜色
        color: ['#e0ffff', '#006edd'],
        symbolSize: [30, 100]
      }
    },
    series: {
      name: '数量',
      type: 'map',
      map: 'world',
      itemStyle: {
        // 鼠标移入后的样式
        emphasis: {
          // 鼠标移入后的颜色
          areaColor: '#78dffc'
        }
      },
      data: worldMapData.value
    }
  }
  worldMap.setOption(option)
}
const initMap = () => {
  initChinaMap()
  if (!isChina.value) {
    void initWorldMap()
  }
}
// 访问次数相关
const isLine = ref(true)
// 访问曲线数据请求
const initLineChart = () => {
  const lineChartDom = document.querySelector('.lineChart')
  if (!lineChartDom || !dialogVisible.value) return
  const lineChart = echarts.getInstanceByDom(lineChartDom) || echarts.init(lineChartDom)
  let option = {
    title: {
      show: false,
      text: 'Stacked Line'
    },
    tooltip: {
      trigger: 'axis'
    },
    legend: {
      data: ['访问次数', '访问人数', '访问IP数']
    },
    grid: {
      left: '3%',
      right: '9%',
      bottom: '3%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: dailyXAxis.value
    },
    yAxis: {
      type: 'value'
    },
    series: [
      {
        name: '访问次数',
        type: 'line',
        data: pvList.value
      },
      {
        name: '访问人数',
        type: 'line',
        data: uvList.value
      },
      {
        name: '访问IP数',
        type: 'line',
        data: uipList.value
      }
    ]
  }
  lineChart.setOption(option)
}
// 总访问次数
const totalPv = ref(0)
// 访问次数数据集数组
const pvList = ref([])
// 总访问人数
const totalUv = ref(0)
// 访问人数数据集数组
const uvList = ref([])
// 总访问IP数
const totalUip = ref(0)
// 访问IP数数据集数组
const uipList = ref([])
// 访问表格数据
const visitsData = ref()
// 访问设备和访问网络
const userTypeList = ref([0, 0])
const deviceList = ref([0, 0])
watch(
  () => props.info?.uvTypeStats,
  () => {
    // 初始化
    userTypeList.value = [0, 0]
    // 对访问用户类型的数据进行转化
    props.info?.uvTypeStats?.forEach((item) => {
      if (item.uvType === 'newUser') {
        userTypeList.value[0] = item.cnt
      } else if (item.uvType === 'oldUser') {
        userTypeList.value[1] = item.cnt
      }
    })
  },
  {
    immediate: true
  }
)
watch(
  () => props.info?.deviceStats,
  () => {
    deviceList.value = [0, 0]
    // 对访问设备类型的数据进行转化
    props.info?.deviceStats?.forEach((item) => {
      if (item.device === 'Mobile') {
        deviceList.value[1] = item.cnt
      } else {
        deviceList.value[0] = item.cnt
      }
    })
  },
  {
    immediate: true
  }
)

// Statistics can arrive before this lazy component mounts. Initialize from the
// current props only after every ref and chart initializer above is available.
watch(
  () => props.info?.localeCnStats,
  () => {
    chinaTotalNum.value = 0
    chinaMapData.value = (props.info?.localeCnStats || []).map((item) => {
      let { cnt, locale, ratio } = item
      locale = locale.replace('省', '')
      locale = locale.replace('市', '')
      chinaTotalNum.value += cnt
      return { name: locale, value: cnt, ratio }
    })
    initChinaMap()
  },
  { deep: true, immediate: true }
)
watch(
  () => props.info?.daily,
  () => {
    totalPv.value = 0
    totalUv.value = 0
    totalUip.value = 0
    pvList.value = []
    uvList.value = []
    uipList.value = []
    dailyXAxis.value = []
    visitsData.value = props.info?.daily
    visitsData.value?.forEach((item) => {
      const { pv, uv, uip, date } = item
      const formDate = date.split('-')[1] + '月' + date.split('-')[2] + '日'
      totalPv.value += pv
      totalUv.value += uv
      totalUip.value += uip
      pvList.value.push(pv)
      uvList.value.push(uv)
      uipList.value.push(uip)
      dailyXAxis.value.push(formDate)
    })
    // Distinct visitors/IPs across days must come from the aggregate, not daily sums.
    totalPv.value = Number(props.info?.pv ?? totalPv.value)
    totalUv.value = Number(props.info?.uv ?? totalUv.value)
    totalUip.value = Number(props.info?.uip ?? totalUip.value)
    initLineChart()
  },
  { immediate: true }
)
</script>

<style lang="less" scoped>
.dimension-note { margin: 12px 16px; color: #606266; font-size: 13px; line-height: 1.6; }
.content-box {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  width: 100%;

  .chart-item {
    height: 300px;
    min-width: 300px;
    margin: 10px;
  }
}

.chinaMap {
  width: 330px;
  height: 240px;
}

.worldMap {
  width: 330px;
  height: 240px;
}

.list-chart {
  display: flex;
  justify-content: space-between;

  .top10 {
    padding: 15px 30px;
    width: 400px;
    height: 270px;
    display: flex;
    flex-direction: column;
    overflow-y: auto;

    .top-item {
      display: flex;
      flex-direction: column;
      flex-wrap: wrap;

      div {
        height: 40px;
        display: flex;
        align-items: center;
        margin-right: 30px;
      }
    }

    span:nth-child(1) {
      color: #3464e0;
      font-size: 12px;
      font-weight: 700;
    }

    .key-value {
      display: flex;
      justify-content: space-between;
      width: 150px;
    }
  }
}

.lineChart {
  margin: 10px;
  width: 600px;
  height: 200px;
}

.flex-box {
  display: flex;
  justify-content: space-around;
}

.pagination-block {
  .el-pagination {
    margin-left: 20%;
  }
}
</style>
