import {
  computed,
  defineComponent,
  inject,
  onBeforeUnmount,
  ref,
  watch,
} from '/vendor/vue.js';

const METRICS = Object.freeze([
  { key: 'pv', label: '访问次数', short: 'PV', value: '18,642', note: '所选时间窗内的全部访问' },
  { key: 'uv', label: '访问人数', short: 'UV', value: '7,921', note: '所选范围内去重访客' },
  { key: 'uip', label: '独立 IP', short: 'UIP', value: '6,305', note: '所选范围内去重 IP' },
]);

const TREND = Object.freeze([
  { date: '09-08', pv: 1850, uv: 920, uip: 704 },
  { date: '09-09', pv: 2310, uv: 1050, uip: 831 },
  { date: '09-10', pv: 2842, uv: 1260, uip: 996 },
  { date: '09-11', pv: 2640, uv: 1170, uip: 912 },
  { date: '09-12', pv: 3040, uv: 1390, uip: 1082 },
  { date: '09-13', pv: 2760, uv: 1250, uip: 988 },
  { date: '09-14', pv: 3200, uv: 1520, uip: 1194 },
]);

const DIMENSION_TABS = Object.freeze([
  { key: 'country', label: '国家' },
  { key: 'province', label: '省份' },
  { key: 'hour', label: '24 小时' },
  { key: 'weekday', label: '星期' },
  { key: 'ip', label: '高频 IP' },
  { key: 'os', label: '操作系统' },
  { key: 'browser', label: '浏览器' },
  { key: 'device', label: '设备' },
  { key: 'isp', label: '运营商 / ISP' },
  { key: 'newvisitor', label: '新老访客' },
]);

const DIMENSION_DATA = Object.freeze({
  country: {
    title: '国家分布',
    note: '无法离线定位的访问单独计入未知。',
    rows: [
      ['中国', '17,214', '92.3%'],
      ['新加坡', '312', '1.7%'],
      ['美国', '218', '1.2%'],
      ['未知', '898', '4.8%'],
    ],
  },
  province: {
    title: '省份分布',
    note: '地域来自 IP 离线归属，未知不会并入其他省份。',
    rows: [
      ['广东', '5,966', '32.0%'],
      ['浙江', '3,915', '21.0%'],
      ['北京', '2,796', '15.0%'],
      ['其他已识别', '5,067', '27.2%'],
      ['未知', '898', '4.8%'],
    ],
  },
  hour: {
    title: '24 小时访问分布',
    note: '时间统一按北京时间展示。',
    rows: [
      ['00:00 至 05:59', '1,326', '7.1%'],
      ['06:00 至 11:59', '4,492', '24.1%'],
      ['12:00 至 17:59', '7,084', '38.0%'],
      ['18:00 至 23:59', '5,740', '30.8%'],
    ],
  },
  weekday: {
    title: '一周访问分布',
    note: '当前时间窗覆盖完整的 7 个自然日。',
    rows: [
      ['周二', '1,850', '9.9%'],
      ['周三', '2,310', '12.4%'],
      ['周四', '2,842', '15.2%'],
      ['周五', '2,640', '14.2%'],
      ['周六', '3,040', '16.3%'],
      ['周日', '2,760', '14.8%'],
      ['周一', '3,200', '17.2%'],
    ],
  },
  ip: {
    title: '高频 IP',
    note: 'IP 已脱敏；此表用于定位异常信号，不直接等同于风险判定。',
    rows: [
      ['203.0.113.***', '486', '2.6%'],
      ['198.51.100.***', '341', '1.8%'],
      ['192.0.2.***', '208', '1.1%'],
      ['其他 IP', '17,607', '94.5%'],
    ],
  },
  os: {
    title: '操作系统',
    note: 'User-Agent 无法可靠识别时单独显示未知。',
    rows: [
      ['Android', '7,894', '42.3%'],
      ['iOS', '4,774', '25.6%'],
      ['Windows', '3,430', '18.4%'],
      ['macOS', '1,649', '8.9%'],
      ['未知', '895', '4.8%'],
    ],
  },
  browser: {
    title: '浏览器',
    note: '浏览器来自请求 User-Agent 解析。',
    rows: [
      ['Chrome', '10,067', '54.0%'],
      ['Safari', '4,922', '26.4%'],
      ['Edge', '1,742', '9.3%'],
      ['其他已识别', '1,016', '5.5%'],
      ['未知', '895', '4.8%'],
    ],
  },
  device: {
    title: '设备类型',
    note: '设备类型由 User-Agent 解析，不代表具体设备身份。',
    rows: [
      ['移动设备', '12,677', '68.0%'],
      ['桌面设备', '5,219', '28.0%'],
      ['平板及其他', '373', '2.0%'],
      ['未知', '373', '2.0%'],
    ],
  },
  isp: {
    title: '运营商 / ISP',
    note: '按 IP 异步离线归属，不能据此判断 Wi-Fi、4G 或 5G。',
    rows: [
      ['中国电信', '7,830', '42.0%'],
      ['中国移动', '5,592', '30.0%'],
      ['中国联通', '3,728', '20.0%'],
      ['其他已识别', '746', '4.0%'],
      ['未知', '746', '4.0%'],
    ],
  },
  newvisitor: {
    title: '新老访客',
    note: '按所选短链 / 分组的历史首次观测判断，历史覆盖 2026-08-16 至 2026-09-14。',
    rows: [
      ['新访客', '10,626', '57.0%'],
      ['老访客', '7,270', '39.0%'],
      ['历史不足 / 未知', '746', '4.0%'],
    ],
  },
});

const RECORD_BATCHES = Object.freeze([
  {
    cursor: 'snap_0914_A / start',
    previous: null,
    next: 'cursor_A2',
    rows: [
      ['2026-09-14 14:28:31', '203.0.113.***', 'visitor_7f***2a', '广东', '移动设备', 'Chrome / Android', '中国电信', '2026-08-16 10:04', '跳转成功', 'CLICK'],
      ['2026-09-14 14:27:08', '198.51.100.***', 'visitor_91***c4', '浙江', '移动设备', 'Safari / iOS', '未知', '2026-09-14 14:27', '跳转成功', 'CLICK'],
      ['2026-09-14 14:24:42', '192.0.2.***', 'visitor_35***e8', '未知', '桌面设备', 'Edge / Windows', '中国联通', '历史不足 / 未知', '跳转成功', 'CLICK'],
    ],
  },
  {
    cursor: 'snap_0914_A / cursor_A2',
    previous: 'start',
    next: 'cursor_A3',
    rows: [
      ['2026-09-14 14:20:04', '192.0.2.***', 'visitor_18***b6', '北京', '桌面设备', 'Edge / Windows', '中国联通', '2026-09-02 08:11', '跳转成功', 'CLICK'],
      ['2026-09-14 14:19:42', '203.0.113.***', 'visitor_6a***90', '广东', '桌面设备', 'Chrome / macOS', '中国电信', '2026-08-25 17:42', '跳转成功', 'CLICK'],
      ['2026-09-14 14:16:18', '203.0.113.***', 'visitor_2c***51', '广东', '移动设备', 'Chrome / Android', '中国电信', '2026-09-08 20:06', '业务拒绝', 'BUSINESS'],
    ],
  },
  {
    cursor: 'snap_0914_A / cursor_A3',
    previous: 'cursor_A2',
    next: null,
    rows: [
      ['2026-09-14 14:12:09', '198.51.100.***', 'visitor_42***77', '浙江', '移动设备', 'Safari / iOS', '中国移动', '2026-08-30 11:22', '跳转成功', 'CLICK'],
      ['2026-09-14 14:09:33', '192.0.2.***', 'visitor_a8***13', '上海', '移动设备', 'Chrome / Android', '中国联通', '2026-09-12 09:18', '跳转成功', 'CLICK'],
    ],
  },
]);

const asDate = (value) => {
  const date = new Date(`${value}T00:00:00+08:00`);
  return Number.isNaN(date.getTime()) ? null : date;
};

const numeric = (value) => Number(String(value).replaceAll(',', '')) || 0;
const formatted = (value) => Math.round(value).toLocaleString('zh-CN');

const formatSession = (type, sequence) => {
  const prefix = type === 'security-risk' ? 'sec' : 'cmp';
  return `${prefix}_demo_${String(sequence).padStart(3, '0')}`;
};

function requireRelay() {
  const relay = inject('relay');
  if (!relay) throw new Error('Analytics and Agent views require the relay provider.');
  return relay;
}

