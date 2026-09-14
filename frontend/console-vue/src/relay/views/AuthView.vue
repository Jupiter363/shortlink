<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as api from '../api/product.js'
import { passwordError, validUsername, errorMessage, isAborted } from '../domain/product-model.js'
import './product.css'

const props = defineProps({ register: Boolean })
const relay = inject('relay')
const { state, go, notify } = relay
const form = reactive({
  username: props.register ? '' : state.session.username || '',
  password: '',
  realName: '',
  phone: '',
  mail: '',
  remember: !!state.session.remember
})
const errors = reactive({})
const busy = ref(false),
  step = ref('FORM'),
  availability = ref(''),
  registered = ref(false)
const gate = ref('PENDING'),
  gateMessage = ref(''),
  nextRetryAt = ref(''),
  loginError = ref(''),
  checking = ref(false)
const headline = computed(() =>
  props.register ? '把每一次连接，\n变成新的可能。' : '每个好主意，\n都值得被抵达。'
)
let generation = 0,
  pollTimer = 0,
  pollStarted = 0
let controller = null,
  nameController = null
const clearErrors = () => Object.keys(errors).forEach((key) => delete errors[key])
const destination = () => {
  const query = new URL(state.route || '/login', window.location.origin).searchParams.get(
    'redirect'
  )
  const route = state.redirect || query || '/home/space'
  return /^\/home\//.test(route) ? route : '/home/space'
}

async function checkUsername() {
  const username = form.username.trim()
  if (!validUsername(username)) {
    availability.value = '用户名为 3–64 位字母、数字、下划线或连字符。'
    return
  }
  nameController?.abort()
  nameController = new AbortController()
  availability.value = '正在检查用户名…'
  try {
    const exists = await api.hasUsername(username, { signal: nameController.signal })
    if (username === form.username.trim())
      availability.value = exists ? '这个用户名已被使用。' : '这个用户名可以使用'
  } catch (error) {
    if (!isAborted(error) && username === form.username.trim())
      availability.value = errorMessage(error, '暂时无法检查用户名，请稍后重试。')
  }
}

function validate() {
  clearErrors()
  if (!validUsername(form.username.trim()))
    errors.username = '用户名为 3–64 位字母、数字、下划线或连字符。'
  if (passwordError(form.password)) errors.password = passwordError(form.password)
  if (props.register && !registered.value) {
    if (!form.realName.trim() || form.realName.length > 64)
      errors.realName = '请填写姓名，最多 64 个字符。'
    if (!/^[+\d ()-]{5,32}$/.test(form.phone)) errors.phone = '请输入有效手机号码。'
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.mail) || form.mail.length > 254)
      errors.mail = '请输入有效邮箱。'
  }
  return !Object.keys(errors).length
}

async function checkInitialization(own, retry = false) {
  if (own !== generation || !state.session.loggedIn) return
  checking.value = true
  gateMessage.value = ''
  try {
    const result = retry
      ? await api.retryInitialization({ signal: controller.signal })
      : await api.getInitialization({ signal: controller.signal })
    if (own !== generation) return
    if (!result || !['PENDING', 'FAILED', 'READY'].includes(result.state))
      throw new Error('账户初始化状态暂时无法确认。')
    state.initialization = {
      state: result.state,
      groupId: result.groupId || null,
      nextRetryAt: result.nextRetryAt || null,
      reason: result.reason || ''
    }
    gate.value = result.state
    nextRetryAt.value = result.nextRetryAt
      ? new Date(result.nextRetryAt).toLocaleString('zh-CN', {
          timeZone: 'Asia/Shanghai',
          hour12: false
        })
      : ''
    if (result.state === 'READY') {
      if (!result.groupId) throw new Error('默认分组信息尚未就绪，请重新检查。')
      state.defaultGroupId = result.groupId
      await relay.refreshGroups()
      if (own !== generation) return
      if (!state.groups.some((group) => group.id === result.groupId))
        throw new Error('默认分组尚未可见，请重新检查。')
      if (!state.groups.some((group) => group.id === state.groupId)) state.groupId = result.groupId
      go(destination())
    } else if (result.state === 'FAILED') {
      gateMessage.value = result.reason || '默认分组尚未完成初始化，账户信息已保存。'
    } else if (Date.now() - pollStarted < 120000) {
      pollTimer = setTimeout(() => checkInitialization(own), 2000)
    } else gateMessage.value = '初始化仍在进行。可以继续检查，或稍后返回。'
  } catch (error) {
    if (own !== generation || isAborted(error)) return
    if (error.status === 401) {
      relay.clearSession()
      step.value = 'FORM'
      loginError.value = '登录已失效，请重新登录。'
      return
    }
    // Transport failure is UNKNOWN, never a fabricated successful or failed initialization result.
    state.initialization = { ...state.initialization, state: 'UNKNOWN' }
    gate.value = 'UNKNOWN'
    gateMessage.value = errorMessage(error, '暂时无法查询账户初始化状态，请重试。')
  } finally {
    if (own === generation) checking.value = false
  }
}

