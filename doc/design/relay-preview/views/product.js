import { computed, inject, onBeforeUnmount, onMounted, reactive, ref, watch } from '/vendor/vue.js';

const accounts = new Map([['Jupiter', { password: '12345678', disabled: false }]]);
const TERMINAL = new Set(['SUCCEEDED', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED']);
const JOB_LABELS = { VALIDATING: '校验中', READY: '等待执行', RUNNING: '创建中', SUCCEEDED: '全部完成', PARTIAL_SUCCESS: '部分完成', FAILED: '创建失败', CANCELLED: '已取消' };
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const required = value => String(value ?? '').trim();
const passwordError = value => value.length < 8 || value.length > 15 ? '密码需要 8–15 位字符。' : '';
const createdTime = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date());
const statusForExpiry = expires => !expires ? 'normal' : new Date(expires).getTime() <= Date.now() ? 'expired' : new Date(expires).getTime() - Date.now() <= 3 * 86_400_000 ? 'expiring' : 'normal';
const isUrl = value => {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password && !/\s/.test(value); }
  catch { return false; }
};
const safeRoute = value => typeof value === 'string' && value.startsWith('/home/') ? value : '/home/space';
const copyText = async (text, notify) => {
  try { await navigator.clipboard.writeText(text); notify('已复制到剪贴板', 'success'); }
  catch { notify('浏览器未允许复制，请选择短链后手动复制。', 'warning'); }
};
const download = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename;
  document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1500);
};
const clearPersistedSession = () => { localStorage.removeItem('relay.mock.session'); sessionStorage.removeItem('relay.mock.session'); };
const persistSession = session => {
  clearPersistedSession();
  const storage = session.remember ? localStorage : sessionStorage;
  storage.setItem('relay.mock.session', JSON.stringify({ token: session.token, username: session.username }));
};