export const AnalyticsView = defineComponent({
  name: 'AnalyticsView',
  setup() {
    const relay = requireRelay();
    const initialScope = relay.state.analyticsScope || { type: 'group', id: relay.state.groupId || 'autumn' };
    const scopeType = ref(initialScope.type === 'link' ? 'link' : 'group');
    const scopeId = ref(initialScope.id || relay.state.groupId || 'autumn');
    const startDate = ref('2026-09-08');
    const endDate = ref('2026-09-14');
    const viewMode = ref('chart');
    const dimension = ref('country');
    const status = ref('PARTIAL');
    const demoState = ref('normal');
    const recordsOpen = ref(false);
    const recordBatch = ref(0);
    const recordState = ref('READY');
    const loadingTimer = ref(null);
    const recordTimer = ref(null);

    const groupOptions = computed(() => {
      const groups = relay.state.groups || [];
      if (!groups.length) return [{ label: '秋日投放 · 4 条活动短链', value: 'autumn' }];
      return groups.map((group) => ({
        label: `${group.name || group.title || '未命名分组'} · ${(relay.state.links || []).filter((link) => link.groupId === (group.id || group.gid) && !link.recycled).length} 条活动短链`,
        value: group.id || group.gid,
      }));
    });

    const linkOptions = computed(() => {
      const links = relay.state.links || [];
      if (!links.length) return [{ label: '秋日新品 · 主会场', value: '7b3N9kX2q' }];
      return links.filter((link) => !link.recycled).map((link) => ({
        label: link.description || link.title || link.shortCode || link.code,
        value: link.id || link.shortCode || link.code || link.fullShortUrl,
      }));
    });

    const scopeOptions = computed(() => (scopeType.value === 'group' ? groupOptions.value : linkOptions.value));
    const selectedAnalyticsScopeLabel = computed(() => scopeOptions.value.find((item) => item.value === scopeId.value)?.label || '当前统计范围');

    const selectedGroupSize = computed(() => {
      if (demoState.value === 'too-large') return 501;
      const group = (relay.state.groups || []).find((item) => (item.id || item.gid) === scopeId.value);
      if (!group) return 0;
      return (relay.state.links || []).filter((link) => link.groupId === (group.id || group.gid) && !link.recycled).length;
    });

    const dateSpan = computed(() => {
      const start = asDate(startDate.value);
      const end = asDate(endDate.value);
      if (!start || !end) return null;
      return Math.floor((end.getTime() - start.getTime()) / 86400000) + 1;
    });

    const scopeError = computed(() => {
      if (!dateSpan.value || dateSpan.value < 1) return '结束日期不能早于开始日期。';
      if (dateSpan.value > 7) return '一次统计查询最多覆盖 7 天，请缩短日期范围。';
      if (scopeType.value === 'group' && selectedGroupSize.value > 500) {
        return '分组超过 500 条短链，请缩小范围。';
      }
      return '';
    });

    const selectedLink = computed(() => (relay.state.links || []).find((link) => (link.id || link.shortCode || link.code) === scopeId.value));
    const selectedGroupLinks = computed(() => (relay.state.links || []).filter((link) => link.groupId === scopeId.value && !link.recycled));
    const baseMetricNumbers = computed(() => {
      if (scopeType.value === 'link') {
        if (!selectedLink.value || selectedLink.value.recycled) return { pv: 0, uv: 0, uip: 0 };
        return {
          pv: Number(selectedLink.value.pv || 0),
          uv: Number(selectedLink.value.uv || 0),
          uip: Number(selectedLink.value.uip || 0),
        };
      }
      return selectedGroupLinks.value.reduce((totals, link) => ({
        pv: totals.pv + Number(link.pv || 0),
        uv: totals.uv + Number(link.uv || 0),
        uip: totals.uip + Number(link.uip || 0),
      }), { pv: 0, uv: 0, uip: 0 });
    });
    const trendInRange = computed(() => TREND.filter((row) => {
      const isoDate = `2026-${row.date}`;
      return isoDate >= startDate.value && isoDate <= endDate.value;
    }));
    const currentMetricNumbers = computed(() => {
      const rangeTotals = trendInRange.value.reduce((totals, row) => ({
        pv: totals.pv + row.pv,
        uv: totals.uv + row.uv,
        uip: totals.uip + row.uip,
      }), { pv: 0, uv: 0, uip: 0 });
      return {
        pv: Math.round(baseMetricNumbers.value.pv * rangeTotals.pv / 18642),
        uv: Math.round(baseMetricNumbers.value.uv * rangeTotals.uv / 8560),
        uip: Math.round(baseMetricNumbers.value.uip * rangeTotals.uip / 6707),
      };
    });
    const currentMetrics = computed(() => METRICS.map((metric) => ({
      ...metric,
      value: formatted(currentMetricNumbers.value[metric.key]),
    })));
    const currentTrend = computed(() => {
      const pvRatio = baseMetricNumbers.value.pv / 18642;
      const uvRatio = baseMetricNumbers.value.uv / 7921;
      const uipRatio = baseMetricNumbers.value.uip / 6305;
      return trendInRange.value.map((row) => ({
        date: row.date,
        pv: Math.round(row.pv * pvRatio),
        uv: Math.round(row.uv * uvRatio),
        uip: Math.round(row.uip * uipRatio),
      }));
    });
    const chartGeometry = computed(() => {
      const rows = currentTrend.value;
      const left = 52;
      const right = 724;
      const top = 48;
      const bottom = 208;
      const max = Math.max(1, ...rows.flatMap((row) => [row.pv, row.uv, row.uip]));
      const plotted = rows.map((row, index) => {
        const x = rows.length === 1 ? (left + right) / 2 : left + (right - left) * index / (rows.length - 1);
        const y = (value) => bottom - (bottom - top) * value / max;
        return { ...row, x, pvY: y(row.pv), uvY: y(row.uv), uipY: y(row.uip) };
      });
      const points = (key) => plotted.map((row) => `${row.x.toFixed(1)},${row[key].toFixed(1)}`).join(' ');
      const peak = rows.reduce((best, row) => (row.pv > (best?.pv || -1) ? row : best), null);
      return {
        rows: plotted,
        pv: points('pvY'),
        uv: points('uvY'),
        uip: points('uipY'),
        description: peak
          ? `${startDate.value} 至 ${endDate.value} 共 ${rows.length} 个有数据自然日，PV 峰值为 ${peak.pv.toLocaleString()}。`
          : `${startDate.value} 至 ${endDate.value} 没有可展示的趋势数据。`,
      };
    });
    const dimensionData = computed(() => {
      const source = DIMENSION_DATA[dimension.value];
      if (dimension.value === 'weekday') {
        const weekdayNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
        const total = Math.max(1, currentMetricNumbers.value.pv);
        return {
          ...source,
          note: `按当前 ${startDate.value} 至 ${endDate.value} 时间窗展示。`,
          rows: currentTrend.value.map((row) => {
            const weekday = weekdayNames[new Date(`2026-${row.date}T00:00:00Z`).getUTCDay()];
            return [weekday, formatted(row.pv), `${(row.pv / total * 100).toFixed(1)}%`];
          }),
        };
      }
      const ratio = currentMetricNumbers.value.pv / 18642;
      return {
        ...source,
        rows: source.rows.map((row) => [row[0], formatted(numeric(row[1]) * ratio), row[2]]),
      };
    });
    const currentBatch = computed(() => RECORD_BATCHES[recordBatch.value]);
    const showEmpty = computed(() => demoState.value === 'empty'
      || (!scopeError.value && (!currentTrend.value.length || currentMetricNumbers.value.pv === 0)));
    const showServiceError = computed(() => demoState.value === 'error');
    const hasRecordFixture = computed(() => startDate.value <= '2026-09-14' && endDate.value >= '2026-09-14');

    const applyScope = () => {
      if (scopeError.value) {
        relay.notify(scopeError.value, 'warning');
        return;
      }
      if (loadingTimer.value) window.clearTimeout(loadingTimer.value);
      status.value = 'LOADING';
      loadingTimer.value = window.setTimeout(() => {
        status.value = 'PARTIAL';
        relay.notify('统计快照已更新，部分维度仍在异步汇总。', 'info');
      }, 520);
    };

    const openRecords = () => {
      if (scopeError.value) {
        relay.notify(scopeError.value, 'warning');
        return;
      }
      if (showEmpty.value || showServiceError.value || status.value === 'LOADING') {
        relay.notify('当前范围没有可读取的访问记录。', 'warning');
        return;
      }
      if (recordTimer.value) window.clearTimeout(recordTimer.value);
      recordTimer.value = null;
      recordBatch.value = 0;
      recordState.value = hasRecordFixture.value ? 'READY' : 'NO_DATA';
      recordsOpen.value = true;
    };

    const moveBatch = (direction) => {
      if (recordState.value !== 'READY') return;
      const next = recordBatch.value + direction;
      if (next < 0 || next >= RECORD_BATCHES.length) return;
      if (recordTimer.value) window.clearTimeout(recordTimer.value);
      recordState.value = 'LOADING';
      recordTimer.value = window.setTimeout(() => {
        recordTimer.value = null;
        recordBatch.value = next;
        recordState.value = 'READY';
      }, 520);
    };

    const expireRecordCursor = () => {
      if (recordTimer.value) window.clearTimeout(recordTimer.value);
      recordTimer.value = null;
      recordState.value = 'EXPIRED';
    };

    const reloadRecordSnapshot = () => {
      if (recordTimer.value) window.clearTimeout(recordTimer.value);
      recordBatch.value = 0;
      recordState.value = 'LOADING';
      recordTimer.value = window.setTimeout(() => {
        recordTimer.value = null;
        recordState.value = hasRecordFixture.value ? 'READY' : 'NO_DATA';
        relay.notify(
          hasRecordFixture.value ? '已重新读取当前范围的首批访问记录。' : '当前时间窗没有本地原始记录样例。',
          hasRecordFixture.value ? 'info' : 'warning',
        );
      }, 520);
    };

    const closeRecords = () => {
      if (recordTimer.value) window.clearTimeout(recordTimer.value);
      recordTimer.value = null;
      recordState.value = 'READY';
      recordsOpen.value = false;
    };

    watch(scopeType, () => {
      scopeId.value = scopeOptions.value[0]?.value || '';
    });

    watch([scopeType, scopeId], ([type, id]) => {
      relay.state.analyticsScope = { type, id };
    });

    watch(demoState, (value) => {
      if (value === 'too-large') scopeType.value = 'group';
    });

    onBeforeUnmount(() => {
      if (loadingTimer.value) window.clearTimeout(loadingTimer.value);
      if (recordTimer.value) window.clearTimeout(recordTimer.value);
    });

    return {
      relay,
      currentMetrics,
      currentTrend,
      chartGeometry,
      DIMENSION_TABS,
      scopeType,
      scopeId,
      scopeOptions,
      selectedAnalyticsScopeLabel,
      startDate,
      endDate,
      viewMode,
      dimension,
      dimensionData,
      status,
      demoState,
      recordsOpen,
      recordBatch,
      recordState,
      currentBatch,
      scopeError,
      showEmpty,
      showServiceError,
      applyScope,
      openRecords,
      moveBatch,
      expireRecordCursor,
      reloadRecordSnapshot,
      closeRecords,
    };
  },
  template: `
    <section class="ins-view analytics-view" aria-labelledby="analytics-title">
      <header class="ins-page-head">
        <div>
          <p class="ins-kicker">观测台 / ANALYTICS</p>
          <h1 id="analytics-title">访问统计</h1>
          <p>在一个可核验的时间窗中查看访问、访客和独立 IP 信号。</p>
        </div>
        <div class="ins-head-actions">
          <RBadge v-if="scopeError" tone="danger">范围无效</RBadge>
          <RBadge v-else-if="status === 'LOADING'" tone="info">LOADING</RBadge>
          <RBadge v-else-if="showServiceError" tone="danger">ERROR</RBadge>
          <RBadge v-else-if="showEmpty" tone="unknown">EMPTY</RBadge>
          <RBadge v-else tone="warning">PARTIAL</RBadge>
          <RButton kind="secondary" :disabled="showEmpty || showServiceError || status === 'LOADING'" @click="openRecords">访问记录</RButton>
        </div>
      </header>

      <section class="ins-panel analytics-scope" aria-labelledby="analytics-scope-title">
        <div class="ins-section-title">
          <span class="ins-icon-box"><RIcon name="chart" /></span>
          <div>
            <h2 id="analytics-scope-title">统计范围</h2>
            <p>单次最多 7 天；分组范围最多 500 条短链。</p>
          </div>
        </div>
        <div class="analytics-scope-grid">
          <RSelect v-model="scopeType" label="对象类型" :options="[
            { label: '分组', value: 'group' },
            { label: '单条短链', value: 'link' }
          ]" />
          <RSelect v-model="scopeId" label="统计对象" :options="scopeOptions" />
          <RDateTime v-model="startDate" label="开始日期" type="date" />
          <RDateTime v-model="endDate" label="结束日期" type="date" />
          <RButton class="analytics-apply" kind="primary" :disabled="status === 'LOADING'" @click="applyScope">
            {{ status === 'LOADING' ? '正在读取' : '应用范围' }}
          </RButton>
        </div>
        <div v-if="scopeError" class="ins-alert ins-alert-danger" role="alert">
          <RIcon name="shield" />
          <span>{{ scopeError }}</span>
          <RBadge v-if="scopeError.includes('500')" tone="danger">TOO_LARGE</RBadge>
        </div>
        <div v-else class="ins-meta-row">
          <span>{{ startDate }} 至 {{ endDate }}</span>
          <span>北京时间</span>
          <span>更新于 2026-09-14 14:32</span>
        </div>
      </section>

      <details class="ins-demo-tools">
        <summary>本地交互演示工具</summary>
        <p>仅切换原型状态，不发起接口请求。</p>
        <RSelect v-model="demoState" label="统计场景" :options="[
          { label: '正常且部分完成', value: 'normal' },
          { label: '真正无数据', value: 'empty' },
          { label: '分组超过 500 条', value: 'too-large' },
          { label: '服务读取失败', value: 'error' }
        ]" />
      </details>

      <div v-if="status === 'LOADING'" class="ins-panel analytics-loading" aria-live="polite">
        <div class="ins-skeleton"></div>
        <div class="ins-skeleton ins-skeleton-short"></div>
        <p>正在读取当前范围的统计快照。</p>
      </div>

      <div v-else-if="showServiceError" class="ins-panel ins-empty-state" role="alert">
        <RRobot role="navigator" expression="recovery" :size="132" />
        <div>
          <h2>暂时无法读取统计快照</h2>
          <p>当前原型模拟服务失败。调整演示场景后可重新读取。</p>
          <RButton kind="primary" @click="demoState = 'normal'; applyScope()">重新读取</RButton>
        </div>
      </div>

      <div v-else-if="showEmpty" class="ins-panel ins-empty-state">
        <RRobot role="navigator" expression="neutral" :size="132" />
        <div>
          <h2>这个范围还没有访问数据</h2>
          <p>这是真正的空结果。可以调整日期或统计对象后再试。</p>
        </div>
      </div>

      <template v-else>
        <div class="analytics-quality ins-alert ins-alert-warning">
          <RIcon name="outbox" />
          <div>
            <strong>异步汇总中，当前快照可能不完整</strong>
            <span>completeness = PARTIAL；未知维度单独呈现，不按 0 处理。</span>
          </div>
          <RBadge tone="warning">PARTIAL</RBadge>
        </div>

        <section class="analytics-metrics" aria-label="核心统计指标">
          <article v-for="metric in currentMetrics" :key="metric.key" class="ins-panel analytics-metric">
            <span class="analytics-metric-code">{{ metric.short }}</span>
            <strong>{{ metric.value }}</strong>
            <span>{{ metric.label }}</span>
            <small>{{ metric.note }}</small>
          </article>
        </section>

        <section class="ins-panel analytics-trend" aria-labelledby="trend-title">
          <div class="ins-section-title analytics-trend-head">
            <div>
              <h2 id="trend-title">所选时间窗访问趋势</h2>
              <p>日 UV / UIP 用于趋势观察；顶部指标为整个时间窗去重。</p>
            </div>
            <div class="ins-segmented" aria-label="趋势展示方式">
              <RButton :kind="viewMode === 'chart' ? 'primary' : 'text'" :aria-pressed="viewMode === 'chart'" @click="viewMode = 'chart'">图表</RButton>
              <RButton :kind="viewMode === 'table' ? 'primary' : 'text'" :aria-pressed="viewMode === 'table'" @click="viewMode = 'table'">数据表</RButton>
            </div>
          </div>

          <div v-if="viewMode === 'chart'" class="analytics-chart-wrap">
            <div class="analytics-legend" aria-label="图例">
              <span><i class="analytics-swatch analytics-swatch-pv"></i>PV</span>
              <span><i class="analytics-swatch analytics-swatch-uv"></i>UV</span>
              <span><i class="analytics-swatch analytics-swatch-uip"></i>UIP</span>
            </div>
            <svg class="analytics-chart" viewBox="0 0 760 250" role="img" aria-labelledby="trend-svg-title trend-svg-desc">
              <title id="trend-svg-title">所选时间窗 PV、UV、UIP 趋势</title>
              <desc id="trend-svg-desc">{{ chartGeometry.description }}</desc>
              <g class="analytics-grid-lines">
                <line x1="52" y1="34" x2="734" y2="34" />
                <line x1="52" y1="92" x2="734" y2="92" />
                <line x1="52" y1="150" x2="734" y2="150" />
                <line x1="52" y1="208" x2="734" y2="208" />
              </g>
              <polyline class="analytics-line analytics-line-pv" :points="chartGeometry.pv" />
              <polyline class="analytics-line analytics-line-uv" :points="chartGeometry.uv" />
              <polyline class="analytics-line analytics-line-uip" :points="chartGeometry.uip" />
              <g class="analytics-points">
                <circle v-for="row in chartGeometry.rows" :key="'pv-' + row.date" class="analytics-point-pv" :cx="row.x" :cy="row.pvY" r="4" />
                <circle v-for="row in chartGeometry.rows" :key="'uv-' + row.date" class="analytics-point-uv" :cx="row.x" :cy="row.uvY" r="4" />
                <circle v-for="row in chartGeometry.rows" :key="'uip-' + row.date" class="analytics-point-uip" :cx="row.x" :cy="row.uipY" r="4" />
              </g>
              <g class="analytics-axis-labels">
                <text v-for="row in chartGeometry.rows" :key="'label-' + row.date" :x="row.x" y="236">{{ row.date }}</text>
              </g>
            </svg>
          </div>

          <div v-else class="ins-table-wrap">
            <table class="ins-table">
              <caption class="ins-sr-only">所选时间窗 PV、UV、UIP 数据表</caption>
              <thead><tr><th>日期</th><th>PV</th><th>UV</th><th>UIP</th></tr></thead>
              <tbody><tr v-for="row in currentTrend" :key="row.date"><td>{{ row.date }}</td><td>{{ row.pv.toLocaleString() }}</td><td>{{ row.uv.toLocaleString() }}</td><td>{{ row.uip.toLocaleString() }}</td></tr></tbody>
            </table>
          </div>
        </section>

        <section class="ins-panel analytics-dimensions" aria-labelledby="dimension-title">
          <div class="ins-section-title">
            <span class="ins-icon-box ins-icon-mint"><RIcon name="globe" /></span>
            <div>
              <h2 id="dimension-title">维度分析</h2>
              <p>所有未知值保持独立，切换维度不会改变统计范围。</p>
            </div>
          </div>
          <div class="analytics-tabs" role="tablist" aria-label="统计维度">
            <button v-for="tab in DIMENSION_TABS" :key="tab.key" type="button" class="analytics-tab" :class="{ 'is-active': dimension === tab.key }" role="tab" :aria-selected="dimension === tab.key" @click="dimension = tab.key">{{ tab.label }}</button>
          </div>
          <div class="analytics-dimension-head">
            <div><h3>{{ dimensionData.title }}</h3><p>{{ dimensionData.note }}</p></div>
            <RBadge tone="unknown">含未知项</RBadge>
          </div>
          <div class="ins-table-wrap">
            <table class="ins-table analytics-ranking-table">
              <thead><tr><th>分类</th><th>访问次数</th><th>占比</th></tr></thead>
              <tbody><tr v-for="row in dimensionData.rows" :key="row[0]" :class="{ 'is-unknown': row[0].includes('未知') }"><td>{{ row[0] }}</td><td>{{ row[1] }}</td><td>{{ row[2] }}</td></tr></tbody>
            </table>
          </div>
          <p v-if="dimension === 'newvisitor'" class="analytics-history-note">
            历史保留范围：2026-08-16 至 2026-09-14。更早访问不可用，无法判断时显示未知；新设备 Cookie 不等同于真实新用户。
          </p>
        </section>
      </template>

      <RModal :open="recordsOpen" title="访问记录" :drawer="true" @close="closeRecords">
        <div class="analytics-records">
          <div class="analytics-records-meta">
            <RBadge :tone="recordState === 'EXPIRED' ? 'danger' : recordState === 'NO_DATA' ? 'unknown' : 'info'">
              {{ recordState === 'NO_DATA' ? '无可用样例快照' : 'snapshotId: snap_0914_A' }}
            </RBadge>
            <span v-if="recordState !== 'NO_DATA'">当前游标：{{ currentBatch.cursor }}</span>
          </div>
          <p>范围：{{ selectedAnalyticsScopeLabel }}；时间窗：{{ startDate }} 至 {{ endDate }}；北京时间。IP 与访客标识均已脱敏，记录按 snapshotId + cursor 逐批读取，不提供固定总页数。</p>

          <div v-if="recordState === 'LOADING'" class="ins-alert ins-alert-info" role="status" aria-live="polite">
            <RIcon name="outbox" />
            <span>正在读取当前快照的记录批次，请稍候。</span>
            <RBadge tone="info">LOADING</RBadge>
          </div>

          <div v-else-if="recordState === 'EXPIRED'" class="ins-alert ins-alert-danger" role="alert">
            <RIcon name="shield" />
            <div><strong>当前快照游标已失效</strong><span>不能继续前后翻批。重新读取会回到首批，并清除失效状态。</span></div>
            <RButton kind="secondary" @click="reloadRecordSnapshot">重新读取首批</RButton>
          </div>

          <div v-else-if="recordState === 'NO_DATA'" class="ins-alert ins-alert-info" role="status">
            <RIcon name="outbox" />
            <div><strong>当前时间窗没有本地原始记录样例</strong><span>示例访问记录只覆盖 2026-09-14；不会把 9 月 14 日记录放入其他时间窗。</span></div>
          </div>

          <div v-else class="ins-table-wrap">
            <table class="ins-table analytics-record-table">
              <thead><tr><th>访问时间</th><th>IP / 访客</th><th>地区</th><th>设备</th><th>浏览器 / 系统</th><th>ISP</th><th>历史首次观测</th><th>结果 / 事件</th></tr></thead>
              <tbody>
                <tr v-for="row in currentBatch.rows" :key="row[0] + row[2]">
                  <td>{{ row[0] }}</td><td><code>{{ row[1] }}</code><small>{{ row[2] }}</small></td><td>{{ row[3] }}</td><td>{{ row[4] }}</td><td>{{ row[5] }}</td><td>{{ row[6] }}</td><td>{{ row[7] }}</td><td>{{ row[8] }}<small>{{ row[9] }}</small></td>
                </tr>
              </tbody>
            </table>
          </div>

          <details v-if="recordState !== 'NO_DATA'" class="ins-demo-tools">
            <summary>访问记录本地演示工具</summary>
            <p>只模拟快照游标状态，不发起后端请求。</p>
            <RButton kind="secondary" :disabled="recordState !== 'READY'" @click="expireRecordCursor">模拟当前游标失效</RButton>
          </details>
          <div class="analytics-history-box">
            <strong>访客历史口径</strong>
            <span>按当前短链 / 分组历史首次观测区分；历史覆盖 2026-08-16 至 2026-09-14。</span>
          </div>
        </div>
        <template #footer>
          <div class="ins-modal-actions">
            <RButton kind="secondary" :disabled="recordState !== 'READY' || !currentBatch.previous" @click="moveBatch(-1)">上一批</RButton>
            <RButton kind="secondary" :disabled="recordState !== 'READY' || !currentBatch.next" @click="moveBatch(1)">下一批</RButton>
            <RButton kind="text" @click="closeRecords">关闭</RButton>
          </div>
        </template>
      </RModal>
    </section>
  `,
});