function initialize(retry = false) {
  clearTimeout(pollTimer)
  controller?.abort()
  controller = new AbortController()
  const own = ++generation
  pollStarted = Date.now()
  step.value = 'GATE'
  gate.value = 'PENDING'
  state.initialization = { ...state.initialization, state: 'PENDING' }
  checkInitialization(own, retry)
}

async function submit() {
  if (busy.value || !validate()) return
  busy.value = true
  loginError.value = ''
  controller?.abort()
  controller = new AbortController()
  const own = ++generation,
    credentials = { username: form.username.trim(), password: form.password }
  try {
    if (props.register && !registered.value) {
      await api.registerUser(
        {
          ...credentials,
          realName: form.realName.trim(),
          phone: form.phone.trim(),
          mail: form.mail.trim()
        },
        { signal: controller.signal }
      )
      if (own !== generation) return
      registered.value = true
      notify('注册成功，正在登录账户。', 'success')
    }
    const session = await api.login(credentials, { signal: controller.signal })
    if (own !== generation) return
    if (!session?.token) throw new Error('登录响应缺少有效凭据，请重新登录。')
    relay.setSession({
      username: credentials.username,
      token: session.token,
      remember: form.remember
    })
    form.password = ''
    busy.value = false
    initialize()
  } catch (error) {
    if (own === generation && !isAborted(error))
      loginError.value = errorMessage(error, '登录服务暂时不可用，请稍后重试。')
  } finally {
    if (own === generation) busy.value = false
  }
}

function retry() {
  if (!checking.value) initialize(gate.value === 'FAILED')
}
function switchAuth() {
  state.redirect = destination()
  go(props.register ? '/login' : '/register')
}
function leaveGate() {
  generation++
  clearTimeout(pollTimer)
  controller?.abort()
  relay.clearSession()
  go('/login')
  step.value = 'FORM'
  form.password = ''
}
onMounted(() => {
  if (state.session.loggedIn) initialize()
})
onBeforeUnmount(() => {
  generation++
  clearTimeout(pollTimer)
  controller?.abort()
  nameController?.abort()
  form.password = ''
})
</script>