export const AuthView = {
  props: { register: Boolean },
  setup(props) {
    const relay = inject('relay');
    const { state, go, notify } = relay;
    const form = reactive({ username: props.register ? '' : 'Jupiter', password: '', realName: '', phone: '', mail: '', remember: true });
    const errors = reactive({});
    const busy = ref(false), step = ref('FORM'), availability = ref(''), gate = ref('PENDING');
    const gateMessage = ref(''), nextRetryAt = ref(''), initialFailed = ref(false), loginError = ref(''), loginScenario = ref('normal');
    let generation = 0;
    const headline = computed(() => props.register ? '把每一次连接，\n变成新的可能。' : '每个好主意，\n都值得被抵达。');
    const destination = () => {
      const query = new URL(state.route || '/login', 'https://preview.local').searchParams.get('redirect');
      return safeRoute(state.redirect || query);
    };
    const checkUsername = async () => {
      const name = required(form.username);
      if (!/^[A-Za-z0-9_]{3,24}$/.test(name)) { availability.value = '用户名使用 3–24 位字母、数字或下划线。'; return false; }
      availability.value = '正在检查用户名…'; await wait(220);
      availability.value = accounts.has(name) ? '这个用户名已被使用，换一个试试。' : '这个用户名可以使用';
      return !accounts.has(name);
    };
    const validate = () => {
      Object.keys(errors).forEach(key => delete errors[key]);
      if (!required(form.username)) errors.username = '请输入用户名。';
      if (passwordError(form.password)) errors.password = passwordError(form.password);
      if (props.register) {
        if (!/^[A-Za-z0-9_]{3,24}$/.test(required(form.username))) errors.username = '用户名使用 3–24 位字母、数字或下划线。';
        if (accounts.has(required(form.username))) errors.username = '用户名已存在。';
        if (!required(form.realName)) errors.realName = '请输入姓名。';
        if (!/^1\d{10}$/.test(form.phone)) errors.phone = '请输入 11 位手机号码。';
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.mail)) errors.mail = '请输入有效邮箱。';
      }
      return Object.keys(errors).length === 0;
    };
    const initialize = async fail => {
      const own = ++generation;
      step.value = 'GATE'; gate.value = 'PENDING'; gateMessage.value = ''; nextRetryAt.value = '';
      state.initialization = { state: 'PENDING', groupId: null, nextRetryAt: null, error: null };
      await wait(1150);
      if (own !== generation) return;
      if (fail) {
        gate.value = 'FAILED'; gateMessage.value = '默认分组暂时未能完成初始化，账户信息已安全保存。';
        nextRetryAt.value = new Date(Date.now() + 30_000).toLocaleTimeString('zh-CN', { hour12: false });
        state.initialization = { state: 'FAILED', groupId: null, nextRetryAt: Date.now() + 30_000, error: gateMessage.value };
      } else {
        const defaultId = state.defaultGroupId || state.groups[0]?.id || 'default';
        if (!state.groups.some(group => group.id === defaultId)) state.groups.unshift({ id: defaultId, name: '默认分组' });
        state.defaultGroupId = defaultId;
        state.initialization = { state: 'READY', groupId: defaultId, nextRetryAt: null, error: null };
        gate.value = 'READY';
        await wait(650);
        if (own === generation) go(destination());
      }
    };
    const submit = async (failInitialization = false) => {
      loginError.value = '';
      if (busy.value || !validate()) return;
      busy.value = true;
      const own = ++generation;
      try {
        await wait(500);
        if (own !== generation) return;
        const name = required(form.username);
        if (loginScenario.value !== 'normal') {
          loginError.value = ({ conflict: '检测到其他位置的登录状态，请确认当前账户后重新登录。', status: '账户状态已变化，请重新确认后再试。', service: '登录服务暂时不可用，请稍后重试。' })[loginScenario.value];
          loginScenario.value = 'normal'; return;
        }
        if (props.register) {
          if (accounts.has(name)) { errors.username = '用户名已存在。'; return; }
          accounts.set(name, { password: form.password, disabled: false, profile: { username: name, realName: form.realName, phone: form.phone, mail: form.mail } });
          notify('注册成功，正在使用刚提交的凭据登录。', 'success');
          await wait(450);
          if (own !== generation) return;
        }
        const account = accounts.get(name);
        if (!account || account.password !== form.password) { loginError.value = '用户名或密码不正确，请重新输入。'; return; }
        if (account.disabled) { loginError.value = '此账户已停用，无法通过自助操作恢复。'; return; }
        // A registration result is not a session. Only this successful login creates the mock token.
        Object.assign(state.session, { loggedIn: true, username: name, remember: form.remember, token: `relay-demo-${Date.now().toString(36)}` });
        state.profile = { ...(account.profile || state.profile || {}), username: name };
        persistSession(state.session);
        form.password = '';
        initialFailed.value = failInitialization;
        await initialize(failInitialization);
      } finally { busy.value = false; }
    };
    const retry = () => { initialFailed.value = false; return initialize(false); };
    const switchAuth = () => { generation++; step.value = 'FORM'; loginError.value = ''; go(`${props.register ? '/login' : '/register'}?redirect=${encodeURIComponent(destination())}`); };
    const leaveGate = () => { generation++; step.value = 'FORM'; gate.value = 'PENDING'; state.session.loggedIn = false; clearPersistedSession(); form.password = ''; go('/login'); };
    watch(() => props.register, () => { form.password = ''; availability.value = ''; Object.keys(errors).forEach(key => delete errors[key]); if (props.register) form.username = ''; });
    onMounted(() => {
      if (state.session.loggedIn && state.initialization?.state !== 'READY') initialize(false);
    });
    onBeforeUnmount(() => { generation++; });
    return { state, form, errors, busy, step, availability, gate, gateMessage, nextRetryAt, headline, loginError, loginScenario, checkUsername, submit, retry, switchAuth, leaveGate };
  },
  template: `
    <main class="auth-page">
      <section class="auth-story" aria-label="Jupiter Relay 品牌介绍">
        <RBrand variant="color" :show-text="true" :size="42" />
        <div class="auth-story-copy"><span class="auth-eyebrow">连接 · 抵达 · 观测 · 守护</span><h1>{{ headline }}</h1><p>短一点的链接，更清楚的洞察。<br>在一个工作台，打理你的每一次分享。</p></div>
        <div class="auth-illustration"><div class="auth-orbit auth-orbit-one"></div><div class="auth-orbit auth-orbit-two"></div><span class="auth-note auth-note-left"><RIcon name="link" /> 连接好想法</span><RRobot role="base" :expression="register ? 'success' : 'neutral'" :size="256" /><span class="auth-note auth-note-right"><RIcon name="arrow-up-right" /> 抵达新可能</span></div>
        <div class="auth-story-footer"><span class="auth-signal"></span> JUPITER RELAY <span>木星中继站</span></div>
      </section>
      <section class="auth-panel">
        <div v-if="step === 'FORM'" class="auth-form-wrap">
          <div class="auth-form-heading"><span class="auth-section-label">{{ register ? '创建你的工作台' : '欢迎回来' }}</span><h2>{{ register ? '开启新的连接' : '登录木星中继站' }}</h2><p>{{ register ? '填写账户信息，准备开始管理短链接。' : '进入工作台，继续上一次的好想法。' }}</p></div>
          <form class="auth-form" @submit.prevent="submit(false)">
            <RField v-model="form.username" label="用户名" :error="errors.username" autocomplete="username" />
            <div v-if="register" class="auth-availability"><span :class="{ 'auth-available': availability.includes('可以使用') }">{{ availability || '用户名使用 3–24 位字母、数字或下划线。' }}</span><button type="button" @click="checkUsername">检查可用性</button></div>
            <div class="auth-password"><RField v-model="form.password" label="密码" type="password" hint="8–15 位字符" :error="errors.password" :autocomplete="register ? 'new-password' : 'current-password'" /></div>
            <template v-if="register"><RField v-model="form.realName" label="姓名" :error="errors.realName" /><RField v-model="form.phone" label="手机号码" type="tel" :error="errors.phone" /><RField v-model="form.mail" label="邮箱" type="email" :error="errors.mail" /></template>
            <RCheckbox v-model="form.remember" label="记住登录" />
            <p class="auth-session-note">仅保存登录凭据和用户名，不保存密码。</p>
            <div v-if="loginError" class="product-alert product-alert-danger" role="alert"><RIcon name="warning" />{{ loginError }}</div>
            <RButton kind="primary" :loading="busy" :disabled="busy" @click="submit(false)">{{ register ? '注册并登录' : '登录工作台' }}<RIcon name="arrow-right" /></RButton>
            <div class="auth-switch">{{ register ? '已经有账户？' : '还没有账户？' }}<button type="button" @click="switchAuth">{{ register ? '直接登录' : '创建账户' }}</button></div>
          </form>
          <details class="auth-demo"><summary>本地演示说明</summary><p>示例用户名 Jupiter，密码 12345678。注册与密码修改仅保存在本页内存，不连接真实账户。</p><RSelect v-model="loginScenario" label="下一次登录反馈" :options="[{value:'normal',label:'正常登录'},{value:'conflict',label:'重复或异地登录'},{value:'status',label:'账户状态变化'},{value:'service',label:'服务暂时不可用'}]" /><RButton kind="text" :disabled="busy" @click="submit(true)">登录后演示初始化失败</RButton></details>
        </div>
        <div v-else class="auth-gate" aria-live="polite">
          <RRobot role="base" :expression="gate === 'FAILED' ? 'recovery' : gate === 'READY' ? 'success' : 'waiting'" :size="160" />
          <RBadge :tone="gate === 'FAILED' ? 'danger' : gate === 'READY' ? 'success' : 'info'">{{ gate === 'FAILED' ? '初始化未完成' : gate === 'READY' ? '工作台已就绪' : '正在初始化' }}</RBadge>
          <h2>{{ gate === 'FAILED' ? '再给连接一点时间' : gate === 'READY' ? '准备好了，出发吧' : '正在准备你的工作台' }}</h2>
          <p>{{ gate === 'FAILED' ? gateMessage : gate === 'READY' ? '账户与默认分组已准备完成，即将进入原先的页面。' : '已成功登录。我们正在准备默认分组，并自动检查初始化状态。' }}</p>
          <ol class="auth-gate-steps"><li class="is-complete"><RIcon name="check" />已完成账户登录</li><li :class="{ 'is-complete': gate === 'READY' }"><RIcon :name="gate === 'READY' ? 'check' : 'clock'" />{{ gate === 'FAILED' ? '默认分组等待重试' : '准备默认分组' }}</li><li :class="{ 'is-complete': gate === 'READY' }"><RIcon :name="gate === 'READY' ? 'check' : 'arrow-right'" />进入工作台</li></ol>
          <p v-if="gate === 'FAILED'" class="auth-retry-time">下次可自动重试时间：{{ nextRetryAt }}</p>
          <RButton v-if="gate === 'FAILED'" kind="primary" @click="retry">立即重试</RButton>
          <RButton v-if="gate !== 'READY'" kind="text" @click="leaveGate">暂时返回登录页</RButton>
          <small>可以安全离开，离开不会删除已创建的账户。</small>
        </div>
        <footer class="auth-panel-footer">JUPITER RELAY <span>让每一次连接，都有好结果。</span></footer>
      </section>
    </main>`
};

