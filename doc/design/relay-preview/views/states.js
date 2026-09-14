import { computed, inject, onBeforeUnmount, ref, watch } from '/vendor/vue.js';

const STATES = Object.freeze([
  { value: 'first-load', label: '首屏加载 · Skeleton', title: '正在准备工作区', description: '首次打开页面，先保留内容的排版位置。', icon: 'clock', tone: 'info', action: '完成加载样例' },
  { value: 'local-load', label: '局部刷新 · Skeleton', title: '正在刷新当前区域', description: '保留当前范围与已展示的信息，只更新需要刷新的区域。', icon: 'clock', tone: 'info', action: '完成局部刷新' },
  { value: 'offline', label: '离线 · 网络连接中断', title: '当前处于离线状态', description: '尚未提交的输入会保留。请检查网络连接后再试。', icon: 'browser', tone: 'warning', action: '重新连接样例' },
  { value: 'network-error', label: '网络请求失败', title: '暂时无法连接服务', description: '这次请求未完成，当前输入和选择范围仍然保留。', icon: 'warning', tone: 'warning', action: '重试请求样例' },
  { value: 'unauthorized', label: '401 · 登录状态失效', title: '请重新登录', description: '当前操作需要有效的登录状态。登录完成后，返回触发本次会话过期的页面。', icon: 'shield-check', tone: 'warning', action: '重新登录并保留来源' },
  { value: 'forbidden', label: '403 · 无权访问', title: '当前内容不可访问', description: '当前账户没有这项内容的访问权限。请选择已有权限的内容，或稍后核对访问范围。', icon: 'lock', tone: 'danger', action: '重试反馈样例' },
  { value: 'rate-limited', label: '429 · 稍后重试', title: '请求较多，请稍后重试', description: '当前结果和输入保持不变，等待一会儿再继续。', icon: 'clock', tone: 'warning', action: '重试请求样例' },
  { value: 'timeout', label: '504 · 请求超时', title: '等待响应超时', description: '尚未收到及时响应，原输入仍然保留。请确认原操作结果后，再决定是否重试。', icon: 'clock', tone: 'warning', action: '演示重新请求' },
  { value: 'success', label: '成功 · 完成反馈', title: '已完成本地样例', description: '新的内容已经准备好，可以继续当前任务。', icon: 'check', tone: 'success', action: '再次查看刷新样例' },
]);