<template>
  <main class="auth-page">
    <section class="auth-story" aria-label="Jupiter Relay 品牌介绍">
      <RBrand variant="color" :show-text="true" :size="42" />
      <div class="auth-story-copy">
        <span class="auth-eyebrow">连接 · 抵达 · 观测 · 守护</span>
        <h1>{{ headline }}</h1>
        <p>短一点的链接，更清楚的洞察。<br />在一个工作台，打理你的每一次分享。</p>
      </div>
      <div class="auth-illustration">
        <div class="auth-orbit auth-orbit-one"></div>
        <div class="auth-orbit auth-orbit-two"></div>
        <span class="auth-note auth-note-left"><RIcon name="link" />连接好想法</span
        ><RRobot role="base" :expression="register ? 'success' : 'neutral'" :size="256" /><span
          class="auth-note auth-note-right"
          ><RIcon name="arrow-up-right" />抵达新可能</span
        >
      </div>
      <div class="auth-story-footer">
        <span class="auth-signal"></span>JUPITER RELAY<span>木星中继站</span>
      </div>
    </section>
    <section class="auth-panel">
      <div v-if="step === 'FORM'" class="auth-form-wrap">
        <div class="auth-form-heading">
          <span class="auth-section-label">{{ register ? '创建你的工作台' : '欢迎回来' }}</span>
          <h2>{{ register ? '开启新的连接' : '登录木星中继站' }}</h2>
          <p>
            {{
              registered
                ? '账户已注册成功，继续登录即可。'
                : register
                  ? '填写账户信息，准备开始管理短链接。'
                  : '进入工作台，继续上一次的好想法。'
            }}
          </p>
        </div>
        <form class="auth-form" @submit.prevent="submit">
          <RField
            v-model="form.username"
            label="用户名"
            :error="errors.username"
            :disabled="busy || registered"
            autocomplete="username"
          />
          <div v-if="register && !registered" class="auth-availability">
            <span>{{ availability || '3–64 位字母、数字、下划线或连字符' }}</span
            ><button type="button" :disabled="busy" @click="checkUsername">检查可用性</button>
          </div>
          <RField
            v-model="form.password"
            label="密码"
            type="password"
            hint="8–15 位字符"
            :error="errors.password"
            :disabled="busy"
            :autocomplete="register ? 'new-password' : 'current-password'"
          />
          <template v-if="register && !registered"
            ><RField
              v-model="form.realName"
              label="姓名"
              :error="errors.realName"
              :disabled="busy" /><RField
              v-model="form.phone"
              label="手机号码"
              type="tel"
              :error="errors.phone"
              :disabled="busy" /><RField
              v-model="form.mail"
              label="邮箱"
              type="email"
              :error="errors.mail"
              :disabled="busy"
          /></template>
          <RCheckbox v-model="form.remember" label="记住登录" :disabled="busy" />
          <p class="auth-session-note">仅保存登录凭据和用户名，不保存密码。</p>
          <div v-if="loginError" class="product-alert product-alert-danger" role="alert">
            <RIcon name="warning" />{{ loginError }}
          </div>
          <RButton type="submit" kind="primary" :loading="busy"
            >{{ registered ? '继续登录' : register ? '注册并登录' : '登录工作台'
            }}<RIcon name="arrow-right"
          /></RButton>
          <div class="auth-switch">
            {{ register ? '已经有账户？' : '还没有账户？'
            }}<button type="button" :disabled="busy" @click="switchAuth">
              {{ register ? '直接登录' : '创建账户' }}
            </button>
          </div>
        </form>
      </div>
      <div v-else class="auth-gate" aria-live="polite">
        <RRobot
          role="base"
          :expression="
            ['FAILED', 'UNKNOWN'].includes(gate)
              ? 'recovery'
              : gate === 'READY'
                ? 'success'
                : 'waiting'
          "
          :size="160"
        />
        <RBadge
          :tone="
            gate === 'FAILED'
              ? 'danger'
              : gate === 'READY'
                ? 'success'
                : gate === 'UNKNOWN'
                  ? 'unknown'
                  : 'info'
          "
          >{{
            gate === 'FAILED'
              ? '初始化未完成'
              : gate === 'READY'
                ? '工作台已就绪'
                : gate === 'UNKNOWN'
                  ? '状态待确认'
                  : '正在初始化'
          }}</RBadge
        >
        <h2>
          {{
            ['FAILED', 'UNKNOWN'].includes(gate)
              ? '再给连接一点时间'
              : gate === 'READY'
                ? '准备好了，出发吧'
                : '正在准备你的工作台'
          }}
        </h2>
        <p>
          {{
            gateMessage ||
            (gate === 'READY'
              ? '账户与默认分组已准备完成。'
              : '已成功登录，正在准备默认分组并检查初始化状态。')
          }}
        </p>
        <ol class="auth-gate-steps">
          <li class="is-complete"><RIcon name="check" />已完成账户登录</li>
          <li :class="{ 'is-complete': gate === 'READY' }">
            <RIcon :name="gate === 'READY' ? 'check' : 'clock'" />准备默认分组
          </li>
          <li :class="{ 'is-complete': gate === 'READY' }">
            <RIcon :name="gate === 'READY' ? 'check' : 'arrow-right'" />进入工作台
          </li>
        </ol>
        <p v-if="gate === 'FAILED' && nextRetryAt" class="auth-retry-time">
          下次计划重试：{{ nextRetryAt }}
        </p>
        <RButton
          v-if="gate !== 'READY' && (gate !== 'PENDING' || gateMessage)"
          kind="primary"
          :loading="checking"
          @click="retry"
          >{{ gate === 'FAILED' ? '立即重试' : '继续检查' }}</RButton
        >
        <RButton v-if="gate !== 'READY'" kind="text" @click="leaveGate">返回登录页</RButton
        ><small>可以安全离开，离开不会删除已创建的账户。</small>
      </div>
      <footer class="auth-panel-footer">
        JUPITER RELAY<span>让每一次连接，都有好结果。</span>
      </footer>
    </section>
  </main>
</template>