export const AccountView = {
  setup() {
    const { state, go, notify } = inject('relay');
    const form = reactive({ realName: '', phone: '', mail: '', currentPassword: '', password: '' });
    const editing = ref(false), saving = ref(false), showDisable = ref(false), disablePassword = ref(''), disableConfirm = ref(false), disabling = ref(false);
    const errors = reactive({}), disableError = ref(''), demoError = ref('');
    const profile = computed(() => state.profile || { username: state.session.username, realName: '王小明', phone: '13800005678', mail: 'jupiter@example.com' });
    const startEdit = () => { Object.assign(form, profile.value, { currentPassword: '', password: '' }); Object.keys(errors).forEach(key => delete errors[key]); editing.value = true; };
    const cancelEdit = () => { form.currentPassword = ''; form.password = ''; editing.value = false; };
    const logout = () => { clearPersistedSession(); state.session.loggedIn = false; state.session.token = ''; state.redirect = '/home/account'; notify('已退出当前登录', 'success'); go('/login'); };
    const save = async () => {
      if (saving.value) return;
      Object.keys(errors).forEach(key => delete errors[key]);
      if (!required(form.realName)) errors.realName = '请输入姓名。';
      if (!/^1\d{10}$/.test(form.phone)) errors.phone = '请输入有效手机号码。';
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.mail)) errors.mail = '请输入有效邮箱。';
      if (form.password && passwordError(form.password)) errors.password = passwordError(form.password);
      const account = accounts.get(state.session.username) || { password: '12345678', disabled: false };
      if (form.password && !form.currentPassword) errors.currentPassword = '修改密码前需要输入当前密码。';
      else if (form.password && form.currentPassword !== account.password) errors.currentPassword = '当前密码不正确。';
      if (Object.keys(errors).length) return;
      saving.value = true; await wait(650);
      if (demoError.value) { errors.form = demoError.value === 'conflict' ? '账户状态已变化，请刷新后重新确认。' : '服务暂时不可用，信息尚未保存。'; demoError.value = ''; saving.value = false; return; }
      state.profile = { username: state.session.username, realName: form.realName, phone: form.phone, mail: form.mail };
      if (form.password) account.password = form.password;
      account.profile = { ...state.profile }; accounts.set(state.session.username, account);
      saving.value = false; cancelEdit(); notify('账户信息已保存', 'success');
    };
    const disable = async () => {
      disableError.value = '';
      const account = accounts.get(state.session.username) || { password: '12345678', disabled: false };
      if (!disablePassword.value) { disableError.value = '请输入当前密码。'; return; }
      if (disablePassword.value !== account.password) { disableError.value = '当前密码不正确。'; return; }
      if (!disableConfirm.value) { disableError.value = '请确认停用后的影响。'; return; }
      if (disabling.value) return;
      disabling.value = true; await wait(600);
      account.disabled = true; accounts.set(state.session.username, account);
      disablePassword.value = ''; disabling.value = false; showDisable.value = false;
      clearPersistedSession(); state.session.loggedIn = false; state.session.token = '';
      notify('账户已停用，当前登录已退出。', 'success'); go('/login');
    };
    return { state, profile, form, editing, saving, errors, showDisable, disablePassword, disableConfirm, disabling, disableError, demoError, startEdit, cancelEdit, save, logout, disable };
  },
  template: `
    <section class="account-page">
      <header class="account-page-heading"><div><span class="product-eyebrow">个人设置</span><h1>账户中心</h1><p>管理你的个人信息和当前登录。</p></div><RButton kind="secondary" @click="logout"><RIcon name="sign-out" />退出登录</RButton></header>
      <div class="account-layout"><aside class="account-identity"><div class="account-avatar">{{ (state.session.username || 'J').slice(0, 1).toUpperCase() }}</div><h2>{{ state.session.username }}</h2><p>{{ profile.realName || '个人账户' }}</p><RBadge tone="success">当前已登录</RBadge><div class="account-identity-note"><RIcon name="shield-check" /><p>保持信息准确，<br>安心管理每一次连接。</p></div><RRobot role="base" expression="neutral" :size="126" /></aside>
        <div class="account-sections"><section class="account-section"><header><div><h2>基本资料</h2><p>用户名用于登录，创建后不可修改。</p></div><RButton v-if="!editing" kind="secondary" @click="startEdit"><RIcon name="pencil" />编辑资料</RButton></header>
          <form v-if="editing" class="account-edit" @submit.prevent="save"><RField :model-value="state.session.username" label="用户名" readonly /><RField v-model="form.realName" label="姓名" :error="errors.realName" /><RField v-model="form.phone" label="手机号码" type="tel" :error="errors.phone" /><RField v-model="form.mail" label="邮箱" type="email" :error="errors.mail" /><div class="account-password-heading"><h3>修改密码</h3><p>不修改密码时，保留下方字段为空。</p></div><RField v-model="form.currentPassword" label="当前密码" type="password" :error="errors.currentPassword" autocomplete="current-password" /><RField v-model="form.password" label="新密码" type="password" hint="8–15 位字符" :error="errors.password" autocomplete="new-password" /><div v-if="errors.form" class="product-alert product-alert-danger account-full" role="alert">{{ errors.form }}</div><div class="account-form-actions"><RButton kind="secondary" :disabled="saving" @click="cancelEdit">取消</RButton><RButton kind="primary" :loading="saving" @click="save">保存修改</RButton></div><details class="account-demo"><summary>查看异常反馈演示</summary><RButton kind="text" @click="demoError = 'service'">下次保存演示服务失败</RButton><RButton kind="text" @click="demoError = 'conflict'">下次保存演示状态冲突</RButton></details></form>
          <dl v-else class="account-details"><div><dt>用户名</dt><dd>{{ state.session.username }}<RIcon name="lock" /></dd></div><div><dt>姓名</dt><dd>{{ profile.realName || '未填写' }}</dd></div><div><dt>手机号码</dt><dd>{{ profile.phone || '未填写' }}</dd></div><div><dt>邮箱</dt><dd>{{ profile.mail || '未填写' }}</dd></div></dl>
        </section><section class="account-section account-security"><header><div><h2>账户安全</h2><p>停用前，请仔细确认对账户的影响。</p></div></header><div class="account-danger-row"><div><h3>停用当前账户</h3><p>停用后将退出登录，且无法自助恢复账户。</p></div><RButton kind="danger" @click="showDisable = true; disablePassword = ''; disableConfirm = false; disableError = ''">停用账户</RButton></div></section></div>
      </div>
      <RModal :open="showDisable" title="停用当前账户" @close="!disabling && (showDisable = false)"><div class="product-dialog-fields"><div class="product-confirm-icon product-confirm-danger"><RIcon name="warning" /></div><p class="product-confirm-copy">停用后，你将无法使用此账户登录。<strong>此操作无法通过自助流程恢复。</strong></p><RField v-model="disablePassword" label="当前密码" type="password" :error="disableError" /><RCheckbox v-model="disableConfirm" label="我已了解停用后的影响，并确认停用当前账户" /></div><template #footer><RButton kind="secondary" :disabled="disabling" @click="showDisable = false">保留账户</RButton><RButton kind="danger" :loading="disabling" :disabled="!disableConfirm" @click="disable">确认停用并退出</RButton></template></RModal>
    </section>`
};