const AGENT_COPY = Object.freeze({
  'campaign-analysis': {
    title: '投放分析 Agent',
    role: 'navigator',
    roleName: '领航员 Navigator',
    prompt: '分析最近 7 天的访问表现，指出数据缺口，并给出下一步投放建议。',
    presets: ['比较访问高峰时段', '说明地域与设备分布', '核对新老访客统计口径'],
  },
  'security-risk': {
    title: '安全风控 Agent',
    role: 'guardian',
    roleName: '守护员 Guardian',
    prompt: '核查近期异常访问，说明风险证据、数据完整度和自动限流结果。',
    presets: ['检查高频访问和异常峰值', '解释风险 reason codes', '核验 LIMIT_RATE 传播结果'],
  },
});

export const AgentView = defineComponent({
  name: 'AgentView',
  props: {
    type: {
      type: String,
      default: 'campaign-analysis',
      validator: (value) => value === 'campaign-analysis' || value === 'security-risk',
    },
  },
  setup(props) {
    const relay = requireRelay();
    const prompt = ref('');
    const scope = ref('autumn');
    const runState = ref('READY');
    const scenario = ref('success');
    const debugOpen = ref(false);
    const result = ref(null);
    const errorMessage = ref('');
    const timer = ref(null);
    const runToken = ref(0);

    if (!relay.state.agentSessions) relay.state.agentSessions = {};

    const copy = computed(() => AGENT_COPY[props.type]);
    const isSecurity = computed(() => props.type === 'security-risk');
    const session = computed(() => relay.state.agentSessions[props.type]);
    const count = computed(() => prompt.value.length);
    const isRunning = computed(() => runState.value === 'RUNNING');
    const scopeOptions = computed(() => {
      const groups = relay.state.groups || [];
      const options = groups.map((group) => {
        const value = group.id || group.gid;
        const activeCount = (relay.state.links || []).filter((link) => link.groupId === value && !link.recycled).length;
        return {
          label: `${group.name || group.title || '分组'} · ${activeCount} 条活动短链`,
          value,
          activeCount,
        };
      });
      return options.length ? options : [{ label: '秋日投放 · 4 条活动短链', value: 'autumn', activeCount: 4 }];
    });
    const selectedScopeLabel = computed(() => scopeOptions.value.find((item) => item.value === scope.value)?.label || '当前授权范围');
    const selectedScopeLinks = computed(() => (relay.state.links || []).filter((link) => link.groupId === scope.value && !link.recycled));
    const selectedScopeCount = computed(() => selectedScopeLinks.value.length);
    const selectedScopeMetrics = computed(() => selectedScopeLinks.value.reduce((totals, link) => ({
      pv: totals.pv + Number(link.pv || 0),
      uv: totals.uv + Number(link.uv || 0),
      uip: totals.uip + Number(link.uip || 0),
    }), { pv: 0, uv: 0, uip: 0 }));
    const compiledMessage = computed(() => `分析范围：${selectedScopeLabel.value}，最近 7 天。用户问题：${prompt.value.trim()}`);

    const ensureSession = (type, renew = false) => {
      const current = relay.state.agentSessions[type];
      const sequence = renew ? Number(current?.sequence || 1) + 1 : Number(current?.sequence || 1);
      if (!current || renew) {
        const preferredScope = relay.state.groupId || 'autumn';
        const initialScope = scopeOptions.value.find((item) => item.value === preferredScope)?.value
          || scopeOptions.value[0]?.value
          || preferredScope;
        relay.state.agentSessions[type] = {
          id: formatSession(type, sequence),
          sequence,
          createdAt: '2026-09-14 14:32',
          prompt: AGENT_COPY[type].prompt,
          scope: initialScope,
          runState: 'READY',
          scenario: 'success',
          result: null,
          errorMessage: '',
          debugOpen: false,
        };
      }
    };

    const persistSessionView = () => {
      const current = relay.state.agentSessions[props.type];
      if (!current) return;
      Object.assign(current, {
        prompt: prompt.value,
        scope: scope.value,
        runState: runState.value,
        scenario: scenario.value,
        result: result.value,
        errorMessage: errorMessage.value,
        debugOpen: debugOpen.value,
      });
    };

    const cancelPending = (resetRunning = false) => {
      runToken.value += 1;
      if (timer.value) window.clearTimeout(timer.value);
      timer.value = null;
      if (isRunning.value) {
        relay.state.agentBusy = false;
        if (resetRunning) {
          runState.value = 'READY';
          result.value = null;
          errorMessage.value = '上一次本地演示已取消，迟到结果将被丢弃。';
        }
      }
    };

    const hydrateSession = () => {
      const current = relay.state.agentSessions[props.type];
      const preferredScope = relay.state.groupId || 'autumn';
      const savedScope = scopeOptions.value.find((item) => item.value === current?.scope)?.value;
      const fallbackScope = scopeOptions.value.find((item) => item.value === preferredScope)?.value
        || scopeOptions.value[0]?.value
        || preferredScope;
      prompt.value = current?.prompt ?? AGENT_COPY[props.type].prompt;
      scope.value = savedScope || fallbackScope;
      runState.value = current?.runState === 'RUNNING' ? 'READY' : (current?.runState || 'READY');
      scenario.value = current?.scenario || 'success';
      result.value = current?.runState === 'RUNNING' ? null : (current?.result || null);
      errorMessage.value = current?.runState === 'RUNNING'
        ? '上一次本地演示已取消，迟到结果将被丢弃。'
        : (current?.errorMessage || '');
      debugOpen.value = Boolean(current?.debugOpen);
      persistSessionView();
    };

    const resetForType = () => {
      cancelPending(true);
      ensureSession(props.type);
      hydrateSession();
    };

    const newSession = () => {
      if (isRunning.value) return;
      ensureSession(props.type, true);
      hydrateSession();
      relay.notify('已为当前 Agent 创建独立会话。', 'info');
    };

    const usePreset = (value) => {
      if (isRunning.value) return;
      prompt.value = value;
    };

    const buildResult = () => {
      if (isSecurity.value) {
        return {
          headline: '检测到需要关注的高频访问',
          answer: '主会场近 2 小时出现集中访问。确定性规则已激活 LIMIT_RATE，但传播状态仍未知；请在风险中心继续核验，不能把传播未知视为全面生效。',
          warning: '统计完整度为 PARTIAL，风险结论需要结合后续快照复核。',
          evidence: [
            ['风险等级', '高风险 · 86'],
            ['reason codes', 'HIGH_FREQUENCY_IP · TRAFFIC_SPIKE'],
            ['窗口指标', '2 小时 PV 840 / UV 126；24 小时 PV 3,216 / UV 912'],
            ['脱敏访问证据', '203.0.113.*** 在 2 小时窗口内访问集中'],
            ['自动 LIMIT_RATE', '已激活 · 传播状态未知'],
          ],
        };
      }
      const metrics = selectedScopeMetrics.value;
      return {
        headline: '访问高峰集中在午后与晚间',
        answer: `所选范围在最近 7 天记录 ${formatted(metrics.pv)} 次访问、${formatted(metrics.uv)} 位访客和 ${formatted(metrics.uip)} 个独立 IP。午后与晚间访问较集中；由于部分维度仍在汇总，暂不对渠道优劣作确定判断。`,
        warning: '建议先核对统计完整度，再结合原始投放记录验证渠道差异。',
        evidence: [
          ['统计范围', `${selectedScopeLabel.value} · 2026-09-08 至 2026-09-14`],
          ['核心指标', `PV ${formatted(metrics.pv)} · UV ${formatted(metrics.uv)} · UIP ${formatted(metrics.uip)}`],
          ['数据质量', 'PARTIAL · 未知维度单独呈现'],
          ['访客口径', '按短链 / 分组历史首次观测；历史覆盖 08-16 至 09-14'],
          ['更新时间', '2026-09-14 14:32 · 北京时间'],
        ],
      };
    };

    const run = () => {
      const value = prompt.value.trim();
      if (!value) {
        errorMessage.value = '请输入需要分析的问题。';
        return;
      }
      if (value.length > 2000) {
        errorMessage.value = '问题不能超过 2000 字。';
        return;
      }
      if (!selectedScopeCount.value) {
        runState.value = 'READY';
        result.value = null;
        errorMessage.value = '所选分组没有活动短链，当前没有可分析的数据。';
        return;
      }
      cancelPending();
      const token = runToken.value;
      const startedType = props.type;
      runState.value = 'RUNNING';
      result.value = null;
      errorMessage.value = '';
      relay.state.agentBusy = true;
      timer.value = window.setTimeout(() => {
        if (token !== runToken.value || startedType !== props.type) return;
        relay.state.agentBusy = false;
        timer.value = null;
        if (scenario.value === 'failure') {
          runState.value = 'ERROR';
          errorMessage.value = '本地演示：同步分析未完成，请重试。没有产生回答或执行轨迹。';
          return;
        }
        runState.value = 'SUCCESS';
        result.value = buildResult();
      }, 1180);
    };

    watch(() => props.type, resetForType, { immediate: true });
    watch(scope, () => {
      if (isRunning.value) return;
      runState.value = 'READY';
      result.value = null;
      errorMessage.value = '';
    });
    watch([prompt, scope, runState, scenario, result, errorMessage, debugOpen], persistSessionView, { deep: true });
    onBeforeUnmount(() => {
      cancelPending(true);
      persistSessionView();
    });

    return {
      relay,
      copy,
      isSecurity,
      prompt,
      scope,
      scopeOptions,
      runState,
      scenario,
      debugOpen,
      result,
      errorMessage,
      session,
      count,
      isRunning,
      selectedScopeCount,
      compiledMessage,
      newSession,
      usePreset,
      run,
    };
  },
  template: `
    <section class="ins-view agent-view" :class="{ 'agent-view-security': isSecurity }" :aria-labelledby="'agent-title-' + type">
      <header class="ins-page-head agent-page-head">
        <div>
          <p class="ins-kicker">{{ isSecurity ? '守护台 / SECURITY' : '领航台 / CAMPAIGN' }}</p>
          <h1 :id="'agent-title-' + type">{{ copy.title }}</h1>
          <p>{{ isSecurity ? '用可核验的访问证据解释异常与策略结果。' : '从统计快照中提炼有依据的投放洞察。' }}</p>
        </div>
        <div class="ins-head-actions">
          <RBadge tone="success">HTTP 入口可达</RBadge>
          <RButton kind="secondary" :disabled="isRunning" @click="newSession">新会话</RButton>
        </div>
      </header>

      <div class="agent-health-note">
        健康状态只表示 Agent HTTP 入口可达，不代表 Graph、Analytics、Tool 或 LLM 分别就绪。
      </div>

      <div class="agent-layout">
        <aside class="ins-panel agent-composer">
          <div class="agent-identity">
            <RRobot :role="copy.role" expression="neutral" :size="126" />
            <div>
              <RBadge :tone="isSecurity ? 'warning' : 'info'">{{ copy.roleName }}</RBadge>
              <strong>{{ session?.id }}</strong>
              <span>两个 Agent 使用独立 Session</span>
            </div>
          </div>

          <RSelect v-model="scope" label="分析范围" :options="scopeOptions" :disabled="isRunning" />
          <p class="agent-scope-note">范围控件只会编译为 message 文本；权限范围由当前登录主体在服务端确定。</p>
          <div v-if="!selectedScopeCount" class="ins-alert ins-alert-warning" role="status">所选分组没有活动短链，不能生成分析结果。</div>

          <RTextarea v-model="prompt" label="你想了解什么？" :maxlength="2000" :rows="7" :count="false" :disabled="isRunning" />
          <div class="agent-count" :class="{ 'is-over': count > 2000 }">{{ count }} / 2000</div>

          <div class="agent-presets" aria-label="快捷问题">
            <RButton v-for="preset in copy.presets" :key="preset" kind="text" :disabled="isRunning" @click="usePreset(preset)">{{ preset }}</RButton>
          </div>

          <div v-if="errorMessage && runState !== 'ERROR'" class="ins-alert ins-alert-danger" role="alert">{{ errorMessage }}</div>
          <RButton class="agent-run" kind="primary" :disabled="isRunning || count > 2000 || !selectedScopeCount" @click="run">
            {{ isRunning ? '正在等待同步响应' : '开始分析' }}
          </RButton>
          <p class="agent-demo-disclosure">全局演示说明：本页面仅用本地延时模拟同步响应，不会发起真实 HTTP 请求。</p>

          <details class="ins-demo-tools">
            <summary>本地交互演示工具</summary>
            <RSelect v-model="scenario" label="下一次响应" :options="[
              { label: '成功响应', value: 'success' },
              { label: '失败响应', value: 'failure' }
            ]" />
          </details>
        </aside>

        <main class="agent-output" aria-live="polite">
          <section v-if="runState === 'READY'" class="ins-panel agent-ready">
            <RRobot :role="copy.role" expression="neutral" :size="152" />
            <div>
              <RBadge tone="unknown">READY</RBadge>
              <h2>{{ isSecurity ? '先看证据，再核验风险' : '让每个判断都有数据依据' }}</h2>
              <p>选择范围并提交问题。完成后一次展示回答、证据与执行轨迹。</p>
            </div>
          </section>

          <section v-else-if="runState === 'RUNNING'" class="ins-panel agent-running" role="status">
            <div class="agent-orbit-loader" aria-hidden="true"><span></span><i></i></div>
            <div>
              <RBadge tone="info">RUNNING</RBadge>
              <h2>正在等待完整分析结果</h2>
              <p>这是同步请求的通用等待状态。期间不能新建会话或切换 Agent；当前不会显示流式文字、实时 Tool 或 Graph 节点。</p>
            </div>
          </section>

          <section v-else-if="runState === 'ERROR'" class="ins-panel agent-error" role="alert">
            <RRobot :role="copy.role" expression="recovery" :size="126" />
            <div>
              <RBadge tone="danger">FAILED</RBadge>
              <h2>分析没有完成</h2>
              <p>{{ errorMessage }}</p>
              <RButton kind="primary" @click="run">重试本次问题</RButton>
            </div>
          </section>

          <template v-else-if="runState === 'SUCCESS' && result">
            <section class="ins-panel agent-answer">
              <div class="agent-answer-heading">
                <div>
                  <RBadge :tone="isSecurity ? 'warning' : 'info'">一次性完整回答</RBadge>
                  <h2>{{ result.headline }}</h2>
                </div>
                <RRobot :role="copy.role" expression="success" :size="74" />
              </div>
              <p class="agent-answer-copy">{{ result.answer }}</p>
              <div class="ins-alert ins-alert-warning"><RIcon name="shield" /><span>{{ result.warning }}</span></div>
            </section>

            <section class="ins-panel agent-evidence">
              <div class="ins-section-title">
                <span class="ins-icon-box ins-icon-mint"><RIcon name="database" /></span>
                <div><h2>回答证据</h2><p>数据来源、口径与时效随回答一起保留。</p></div>
              </div>
              <dl>
                <div v-for="item in result.evidence" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ item[1] }}</dd></div>
              </dl>
              <RButton v-if="isSecurity" kind="secondary" @click="relay.go('/home/risk-center')">到风险中心核验</RButton>
              <RButton v-else kind="secondary" @click="relay.go('/home/analytics')">查看统计工作区</RButton>
            </section>

            <section class="ins-panel agent-trace">
              <div class="ins-section-title">
                <span class="ins-icon-box ins-icon-sun"><RIcon name="cpu" /></span>
                <div><h2>Completed Tool Timeline</h2><p>仅在同步响应完成后展示。</p></div>
              </div>
              <ol class="agent-timeline">
                <li><span>01</span><div><strong>读取授权范围</strong><small>服务端当前登录主体</small></div><RBadge tone="success">完成</RBadge></li>
                <li><span>02</span><div><strong>{{ isSecurity ? '读取风险与访问证据' : '读取统计快照' }}</strong><small>snapshot 2026-09-14 14:32</small></div><RBadge tone="success">完成</RBadge></li>
                <li><span>03</span><div><strong>汇总证据与数据缺口</strong><small>PARTIAL 与 unknown 已保留</small></div><RBadge tone="success">完成</RBadge></li>
                <li><span>04</span><div><strong>形成一次性回答</strong><small>同步响应已结束</small></div><RBadge tone="success">完成</RBadge></li>
              </ol>

              <div class="agent-graph" aria-label="Graph Trace">
                <div class="agent-graph-node">Scope</div><span>→</span>
                <div class="agent-graph-node">{{ isSecurity ? 'Risk Tools' : 'Analytics Tools' }}</div><span>→</span>
                <div class="agent-graph-node">Evidence</div><span>→</span>
                <div class="agent-graph-node agent-graph-node-final">Answer</div>
              </div>

              <details class="agent-debug" :open="debugOpen" @toggle="debugOpen = $event.currentTarget.open">
                <summary>折叠调试数据</summary>
                <pre>{
  "sessionId": "{{ session?.id }}",
  "agentType": "{{ type }}",
  "message": "{{ compiledMessage }}",
  "transport": "local-sync-demo",
  "lateResponsePolicy": "discard"
}</pre>
              </details>
            </section>
          </template>
        </main>
      </div>
    </section>
  `,
});