export const StateGallery = {
  name: 'StateGallery',
  setup() {
    const relay = inject('relay', null);
    const selected = ref('first-load');
    const pending = ref(false);
    const cooldown = ref(0);
    const announcement = ref('');
    const current = computed(() => STATES.find(state => state.value === selected.value) || STATES[0]);
    const loading = computed(() => ['first-load', 'local-load'].includes(selected.value));
    const canExpire = computed(() => typeof relay?.expireSession === 'function');
    const sourceRoute = computed(() => relay?.sessionSource?.() || relay?.state?.route || '/design/components');
    const buttonLabel = computed(() => cooldown.value ? `${cooldown.value} 秒后可重试` : current.value.action);
    let retryTimer = null;
    let cooldownTimer = null;
    let generation = 0;

    function clearTimers() {
      generation += 1;
      if (retryTimer !== null) clearTimeout(retryTimer);
      if (cooldownTimer !== null) clearInterval(cooldownTimer);
      retryTimer = null;
      cooldownTimer = null;
    }

    watch(selected, value => {
      clearTimers();
      pending.value = false;
      cooldown.value = 0;
      announcement.value = '';
      // 本地状态样例：等待时间只演示 429 控件，不代表真实接口限额。
      if (value === 'rate-limited') {
        cooldown.value = 3;
        cooldownTimer = setInterval(() => {
          cooldown.value = Math.max(0, cooldown.value - 1);
          if (!cooldown.value) { clearInterval(cooldownTimer); cooldownTimer = null; }
        }, 1000);
      }
    }, { flush: 'sync' });

    function retryLocal() {
      if (pending.value || cooldown.value) return;
      // 本地状态样例：仅切换组件反馈，不调用后端或变更真实权限。
      if (selected.value === 'success') { selected.value = 'local-load'; return; }
      pending.value = true;
      announcement.value = '正在演示重试反馈。';
      const token = generation;
      retryTimer = setTimeout(() => {
        if (token !== generation) return;
        retryTimer = null;
        pending.value = false;
        selected.value = 'success';
        announcement.value = '本地状态样例已完成，后台数据和权限没有变化。';
      }, 600);
    }

    function act() {
      if (selected.value === 'unauthorized') {
        // 本地状态样例：通过根组件的会话 helper 清理演示登录态并保留来源。
        if (canExpire.value) relay.expireSession();
        return;
      }
      retryLocal();
    }

    function reset() {
      // 本地状态样例：重置当前展示，不改变应用业务数据。
      clearTimers();
      pending.value = false;
      cooldown.value = 0;
      selected.value = 'first-load';
      announcement.value = '已回到首屏加载样例。';
    }

    onBeforeUnmount(clearTimers);
    return { states: STATES, selected, pending, cooldown, current, loading, canExpire, sourceRoute, buttonLabel, announcement, act, reset };
  },
  template: `
    <section class="sg-gallery" aria-labelledby="state-gallery-title">
      <header class="sg-heading">
        <div class="sg-heading-copy"><span class="sg-eyebrow">本地状态样例</span><h2 id="state-gallery-title">每一种状态，都有下一步</h2><p>切换状态并操作按钮，查看加载、异常与恢复反馈。</p></div>
        <div class="sg-picker"><RSelect v-model="selected" label="选择展示状态" :options="states" :disabled="pending" /></div>
      </header>

      <div class="sg-stage" :class="['sg-stage--' + selected, {'sg-stage--loading':loading}]" :aria-busy="loading || pending ? 'true' : undefined">
        <template v-if="loading">
          <div class="sg-preview-heading"><div><span class="sg-eyebrow">{{selected === 'first-load' ? '首次进入' : '当前范围保持不变'}}</span><h3>短链接工作区</h3></div><RBadge tone="info">{{selected === 'first-load' ? '正在载入' : '局部刷新中'}}</RBadge></div>
          <template v-if="selected === 'first-load'">
            <div class="sg-skeleton-summary" aria-hidden="true"><div v-for="index in 3" :key="index"><span class="sg-skeleton sg-skeleton-label"></span><span class="sg-skeleton sg-skeleton-value"></span></div></div>
            <div class="sg-skeleton-list" aria-hidden="true"><div v-for="index in 3" :key="index" class="sg-skeleton-row"><span class="sg-skeleton sg-skeleton-avatar"></span><span class="sg-skeleton-row-copy"><span class="sg-skeleton sg-skeleton-title"></span><span class="sg-skeleton sg-skeleton-url"></span></span><span class="sg-skeleton sg-skeleton-action"></span></div></div>
          </template>
          <template v-else>
            <div class="sg-retained-summary"><span>秋日投放</span><strong>4 条短链接</strong><span>当前分组范围已保留</span></div>
            <div class="sg-retained-row"><RIcon name="globe" /><div><strong>秋日新品 · 主会场</strong><span>s.example/7b3N9kX2q</span></div><RBadge tone="success">正常</RBadge></div>
            <div class="sg-refresh-placeholder" aria-hidden="true"><span class="sg-skeleton sg-skeleton-title"></span><span class="sg-skeleton sg-skeleton-url"></span></div>
          </template>
          <p class="sg-loading-caption" role="status"><RIcon name="clock" :size="20" />{{current.title}}。{{current.description}}</p>
        </template>

        <div v-else class="sg-feedback" :class="'sg-feedback--' + current.tone">
          <div class="sg-feedback-icon"><RIcon :name="current.icon" :size="30" /></div>
          <div class="sg-feedback-copy"><RBadge :tone="current.tone">{{current.label}}</RBadge><h3>{{current.title}}</h3><p>{{current.description}}</p>
            <p v-if="selected === 'unauthorized'" class="sg-context">来源页面 <code>{{sourceRoute}}</code></p>
            <p v-if="selected === 'forbidden'" class="sg-context">此处重试只演示组件反馈，不会授予任何权限。</p>
            <p v-if="selected === 'rate-limited'" class="sg-context">本地样例等待 3 秒后开放重试；实际页面遵循服务端的重试提示。</p>
            <p v-if="selected === 'success'" class="sg-context">本地演示已完成 · 当前输入与范围保持不变</p>
          </div>
        </div>

        <div class="sg-actions"><RButton :loading="pending" :disabled="cooldown > 0 || (selected === 'unauthorized' && !canExpire)" loading-text="正在重试…" @click="act">{{buttonLabel}}</RButton><RButton kind="text" :disabled="pending" @click="reset">重置样例</RButton></div>
        <p v-if="selected === 'unauthorized' && !canExpire" class="sg-context sg-session-unavailable">当前预览的登录回跳入口尚未连接。</p>
      </div>

      <p class="sg-footnote">除 401 演示会重新进入登录流程外，这里的操作仅改变本地组件状态，不发送请求或修改业务数据。</p>
      <span class="sg-screen-reader" role="status" aria-live="polite" aria-atomic="true">{{announcement}}</span>
    </section>
  `,
};

export default StateGallery;