export const ProductDialogs = {
  setup() {
    const relay = inject('relay');
    const { state, fixtures, close, open, notify } = relay;
    const aliases = { createLink: 'create', editLink: 'edit', batchCreate: 'batch' };
    const type = computed(() => aliases[state.modal.type] || state.modal.type);
    const supported = new Set(['groupCreate', 'groupRename', 'groupDelete', 'create', 'edit', 'recycle', 'restore', 'permanentDelete', 'qr', 'batch', 'batchJob']);
    const visible = computed(() => supported.has(type.value));
    const payload = computed(() => state.modal.payload?.link || state.modal.payload?.group || state.modal.payload || {});
    const target = computed(() => state.links.find(link => link.id === payload.value.id) || payload.value);
    const group = computed(() => state.groups.find(item => item.id === (payload.value.id || state.groupId)) || payload.value);
    const form = reactive({ name: '', url: '', title: '', groupId: '', validity: 'forever', expires: '', confirmed: false, typedCode: '' });
    const busy = ref(false), error = ref(''), expectedVersion = ref(0), conflict = ref(false), titleState = ref('IDLE'), created = ref(null), qrSrc = ref('');
    const batch = reactive({ urls: '', titles: '', groupId: '', outcome: 'success', bodyOverride: false });
    const batchError = ref(''), resultAfter = ref(0), exportBusy = ref(false), export429 = ref(false);
    const groupOptions = computed(() => state.groups.map(item => ({ value: item.id, label: item.name })));
    const domain = computed(() => fixtures.domain || 's.example');
    const shortUrl = link => `https://${domain.value}/${link.code}`;
    const job = computed(() => state.batchJob || null);
    const activeJob = computed(() => job.value && !TERMINAL.has(job.value.state));
    const isBatch = computed(() => ['batch', 'batchJob'].includes(type.value));
    const modalTitle = computed(() => ({ groupCreate: '新建分组', groupRename: '重命名分组', groupDelete: '删除分组', create: created.value ? '短链接创建成功' : '创建短链接', edit: '编辑短链接', recycle: '移入回收站', restore: '恢复短链接', permanentDelete: '永久删除短链接', qr: '短链接二维码', batch: '批量创建短链接', batchJob: '批量创建结果' })[type.value]);
    const lines = value => { if (!value.trim()) return []; return value.replace(/\r/g, '').replace(/\n+$/, '').split('\n').map(line => line.trim()); };
    const batchInfo = computed(() => {
      const urls = lines(batch.urls), titles = lines(batch.titles);
      const body = { originUrls: urls, describes: titles.length ? titles : urls.map(() => ''), gid: batch.groupId, validDateType: 0 };
      const bytes = new TextEncoder().encode(JSON.stringify(body)).byteLength;
      const mismatch = titles.length > 0 && titles.length !== urls.length;
      return { urls, titles, bytes, count: urls.length, mismatch, tooLarge: bytes > 8 * 1024 * 1024, invalidCount: urls.filter(url => !isUrl(url)).length, isAsync: urls.length > 500 };
    });
    const groupBlocked = computed(() => {
      if (type.value !== 'groupDelete') return '';
      const defaultId = state.defaultGroupId || state.initialization?.groupId;
      if (group.value.id === defaultId) return '这是初始化返回的默认分组，当前界面会保护它，无法删除。';
      if (state.links.some(link => link.groupId === group.value.id)) return '这个分组仍包含短链接（含回收站中的短链接），请先处理这些内容。';
      if ((state.activeJobs || []).some(item => (item.groupId || item.gid) === group.value.id && !TERMINAL.has(item.state))) return '这个分组仍有活动创建任务，任务结束后再试。';
      return '';
    });
    const resultRows = computed(() => (job.value?.rows || []).slice(0, Math.min(resultAfter.value + 20, job.value?.rows?.length || 0)));
    let titleGeneration = 0, modalGeneration = 0, batchTimer = 0;
    const resetForm = () => {
      const link = target.value;
      Object.assign(form, { name: type.value === 'groupCreate' ? '' : group.value.name || '', url: link.url || '', title: link.title || '', groupId: link.groupId || state.groupId || state.groups[0]?.id || '', validity: link.expires && link.expires !== '永久' ? 'custom' : 'forever', expires: link.expires && link.expires !== '永久' ? String(link.expires).slice(0, 16) : '', confirmed: false, typedCode: '' });
      expectedVersion.value = Number(link.version || 1); error.value = ''; conflict.value = false; titleState.value = 'IDLE'; created.value = null; busy.value = false;
    };
    const resolveQr = async link => {
      qrSrc.value = '';
      const own = modalGeneration;
      if (link.code === '7b3N9kX2q') qrSrc.value = '/assets/qr.svg';
      else if (relay.qrDataUrl) { try { const src = await relay.qrDataUrl(shortUrl(link)); if (own === modalGeneration) qrSrc.value = src; } catch { if (own === modalGeneration) error.value = '二维码暂时生成失败，请重试。'; } }
    };
    watch(() => [state.modal.type, state.modal.payload], () => {
      modalGeneration++; titleGeneration++; resetForm();
      if (type.value === 'qr') resolveQr(target.value);
      if (type.value === 'batch' && !activeJob.value) { batch.urls = ''; batch.titles = ''; batch.groupId = state.groupId || state.groups[0]?.id || ''; batchError.value = ''; state.batchJob = null; resultAfter.value = 0; }
      else if (type.value === 'batchJob') resultAfter.value = 0;
    }, { immediate: true });
    const fetchTitle = async fail => {
      if (!isUrl(form.url)) { error.value = '先输入完整的 http:// 或 https:// 原始链接。'; return; }
      error.value = ''; titleState.value = 'FETCHING'; const own = ++titleGeneration;
      await wait(700); if (own !== titleGeneration) return;
      if (fail) { titleState.value = 'FAILED'; return; }
      const path = new URL(form.url).pathname;
      form.title = /autumn|launch/.test(path) ? '秋季新品发布会 · 主会场' : /guide|docs/.test(path) ? '产品使用指南' : `${new URL(form.url).hostname} · 分享链接`;
      titleState.value = 'READY';
    };
    const verifyVersion = () => {
      const current = state.links.find(link => link.id === payload.value.id);
      if (!current || Number(current.version || 1) !== expectedVersion.value) { conflict.value = true; error.value = '这条短链接已经被更新。请刷新最新内容，确认后再次提交。'; return false; }
      return true;
    };
    const validateLink = () => {
      if (!isUrl(form.url)) { error.value = '请输入有效的 http:// 或 https:// 原始链接。'; return false; }
      if (!required(form.title)) { error.value = '请填写描述，或先获取网页标题。'; return false; }
      if (!state.groups.some(item => item.id === form.groupId)) { error.value = '请选择可用的分组。'; return false; }
      if (form.validity === 'custom' && (!form.expires || new Date(form.expires).getTime() <= Date.now())) { error.value = '请选择未来的有效期。'; return false; }
      return true;
    };
    const createLink = (url, title, groupId, code, id) => ({ id, code, title, url, groupId, created: createdTime(), expires: form.validity === 'custom' ? form.expires : null, status: statusForExpiry(form.validity === 'custom' ? form.expires : null), pv: 0, uv: 0, uip: 0, version: 1, recycled: false });
    const submit = async () => {
      if (busy.value) return;
      error.value = '';
      if (['groupCreate', 'groupRename'].includes(type.value) && (!required(form.name) || form.name.length > 32)) { error.value = '分组名称需要 1–32 个字符。'; return; }
      if (type.value === 'groupCreate' && state.groups.length >= 20) { error.value = '最多可创建 20 个分组。'; return; }
      if (type.value === 'groupDelete' && groupBlocked.value) { error.value = groupBlocked.value; return; }
      if (['create', 'edit'].includes(type.value) && !validateLink()) return;
      if (['edit', 'recycle', 'restore', 'permanentDelete'].includes(type.value) && !verifyVersion()) return;
      if (['recycle', 'restore', 'permanentDelete', 'groupDelete'].includes(type.value) && !form.confirmed) { error.value = '请先确认本次操作。'; return; }
      if (type.value === 'permanentDelete' && form.typedCode !== target.value.code) { error.value = '输入的短码与当前短链接不一致。'; return; }
      const action = type.value, own = modalGeneration;
      busy.value = true; await wait(500);
      if (own !== modalGeneration) return;
      if (['create', 'edit'].includes(action) && !validateLink()) { busy.value = false; return; }
      if (['edit', 'recycle', 'restore', 'permanentDelete'].includes(action) && !verifyVersion()) { busy.value = false; return; }
      if (action === 'groupCreate') { if (state.groups.length >= 20) { error.value = '分组数量已达到 20 个。'; busy.value = false; return; } const id = `g-${Date.now().toString(36)}`; state.groups.push({ id, name: required(form.name) }); state.groupId = id; }
      if (action === 'groupRename') { const existing = state.groups.find(item => item.id === group.value.id); if (existing) existing.name = required(form.name); }
      if (action === 'groupDelete') { if (groupBlocked.value) { error.value = groupBlocked.value; busy.value = false; return; } state.groups = state.groups.filter(item => item.id !== group.value.id); if (state.groupId === group.value.id) state.groupId = state.defaultGroupId || state.groups[0]?.id; }
      if (action === 'create') {
        const id = `link-${Date.now().toString(36)}`;
        const link = createLink(form.url, required(form.title), form.groupId, `N${Date.now().toString(36).slice(-7).padStart(7, '0')}q`, id);
        state.links.unshift(link); created.value = link; busy.value = false; notify('短链接已创建', 'success'); return;
      }
      if (action === 'edit') { const expires = form.validity === 'custom' ? form.expires : null; Object.assign(target.value, { title: required(form.title), url: form.url, groupId: form.groupId, expires, status: statusForExpiry(expires), version: expectedVersion.value + 1 }); }
      if (action === 'recycle') Object.assign(target.value, { recycled: true, version: expectedVersion.value + 1 });
      if (action === 'restore') Object.assign(target.value, { recycled: false, version: expectedVersion.value + 1 });
      if (action === 'permanentDelete') state.links = state.links.filter(link => link.id !== target.value.id);
      busy.value = false; notify(({ groupCreate: '分组已创建', groupRename: '分组名称已更新', groupDelete: '分组已删除', edit: '短链接已更新', recycle: '已移入回收站', restore: '短链接已恢复', permanentDelete: '短链接已永久删除' })[action], 'success'); close();
    };
    const simulateConflict = () => { const current = state.links.find(link => link.id === payload.value.id); if (current) current.version = Number(current.version || 1) + 1; notify('已模拟另一处更新；提交时会执行版本核验。', 'info'); };
    const refreshConflict = () => { resetForm(); notify('已加载最新内容，请确认后重新提交。', 'info'); };
    const viewCreatedStats = () => { if (!created.value) return; state.analyticsScope = { type: 'link', id: created.value.id, code: created.value.code }; close(); relay.go('/home/analytics'); };
    const loadBatchSample = count => { batch.urls = Array.from({ length: count }, (_, index) => `https://example.com/autumn/entry-${index + 1}`).join('\n'); batch.titles = Array.from({ length: count }, (_, index) => `秋季活动 · 入口 ${index + 1}`).join('\n'); };
    const finishJob = terminal => {
      const current = state.batchJob; if (!current || TERMINAL.has(current.state)) return;
      clearTimeout(batchTimer);
      current.state = terminal;
      current.rows = current.source.map((source, index) => {
        let status = 'SUCCEEDED', errorText = '';
        if (terminal === 'CANCELLED' && index >= current.completedRows) { status = 'CANCELLED'; errorText = '任务取消，尚未创建'; }
        else if (!source.valid) { status = 'FAILED'; errorText = '原始链接格式无效'; }
        else if (terminal === 'FAILED') { status = 'FAILED'; errorText = '创建服务暂时不可用'; }
        else if (terminal === 'PARTIAL_SUCCESS' && index % 7 === 0) { status = 'FAILED'; errorText = '创建失败'; }
        const code = `B${(current.sequence + index).toString(36).padStart(8, '0').slice(-8)}`;
        return { row: index + 1, title: source.title, url: source.url, status, shortUrl: status === 'SUCCEEDED' ? `https://${domain.value}/${code}` : '', code, error: errorText };
      });
      current.succeededRows = current.rows.filter(row => row.status === 'SUCCEEDED').length;
      current.failedRows = current.rows.filter(row => row.status === 'FAILED').length;
      current.cancelledRows = current.rows.filter(row => row.status === 'CANCELLED').length;
      current.completedRows = current.succeededRows + current.failedRows;
      current.error = terminal === 'FAILED' ? '创建服务暂时不可用，本次结果已保留。' : '';
      if (terminal !== 'CANCELLED') current.state = current.succeededRows === current.totalRows ? 'SUCCEEDED' : current.succeededRows > 0 ? 'PARTIAL_SUCCESS' : 'FAILED';
      const additions = current.rows.filter(row => row.status === 'SUCCEEDED').map(row => ({ ...createLink(row.url, row.title, current.groupId, row.code, `${current.id}-${row.row}`), expires: null, status: 'normal' }));
      state.links.push(...additions);
      state.activeJobs = (state.activeJobs || []).filter(item => item.id !== current.id);
      resultAfter.value = 0;
      notify(current.state === 'CANCELLED' ? '批量任务已取消，已完成的结果会保留。' : `本次成功创建 ${current.succeededRows.toLocaleString()} 条短链接`, current.state === 'FAILED' ? 'danger' : 'success');
    };
    const runProgress = () => {
      const current = state.batchJob; if (!current || current.state !== 'RUNNING') return;
      current.completedRows = Math.min(current.totalRows, current.completedRows + Math.max(1, Math.ceil(current.totalRows / 4)));
      current.succeededRows = Math.min(current.validRows, current.completedRows);
      if (current.completedRows === current.totalRows) finishJob(current.outcome === 'failed' ? 'FAILED' : current.outcome === 'partial' || current.invalidRows ? 'PARTIAL_SUCCESS' : 'SUCCEEDED');
      else batchTimer = setTimeout(runProgress, 550);
    };
    const startBatch = () => {
      batchError.value = '';
      const info = batchInfo.value;
      if (activeJob.value) { batchError.value = '当前创建任务还未结束。'; return; }
      if (info.count < 2 || info.count > 50_000) { batchError.value = '每批需要 2–50,000 行链接。'; return; }
      if (info.mismatch) { batchError.value = '链接与描述的行数必须逐行匹配。'; return; }
      if (info.tooLarge) { batchError.value = '序列化请求体超过 8 MiB，请减少内容后再提交。'; return; }
      if (!state.groups.some(item => item.id === batch.groupId)) { batchError.value = '请选择可用的分组。'; return; }
      const id = `${info.isAsync ? 'job' : 'request'}-demo-${Date.now().toString(36)}`;
      state.batchJob = { id, requestId: info.isAsync ? null : id, kind: info.isAsync ? 'ASYNC' : 'SYNC', state: 'VALIDATING', groupId: batch.groupId, totalRows: info.count, validRows: info.count - info.invalidCount, invalidRows: info.invalidCount, succeededRows: 0, failedRows: 0, cancelledRows: 0, completedRows: 0, error: '', sequence: Date.now(), outcome: batch.outcome, source: info.urls.map((url, index) => ({ url, title: info.titles[index] || `分享链接 ${index + 1}`, valid: isUrl(url) })), rows: [] };
      state.activeJobs = [...(state.activeJobs || []), state.batchJob];
      batchTimer = setTimeout(() => {
        if (!state.batchJob || state.batchJob.state !== 'VALIDATING') return;
        if (!state.batchJob.validRows) { finishJob('FAILED'); return; }
        state.batchJob.state = 'READY';
        batchTimer = setTimeout(() => { if (!state.batchJob || state.batchJob.state !== 'READY') return; state.batchJob.state = 'RUNNING'; batchTimer = setTimeout(runProgress, 550); }, 850);
      }, 750);
    };
    const exportResults = async () => {
      if (!job.value || !TERMINAL.has(job.value.state) || exportBusy.value) return;
      exportBusy.value = true; await wait(320);
      try {
        if (export429.value) { export429.value = false; notify('导出繁忙，请稍后重试。', 'warning'); return; }
        const rows = job.value.rows.map(row => ({ 行号: row.row, 描述: row.title, 原始链接: row.url, 短链接: row.shortUrl, 结果: row.status, 错误原因: row.error }));
        if (job.value.kind === 'SYNC') {
          if (!relay.exportXlsx) { notify('XLSX 导出组件暂未就绪，结果仍完整保留。', 'warning'); return; }
          await relay.exportXlsx(rows, `shortlink-${job.value.requestId}.xlsx`);
        } else {
          const cell = value => { let text = String(value ?? ''); if (/^[=+\-@]/.test(text)) text = `'${text}`; return `"${text.replace(/"/g, '""')}"`; };
          const header = ['行号', '描述', '原始链接', '短链接', '结果', '错误原因'];
          const csv = [header.map(cell).join(','), ...rows.map(row => header.map(key => cell(row[key])).join(','))].join('\r\n');
          download(new Blob(['\uFEFF', csv], { type: 'text/csv;charset=utf-8' }), `shortlink-${job.value.id}.csv`);
        }
        notify('创建结果已导出', 'success');
      } catch { notify('导出失败，结果仍然保留，请重试。', 'danger'); }
      finally { exportBusy.value = false; }
    };
    const downloadQr = async () => {
      if (!qrSrc.value) return;
      try { const response = await fetch(qrSrc.value); if (!response.ok) throw new Error(); download(await response.blob(), `shortlink-${target.value.code}.svg`); }
      catch { notify('二维码下载失败，请重试。', 'danger'); }
    };
    onBeforeUnmount(() => { titleGeneration++; modalGeneration++; clearTimeout(batchTimer); });
    return { state, relay, type, visible, target, group, form, busy, error, conflict, titleState, created, groupOptions, groupBlocked, modalTitle, domain, shortUrl, batch, batchInfo, batchError, job, activeJob, isBatch, resultRows, resultAfter, exportBusy, export429, qrSrc, TERMINAL, JOB_LABELS, close, open, submit, fetchTitle, refreshConflict, simulateConflict, viewCreatedStats, loadBatchSample, startBatch, finishJob, exportResults, downloadQr, copy: text => copyText(text, notify), qrRetry: () => resolveQr(target.value), bytesLabel: bytes => `${(bytes / 1024 / 1024).toFixed(2)} MiB` };
  },
  template: `
    <RModal :open="visible" :title="modalTitle" :drawer="isBatch" @close="!busy && close()">
      <div v-if="['groupCreate','groupRename','groupDelete'].includes(type)" class="product-dialog-fields">
        <template v-if="type !== 'groupDelete'"><p class="product-lead">{{ type === 'groupCreate' ? '把相同用途的短链接放在一起，分享和观察都更清楚。' : '修改分组名称，不会改变其中的短链接。' }}</p><RField v-model="form.name" label="分组名称" hint="最多 32 个字符" /><p v-if="type === 'groupCreate'" class="product-helper">已创建 {{ state.groups.length }} / 20 个分组</p></template>
        <template v-else><div class="product-confirm-icon"><RIcon name="folder" /></div><p class="product-confirm-copy">删除分组 <strong>{{ group.name }}</strong>？<br>删除后无法找回这个分组。</p><div v-if="groupBlocked" class="product-alert product-alert-warning"><RIcon name="warning" />{{ groupBlocked }}</div><RCheckbox v-else v-model="form.confirmed" label="我确认删除这个空分组" /></template>
      </div>
      <div v-else-if="['create','edit'].includes(type)" class="product-dialog-fields">
        <div v-if="created" class="product-created"><RRobot role="base" expression="success" :size="132" /><h3>新的连接，准备出发</h3><p>{{ created.title }}</p><div class="product-created-url"><span>{{ shortUrl(created) }}</span><RButton kind="secondary" @click="copy(shortUrl(created))"><RIcon name="copy" />复制</RButton></div><div class="product-created-actions"><RButton kind="secondary" @click="open('qr', created)"><RIcon name="qr-code" />二维码</RButton><RButton kind="text" @click="viewCreatedStats()">查看统计<RIcon name="arrow-right" /></RButton></div></div>
        <template v-else><p class="product-lead">{{ type === 'create' ? '一个简洁的链接，让好内容更容易抵达。' : '更新目标链接与描述，原有短码保持不变。' }}</p><RField v-model="form.url" label="原始链接" type="url" placeholder="https://example.com/your-page" /><div class="product-title-label"><span>短链描述</span><RButton kind="text" :loading="titleState === 'FETCHING'" @click="fetchTitle(false)"><RIcon name="sparkle" />获取网页标题</RButton></div><RField v-model="form.title" label="描述" /><p v-if="titleState === 'FAILED'" class="product-inline-warning"><RIcon name="warning" />标题获取失败，可以直接手工填写描述。</p><p v-else-if="titleState === 'READY'" class="product-inline-success"><RIcon name="check" />已填入网页标题，可继续编辑。</p><RSelect v-model="form.groupId" :options="groupOptions" label="所属分组" /><div class="product-two-fields"><RSelect v-model="form.validity" :options="[{value:'forever',label:'永久有效'},{value:'custom',label:'自定义有效期'}]" label="有效期" /><RDateTime v-if="form.validity === 'custom'" v-model="form.expires" type="datetime-local" label="到期时间" /></div><div class="product-domain-info"><RIcon name="link" /><div><strong>{{ type === 'edit' ? shortUrl(target) : domain + ' / 创建后分配短码' }}</strong><span>短链域名由服务端分配，无需手动填写。</span></div></div><details class="product-demo"><summary>查看异常反馈演示</summary><RButton kind="text" @click="fetchTitle(true)">演示标题获取失败</RButton><RButton v-if="type === 'edit'" kind="text" @click="simulateConflict">演示版本冲突</RButton></details></template>
      </div>
      <div v-else-if="['recycle','restore','permanentDelete'].includes(type)" class="product-dialog-fields">
        <div class="product-confirm-icon" :class="{'product-confirm-danger': type === 'permanentDelete'}"><RIcon :name="type === 'restore' ? 'arrow-counter-clockwise' : 'trash'" /></div><p class="product-confirm-copy"><strong>{{ target.title }}</strong><span class="product-confirm-url">{{ shortUrl(target) }}</span></p><p class="product-lead">{{ type === 'restore' ? '恢复后，短链接将重新出现在原分组中；跳转仍按原有效期和风险策略执行。' : type === 'recycle' ? '移入回收站后，短链接将暂停跳转。之后可以在回收站恢复。' : '永久删除后，跳转将失效并停止统计，且无法恢复。请仔细确认。' }}</p><RField v-if="type === 'permanentDelete'" v-model="form.typedCode" label="输入短码以确认" :hint="target.code" /><RCheckbox v-model="form.confirmed" :label="type === 'permanentDelete' ? '我了解后果，确认永久删除' : type === 'restore' ? '确认恢复这条短链接' : '确认将这条短链接移入回收站'" /><details class="product-demo"><summary>查看版本冲突演示</summary><RButton kind="text" @click="simulateConflict">模拟另一处更新</RButton></details>
      </div>
      <div v-else-if="type === 'qr'" class="product-qr"><div class="product-qr-paper"><img v-if="qrSrc" :src="qrSrc" :alt="'短链接二维码：' + shortUrl(target)" /><div v-else class="product-qr-unavailable"><RIcon name="qr-code" /><p>这个示例的二维码待生成</p><RButton v-if="relay.qrDataUrl" kind="text" @click="qrRetry">重新生成</RButton></div></div><h3>{{ target.title }}</h3><p class="product-qr-url">{{ shortUrl(target) }}</p><p class="product-helper">扫描二维码，打开这条短链接。</p><div class="product-qr-actions"><RButton kind="secondary" @click="copy(shortUrl(target))"><RIcon name="copy" />复制短链接</RButton><RButton kind="primary" :disabled="!qrSrc" @click="downloadQr"><RIcon name="download" />下载二维码</RButton></div></div>
      <div v-else-if="isBatch" class="batch-workspace">
        <template v-if="!job"><p class="product-lead">一次放入多个目的地，统一创建、清楚核对。</p><div class="batch-contract"><span><strong>2–500 行</strong>同步返回</span><span><strong>501–50,000 行</strong>异步任务</span><span><strong>≤ 8 MiB</strong>请求体上限</span></div><RSelect v-model="batch.groupId" :options="groupOptions" label="创建到分组" /><div class="batch-editor"><RTextarea v-model="batch.urls" label="原始链接 · 每行一条" :maxlength="12000000" /><RTextarea v-model="batch.titles" label="描述 · 与链接逐行对应，可全部留空" :maxlength="12000000" /></div><div class="batch-validation"><span :class="{'is-invalid': batchInfo.count < 2 || batchInfo.count > 50000}">{{ batchInfo.count.toLocaleString() }} 行链接</span><span :class="{'is-invalid': batchInfo.tooLarge}">{{ bytesLabel(batchInfo.bytes) }} / 8 MiB</span><RBadge v-if="batchInfo.count >= 2" :tone="batchInfo.isAsync ? 'info' : 'success'">{{ batchInfo.isAsync ? '异步创建' : '同步创建' }}</RBadge></div><div v-if="batchInfo.mismatch" class="product-alert product-alert-danger" role="alert">链接与描述的行数不一致，请按行对应后再提交。</div><div v-if="batchInfo.tooLarge" class="product-alert product-alert-danger" role="alert">序列化请求体超过 8 MiB。请减少内容，当前无法提交。</div><p v-if="batchInfo.invalidCount" class="product-inline-warning">有 {{ batchInfo.invalidCount }} 行链接格式待校验，提交后会保留逐行结果。</p><p class="product-helper">直接粘贴链接和描述。当前不提供浏览器文件上传替代流程。</p><details class="batch-demo"><summary>填入样例，体验批量流程</summary><div class="batch-demo-actions"><RButton kind="secondary" @click="loadBatchSample(3)">填入 3 行</RButton><RButton kind="secondary" @click="loadBatchSample(501)">填入 501 行</RButton></div><RSelect v-model="batch.outcome" label="本地结果演示" :options="[{value:'success',label:'全部成功'},{value:'partial',label:'部分成功'},{value:'failed',label:'任务失败'}]" /></details><div v-if="batchError" class="product-alert product-alert-danger" role="alert">{{ batchError }}</div></template>
        <template v-else><div class="batch-job-heading"><div><RBadge :tone="job.state === 'FAILED' ? 'danger' : job.state === 'CANCELLED' ? 'unknown' : TERMINAL.has(job.state) ? 'success' : 'info'">{{ JOB_LABELS[job.state] }}</RBadge><h3>{{ job.kind === 'SYNC' ? '同步创建结果' : '异步创建任务' }}</h3></div><RRobot v-if="TERMINAL.has(job.state)" role="base" :expression="job.state === 'FAILED' ? 'recovery' : 'success'" :size="74" /></div><p class="batch-job-id">{{ job.kind === 'SYNC' ? 'requestId' : 'jobId' }} <code>{{ job.id }}</code></p><ol class="batch-stages"><li :class="{'is-current':job.state === 'VALIDATING','is-complete':job.state !== 'VALIDATING'}">校验中</li><li :class="{'is-current':job.state === 'READY','is-complete':['RUNNING',...TERMINAL].includes(job.state)}">等待执行</li><li :class="{'is-current':job.state === 'RUNNING','is-complete':TERMINAL.has(job.state)}">创建中</li><li :class="{'is-current':TERMINAL.has(job.state)}">{{ TERMINAL.has(job.state) ? JOB_LABELS[job.state] : '结果' }}</li></ol><div class="batch-progress" role="progressbar" :aria-valuenow="job.completedRows" :aria-valuemax="job.totalRows" aria-valuemin="0"><span :style="{width: (job.completedRows / job.totalRows * 100) + '%'}"></span></div><dl class="batch-metrics"><div><dt>总行数</dt><dd>{{ job.totalRows.toLocaleString() }}</dd></div><div><dt>有效 / 无效</dt><dd>{{ job.state === 'VALIDATING' ? '—' : job.validRows + ' / ' + job.invalidRows }}</dd></div><div><dt>成功</dt><dd>{{ job.state === 'VALIDATING' ? '—' : job.succeededRows.toLocaleString() }}</dd></div><div><dt>失败</dt><dd>{{ job.state === 'VALIDATING' ? '—' : job.failedRows.toLocaleString() }}</dd></div></dl><p v-if="job.state === 'VALIDATING'" class="product-helper">正在校验格式和逐行对应关系，尚未开始创建。</p><p v-else-if="job.state === 'READY'" class="product-helper">校验已完成，任务正在等待执行。</p><p v-else-if="job.state === 'RUNNING'" class="product-helper">正在创建短链接。可以关闭此面板，任务会继续。</p><div v-if="job.error" class="product-alert product-alert-danger">{{ job.error }}</div><div v-if="job.state === 'CANCELLED'" class="product-alert product-alert-warning">任务已取消，已成功创建的 {{ job.succeededRows }} 条短链接会保留。未创建 {{ job.cancelledRows }} 条。</div><template v-if="TERMINAL.has(job.state)"><div class="batch-result-heading"><h4>逐行创建结果</h4><span>{{ resultRows.length }} / {{ job.rows.length }}</span></div><div class="batch-results"><table><thead><tr><th>行号</th><th>链接与描述</th><th>结果</th></tr></thead><tbody><tr v-for="row in resultRows" :key="row.row"><td>{{ row.row }}</td><td><strong>{{ row.title }}</strong><span>{{ row.url }}</span><button v-if="row.shortUrl" type="button" @click="copy(row.shortUrl)">{{ row.shortUrl }} <RIcon name="copy" /></button><span v-else-if="row.error" class="batch-row-error">{{ row.error }}</span></td><td><RBadge :tone="row.status === 'SUCCEEDED' ? 'success' : row.status === 'CANCELLED' ? 'unknown' : 'danger'">{{ row.status === 'SUCCEEDED' ? '成功' : row.status === 'CANCELLED' ? '未创建' : '失败' }}</RBadge></td></tr></tbody></table></div><RButton v-if="resultRows.length < job.rows.length" kind="secondary" @click="resultAfter = resultRows.length">继续加载 20 条结果</RButton><p class="product-helper">{{ job.kind === 'SYNC' ? '同步结果按本次 requestId 导出 XLSX。' : '任务已进入终态，可导出完整 CSV。' }}</p><details class="batch-demo"><summary>查看导出繁忙反馈</summary><RCheckbox v-model="export429" label="下次导出演示 429：导出繁忙" /></details></template></template>
      </div>
      <div v-if="error" class="product-alert product-alert-danger" role="alert">{{ error }}</div><RButton v-if="conflict" kind="secondary" @click="refreshConflict">刷新最新内容</RButton>
      <template #footer><template v-if="isBatch"><RButton kind="secondary" @click="close">{{ activeJob ? '关闭，保留任务' : '关闭' }}</RButton><RButton v-if="!job" kind="primary" :disabled="batchInfo.count < 2 || batchInfo.count > 50000 || batchInfo.mismatch || batchInfo.tooLarge" @click="startBatch">{{ batchInfo.isAsync ? '提交创建任务' : '批量创建' }}</RButton><RButton v-else-if="activeJob" kind="danger" @click="finishJob('CANCELLED')">取消任务</RButton><RButton v-else kind="primary" :loading="exportBusy" :disabled="job.kind === 'SYNC' && !relay.exportXlsx" @click="exportResults"><RIcon name="download" />{{ job.kind === 'SYNC' ? '导出 XLSX' : '导出 CSV' }}</RButton></template><template v-else-if="type === 'qr' || created"><RButton kind="primary" @click="close">完成</RButton></template><template v-else><RButton kind="secondary" :disabled="busy" @click="close">取消</RButton><RButton :kind="['permanentDelete','groupDelete','recycle'].includes(type) ? 'danger' : 'primary'" :loading="busy" :disabled="!!groupBlocked || conflict" @click="submit">{{ ({groupCreate:'创建分组',groupRename:'保存名称',groupDelete:'删除分组',create:'创建短链接',edit:'保存修改',recycle:'移入回收站',restore:'确认恢复',permanentDelete:'永久删除'})[type] }}</RButton></template></template>
    </RModal>`
};