const REVIEW_ACTIONS = Object.freeze([
  { value: 'WATCH', label: '关注 · WATCH' },
  { value: 'UNWATCH', label: '取消关注 · UNWATCH' },
  { value: 'FALSE_POSITIVE', label: '标记误报 · FALSE_POSITIVE' },
  { value: 'CONFIRM_RISK', label: '确认风险 · CONFIRM_RISK' },
  { value: 'IGNORE', label: '忽略 · IGNORE' },
]);

const AUTO_LIMIT_LABELS = Object.freeze({
  INACTIVE: ['未触发', 'unknown'],
  ACTIVATED: ['已激活', 'success'],
  FAILED: ['激活失败', 'danger'],
  PROPAGATION_UNKNOWN: ['已激活 · 传播未知', 'warning'],
});

const POLICY_LABELS = Object.freeze({
  ACTIVE: ['现有策略已激活', 'success'],
  SUBMITTED: ['停用请求已提交', 'info'],
  EXECUTED: ['停用已执行', 'success'],
  UNKNOWN: ['停用结果待核实', 'warning'],
  FAILED: ['停用失败', 'danger'],
});

export const RiskView = defineComponent({
  name: 'RiskView',
  setup() {
    const relay = requireRelay();
    const detailOpen = ref(false);
    const reviewOpen = ref(false);
    const disableOpen = ref(false);
    const reviewAction = ref('WATCH');
    const reviewNote = ref('继续关注近期异常访问，结合后续证据复核。');
    const reviewState = ref('IDLE');
    const reviewOutcome = ref('recorded');
    const watchStatus = ref('WATCH');
    const autoLimitState = ref('PROPAGATION_UNKNOWN');
    const policyState = ref('ACTIVE');
    const policyOutcome = ref('UNKNOWN');
    const commandId = 'cmd_demo_20260914_001';
    const reviewTimer = ref(null);
    const policyTimer = ref(null);

    const fixtureLink = computed(() => {
      const candidates = relay.fixtures?.links || relay.state.links || [];
      const found = candidates.find((link) => {
        const code = link.shortCode || link.code || link.fullShortUrl || '';
        return String(code).includes('7b3N9kX2q');
      });
      return {
        title: found?.description || found?.title || '秋日新品 · 主会场',
        code: found?.shortCode || found?.code || '7b3N9kX2q',
        pv: found?.pv ?? 12486,
        uv: found?.uv ?? 5218,
        uip: found?.uip ?? 4036,
      };
    });

    const autoLimit = computed(() => AUTO_LIMIT_LABELS[autoLimitState.value]);
    const policy = computed(() => POLICY_LABELS[policyState.value]);
    const autumnActiveCount = computed(() => (relay.state.links || []).filter((link) => link.groupId === 'autumn' && !link.recycled).length);
    const watchingCount = computed(() => (watchStatus.value === 'WATCH' ? 1 : 0));

    const beginReview = () => {
      reviewState.value = 'IDLE';
      detailOpen.value = false;
      reviewOpen.value = true;
    };

    const submitReview = () => {
      if (reviewTimer.value) window.clearTimeout(reviewTimer.value);
      reviewState.value = 'SUBMITTING';
      reviewTimer.value = window.setTimeout(() => {
        reviewTimer.value = null;
        if (reviewOutcome.value === 'failed') {
          reviewState.value = 'FAILED';
          return;
        }
        reviewState.value = 'RECORDED';
        watchStatus.value = reviewAction.value;
        relay.notify('人工审核判断已记录，策略状态未改变。', 'success');
      }, 620);
    };

    const openDisable = () => {
      if (policyState.value === 'EXECUTED') return;
      disableOpen.value = true;
    };

    const submitDisable = () => {
      if (policyTimer.value) window.clearTimeout(policyTimer.value);
      policyState.value = 'SUBMITTED';
      disableOpen.value = false;
      policyTimer.value = window.setTimeout(() => {
        policyTimer.value = null;
        policyState.value = policyOutcome.value;
        relay.notify(
          policyOutcome.value === 'EXECUTED' ? '已核验停用操作执行完成。' : '停用结果尚未成功确认，请按原 commandId 继续核验。',
          policyOutcome.value === 'EXECUTED' ? 'success' : 'warning',
        );
      }, 720);
    };

    const verifyPolicy = () => {
      if (policyState.value !== 'UNKNOWN') return;
      submitDisable();
    };

    onBeforeUnmount(() => {
      if (reviewTimer.value) window.clearTimeout(reviewTimer.value);
      if (policyTimer.value) window.clearTimeout(policyTimer.value);
    });

    return {
      relay,
      REVIEW_ACTIONS,
      fixtureLink,
      detailOpen,
      reviewOpen,
      disableOpen,
      reviewAction,
      reviewNote,
      reviewState,
      reviewOutcome,
      watchStatus,
      autoLimitState,
      autoLimit,
      policyState,
      policyOutcome,
      policy,
      autumnActiveCount,
      watchingCount,
      commandId,
      beginReview,
      submitReview,
      openDisable,
      submitDisable,
      verifyPolicy,
    };
  },
  template: `
    <section class="ins-view risk-view" aria-labelledby="risk-title">
      <header class="ins-page-head">
        <div>
          <p class="ins-kicker">守护台 / RISK CENTER</p>
          <h1 id="risk-title">风险中心</h1>
          <p>固定运营与审计页面，用证据、判断和策略状态核验风险。</p>
        </div>
        <div class="ins-head-actions">
          <RButton kind="secondary" @click="relay.go('/home/agent/security-risk')">咨询安全风控 Agent</RButton>
        </div>
      </header>

      <div class="risk-scope-strip ins-panel">
        <div><span>当前分组</span><strong>秋日投放 · {{ autumnActiveCount }} 条活动短链</strong></div>
        <div><span>观察窗口</span><strong>最近 7 天</strong></div>
        <div><span>本次扫描</span><strong>{{ autumnActiveCount }} 条活动短链</strong></div>
        <RBadge tone="warning">PARTIAL</RBadge>
      </div>

      <section class="risk-summary" aria-label="风险总览">
        <article class="ins-panel risk-summary-score"><span>分组风险分</span><strong>28</strong><small>平均 24 · 最高 86</small></article>
        <article class="ins-panel risk-level risk-level-high"><span>高风险</span><strong>1</strong><small>需要优先核验</small></article>
        <article class="ins-panel risk-level risk-level-medium"><span>中风险</span><strong>1</strong><small>持续观察</small></article>
        <article class="ins-panel risk-level risk-level-low"><span>低风险</span><strong>2</strong><small>当前无显著信号</small></article>
        <article class="ins-panel risk-level risk-level-watch"><span>关注中</span><strong>{{ watchingCount }}</strong><small>人工 watchStatus</small></article>
      </section>

      <div class="risk-coverage ins-alert ins-alert-info">
        <RIcon name="shield" />
        <div><strong>currentPolicyCoverage = TOP_CARDS_ONLY</strong><span>只有头部风险卡片提供策略详情；全组 disabledCount 为 UNKNOWN，不推算已停用数量。</span></div>
        <RBadge tone="unknown">UNKNOWN</RBadge>
      </div>

      <section class="ins-panel risk-trend" aria-labelledby="risk-trend-title">
        <div class="ins-section-title">
          <span class="ins-icon-box ins-icon-coral"><RIcon name="chart" /></span>
          <div><h2 id="risk-trend-title">最近 7 天风险趋势</h2><p>分组风险分：18、22、19、35、29、31、28。</p></div>
        </div>
        <svg viewBox="0 0 760 210" role="img" aria-labelledby="risk-svg-title risk-svg-desc">
          <title id="risk-svg-title">秋日投放七日风险分趋势</title>
          <desc id="risk-svg-desc">风险分在第四天达到 35，当前为 28。</desc>
          <g class="risk-grid-lines"><line x1="48" y1="28" x2="724" y2="28" /><line x1="48" y1="88" x2="724" y2="88" /><line x1="48" y1="148" x2="724" y2="148" /></g>
          <path class="risk-area" d="M52 142 L164 122 L276 137 L388 71 L500 92 L612 83 L724 96 L724 170 L52 170 Z" />
          <polyline class="risk-line" points="52,142 164,122 276,137 388,71 500,92 612,83 724,96" />
          <g class="risk-points"><circle cx="52" cy="142" r="5"/><circle cx="164" cy="122" r="5"/><circle cx="276" cy="137" r="5"/><circle cx="388" cy="71" r="5"/><circle cx="500" cy="92" r="5"/><circle cx="612" cy="83" r="5"/><circle cx="724" cy="96" r="6"/></g>
        </svg>
      </section>

      <div class="risk-main-grid">
        <section class="ins-panel risk-links" aria-labelledby="risk-links-title">
          <div class="ins-section-title"><div><h2 id="risk-links-title">高风险短链</h2><p>卡片与详情使用同一份快照示例。</p></div></div>
          <article class="risk-link-card">
            <div class="risk-link-head"><div><h3>{{ fixtureLink.title }}</h3><code>s.example/{{ fixtureLink.code }}</code></div><RBadge tone="danger">86 · 高风险</RBadge></div>
            <p class="risk-reason">HIGH_FREQUENCY_IP · TRAFFIC_SPIKE</p>
            <dl class="risk-window-metrics">
              <div><dt>2 小时</dt><dd>PV 840 / UV 126</dd></div>
              <div><dt>24 小时</dt><dd>PV 3,216 / UV 912</dd></div>
              <div><dt>7 天</dt><dd>PV {{ Number(fixtureLink.pv).toLocaleString() }} / UV {{ Number(fixtureLink.uv).toLocaleString() }}</dd></div>
            </dl>
            <div class="risk-card-status"><span>关注状态：{{ watchStatus }}</span><span>自动 LIMIT_RATE：{{ autoLimit[0] }}</span><span>数据质量：PARTIAL</span></div>
            <RButton kind="primary" @click="detailOpen = true">查看证据与策略</RButton>
          </article>
        </section>

        <section class="ins-panel risk-events" aria-labelledby="risk-events-title">
          <div class="ins-section-title"><div><h2 id="risk-events-title">近期风险事件</h2><p>事件上下文来自当前分组与短链。</p></div></div>
          <article class="risk-event">
            <div><RBadge tone="warning">待人工判断</RBadge><time>2026-09-14 14:24</time></div>
            <h3>主会场出现高频访问特征</h3>
            <p>evidence：脱敏 IP 在 2 小时窗口内集中访问。</p>
            <dl><div><dt>source</dt><dd>RULE_ANALYSIS</dd></div><div><dt>targetType</dt><dd>SHORT_LINK</dd></div><div><dt>traceId</dt><dd>trace_risk_0914_01</dd></div><div><dt>sessionId</dt><dd>sec_demo_001</dd></div></dl>
            <p class="risk-agent-summary">Agent 摘要：建议先核对访问证据，再决定人工审核结论。</p>
            <RButton kind="secondary" @click="beginReview">记录人工审核</RButton>
          </article>
        </section>
      </div>

      <section class="risk-state-machines" aria-labelledby="risk-state-title">
        <div class="ins-section-title"><div><h2 id="risk-state-title">三条独立状态链</h2><p>人工判断、自动限流和人工停用已有策略互不替代。</p></div></div>
        <div class="risk-machine-grid">
          <article class="ins-panel risk-machine">
            <span class="risk-machine-index">A</span><h3>人工审核记录</h3><RBadge :tone="reviewState === 'FAILED' ? 'danger' : reviewState === 'RECORDED' ? 'success' : 'unknown'">{{ reviewState }}</RBadge>
            <p>只记录 WATCH 等判断，不执行、停用或撤销策略。</p><RButton kind="secondary" @click="beginReview">记录判断</RButton>
          </article>
          <article class="ins-panel risk-machine">
            <span class="risk-machine-index">B</span><h3>自动 LIMIT_RATE</h3><RBadge :tone="autoLimit[1]">{{ autoLimit[0] }}</RBadge>
            <p>来自安全 Agent 的确定性节点，传播未知不等于全面生效。</p>
          </article>
          <article class="ins-panel risk-machine">
            <span class="risk-machine-index">C</span><h3>停用已有策略</h3><RBadge :tone="policy[1]">{{ policy[0] }}</RBadge>
            <p><code>{{ commandId }}</code></p>
            <div class="risk-machine-actions"><RButton kind="danger" :disabled="policyState !== 'ACTIVE'" @click="openDisable">停用这项策略</RButton><RButton v-if="policyState === 'UNKNOWN'" kind="secondary" @click="verifyPolicy">按原编号核验</RButton></div>
          </article>
        </div>
      </section>

      <details class="ins-demo-tools risk-demo-tools">
        <summary>本地交互演示工具</summary>
        <p>只切换三条链路的结果状态，不提供策略 CRUD。</p>
        <div class="risk-demo-grid">
          <RSelect v-model="reviewOutcome" label="人工审核下一结果" :options="[{ label: '记录成功', value: 'recorded' }, { label: '记录失败', value: 'failed' }]" />
          <RSelect v-model="autoLimitState" label="自动限流状态" :options="[{ label: '未触发', value: 'INACTIVE' }, { label: '已激活', value: 'ACTIVATED' }, { label: '激活失败', value: 'FAILED' }, { label: '传播未知', value: 'PROPAGATION_UNKNOWN' }]" />
          <RSelect v-model="policyOutcome" label="停用核验下一结果" :options="[{ label: '执行完成', value: 'EXECUTED' }, { label: '状态待核实', value: 'UNKNOWN' }, { label: '执行失败', value: 'FAILED' }]" />
        </div>
      </details>

      <RModal :open="detailOpen" title="风险短链接详情" :drawer="true" @close="detailOpen = false">
        <div class="risk-detail">
          <div class="risk-detail-title"><div><h3>{{ fixtureLink.title }}</h3><code>s.example/{{ fixtureLink.code }}</code></div><RBadge tone="danger">86 · 高风险</RBadge></div>
          <dl class="risk-detail-list">
            <div><dt>2 小时 PV / UV</dt><dd>840 / 126</dd></div><div><dt>24 小时 PV / UV</dt><dd>3,216 / 912</dd></div><div><dt>7 天 PV / UV / UIP</dt><dd>{{ Number(fixtureLink.pv).toLocaleString() }} / {{ Number(fixtureLink.uv).toLocaleString() }} / {{ Number(fixtureLink.uip).toLocaleString() }}</dd></div><div><dt>reason codes</dt><dd>HIGH_FREQUENCY_IP · TRAFFIC_SPIKE</dd></div><div><dt>watchStatus</dt><dd>{{ watchStatus }}</dd></div><div><dt>当前策略</dt><dd>LIMIT_RATE · {{ autoLimit[0] }}</dd></div><div><dt>latestSnapshot</dt><dd>2026-09-14 14:32 · PARTIAL</dd></div>
          </dl>
          <div class="risk-detail-evidence"><strong>recentEvents</strong><p>14:24 · RULE_ANALYSIS · 脱敏 IP 在短时窗口内集中访问。</p><p>recommendedActions：记录人工判断；核验现有 LIMIT_RATE 的传播状态。</p></div>
        </div>
        <template #footer><div class="ins-modal-actions"><RButton kind="secondary" @click="beginReview">记录人工审核</RButton><RButton kind="text" @click="detailOpen = false">关闭</RButton></div></template>
      </RModal>

      <RModal :open="reviewOpen" title="记录人工审核" @close="reviewOpen = false">
        <div class="risk-review-form">
          <div class="ins-alert ins-alert-info">人工审核只记录判断，不会自动执行、停用或撤销任何策略。</div>
          <RSelect v-model="reviewAction" label="审核结论" :options="REVIEW_ACTIONS" :disabled="reviewState === 'SUBMITTING'" />
          <RTextarea v-model="reviewNote" label="审核说明" :rows="4" :disabled="reviewState === 'SUBMITTING'" />
          <div v-if="reviewState === 'RECORDED'" class="ins-alert ins-alert-success" role="status">审核记录已保存；策略状态保持不变。</div>
          <div v-if="reviewState === 'FAILED'" class="ins-alert ins-alert-danger" role="alert">审核记录未保存，请保留当前内容后重试。</div>
        </div>
        <template #footer><div class="ins-modal-actions"><RButton kind="primary" :disabled="reviewState === 'SUBMITTING'" @click="submitReview">{{ reviewState === 'SUBMITTING' ? '正在记录' : '提交审核记录' }}</RButton><RButton kind="text" @click="reviewOpen = false">关闭</RButton></div></template>
      </RModal>

      <RModal :open="disableOpen" title="确认停用已有的限流策略？" @close="disableOpen = false">
        <div class="risk-disable-confirm">
          <div class="ins-alert ins-alert-warning">停用将解除该项 LIMIT_RATE。提交只代表请求已受理，仍需使用同一 commandId 核验执行结果。</div>
          <dl><div><dt>短链接</dt><dd>{{ fixtureLink.title }}</dd></div><div><dt>现有策略</dt><dd>LIMIT_RATE · 已激活</dd></div><div><dt>操作编号</dt><dd><code>{{ commandId }}</code></dd></div></dl>
        </div>
        <template #footer><div class="ins-modal-actions"><RButton kind="danger" @click="submitDisable">确认停用</RButton><RButton kind="text" @click="disableOpen = false">保留策略</RButton></div></template>
      </RModal>
    </section>
  `,
});
