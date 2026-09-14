<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as api from '../api/product.js'
import PageHeading from '../components/PageHeading.vue'
import { profileBody, errorMessage, isAborted } from '../domain/product-model.js'
import './product.css'

const relay = inject('relay')
const { state, go, notify } = relay
const form = reactive({ realName: '', phone: '', mail: '', currentPassword: '', password: '' })
const editing = ref(false),
  saving = ref(false),
  loading = ref(false),
  loadError = ref(''),
  errors = reactive({})
const showDisable = ref(false),
  disablePassword = ref(''),
  disableConfirm = ref(false),
  disabling = ref(false),
  disableError = ref(''),
  loggingOut = ref(false)
const profile = computed(() => state.profile || {})
const controller = new AbortController()
let alive = true
async function loadProfile() {
  loading.value = true
  loadError.value = ''
  try {
    const value = await api.getProfile(state.session.username, { signal: controller.signal })
    if (alive) state.profile = value
  } catch (error) {
    if (!isAborted(error) && alive) loadError.value = errorMessage(error, '账户资料暂时无法加载。')
  } finally {
    if (alive) loading.value = false
  }
}
function startEdit() {
  Object.assign(form, {
    realName: profile.value.realName || '',
    mail: profile.value.mail || '',
    phone: '',
    currentPassword: '',
    password: ''
  })
  Object.keys(errors).forEach((key) => delete errors[key])
  editing.value = true
}
function cancelEdit() {
  form.password = ''
  form.currentPassword = ''
  editing.value = false
}
async function save() {
  if (saving.value) return
  Object.keys(errors).forEach((key) => delete errors[key])
  let body
  try {
    body = profileBody(state.session.username, form)
  } catch (error) {
    errors.form = error.message
    return
  }
  saving.value = true
  try {
    await api.updateProfile(body, { signal: controller.signal })
    if (!alive) return
    const changedPassword = !!body.password
    cancelEdit()
    if (changedPassword) {
      relay.clearSession()
      notify('密码已修改，请使用新密码重新登录。', 'success')
      go('/login')
    } else {
      notify('账户信息已保存', 'success')
      await loadProfile()
    }
  } catch (error) {
    if (!isAborted(error) && alive) {
      if (/当前密码/.test(error.message)) errors.currentPassword = '当前密码不正确。'
      else errors.form = errorMessage(error, '保存未完成，请确认后重试。')
    }
  } finally {
    if (alive) saving.value = false
  }
}
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await api.logout({ signal: controller.signal })
  } catch (error) {
    if (!isAborted(error) && error.status !== 401)
      notify('服务端退出暂未确认，已清除本机登录。', 'warning')
  } finally {
    if (alive) {
      relay.clearSession()
      go('/login')
      loggingOut.value = false
    }
  }
}
async function disable() {
  disableError.value = ''
  if (!disablePassword.value) {
    disableError.value = '请输入当前密码。'
    return
  }
  if (!disableConfirm.value || disabling.value) return
  disabling.value = true
  try {
    await api.updateProfile(
      { username: state.session.username, currentPassword: disablePassword.value, disabled: true },
      { signal: controller.signal }
    )
    if (!alive) return
    disablePassword.value = ''
    showDisable.value = false
    relay.clearSession()
    notify('账户已停用，当前登录已退出。', 'success')
    go('/login')
  } catch (error) {
    if (!isAborted(error) && alive)
      disableError.value = errorMessage(error, '停用未完成，请确认账户状态后再试。')
  } finally {
    if (alive) disabling.value = false
  }
}
onMounted(loadProfile)
onBeforeUnmount(() => {
  alive = false
  controller.abort()
  form.password = ''
  form.currentPassword = ''
  disablePassword.value = ''
})
</script>

<template>
  <section class="account-page operation-page" aria-labelledby="account-heading">
    <PageHeading class="account-page-heading">
      <div>
        <h1 id="account-heading">账户中心</h1>
        <p>管理你的个人信息和当前登录。</p>
      </div>
      <RButton kind="secondary" :loading="loggingOut" @click="logout"
        ><RIcon name="sign-out" />退出登录</RButton
      >
    </PageHeading>
    <div
      class="account-content operation-body"
      role="region"
      aria-label="账户资料与安全设置"
      tabindex="0"
    >
      <div v-if="loadError" class="product-alert product-alert-danger" role="alert">
        {{ loadError }}<RButton kind="text" @click="loadProfile">重新加载</RButton>
      </div>
      <div v-if="loading" class="product-loading" role="status">正在加载账户资料…</div>
      <div v-else class="account-layout">
        <aside class="account-identity" aria-label="当前账户">
          <div class="account-avatar">
            {{ (state.session.username || '').slice(0, 1).toUpperCase() }}
          </div>
          <div class="account-identity-copy">
            <h2>{{ state.session.username }}</h2>
            <p>{{ profile.realName || '个人账户' }}</p>
          </div>
          <RBadge tone="success">当前已登录</RBadge>
        </aside>
        <div class="account-sections">
          <section class="account-section">
            <header>
              <div>
                <h2>基本资料</h2>
                <p>用户名用于登录，创建后不可修改。</p>
              </div>
              <RButton v-if="!editing" kind="secondary" :disabled="!!loadError" @click="startEdit"
                ><RIcon name="pencil" />编辑资料</RButton
              >
            </header>
            <form v-if="editing" class="account-edit" @submit.prevent="save">
              <RField :model-value="state.session.username" label="用户名" readonly /><RField
                v-model="form.realName"
                label="姓名"
                :disabled="saving"
              /><RField
                v-model="form.phone"
                label="新的手机号码"
                type="tel"
                :hint="'当前：' + (profile.phone || '未填写') + '；留空不修改'"
                :disabled="saving"
              /><RField v-model="form.mail" label="邮箱" type="email" :disabled="saving" />
              <div class="account-password-heading">
                <h3>修改密码</h3>
                <p>不修改时保留为空。修改成功后需要重新登录。</p>
              </div>
              <RField
                v-model="form.currentPassword"
                label="当前密码"
                type="password"
                :error="errors.currentPassword"
                :disabled="saving"
                autocomplete="current-password"
              /><RField
                v-model="form.password"
                label="新密码"
                type="password"
                hint="8–15 位字符"
                :disabled="saving"
                autocomplete="new-password"
              />
              <div
                v-if="errors.form"
                class="product-alert product-alert-danger account-full"
                role="alert"
              >
                {{ errors.form }}
              </div>
              <div class="account-form-actions">
                <RButton kind="secondary" :disabled="saving" @click="cancelEdit">取消</RButton
                ><RButton type="submit" kind="primary" :loading="saving">保存修改</RButton>
              </div>
            </form>
            <dl v-else class="account-details">
              <div>
                <dt>用户名</dt>
                <dd>{{ state.session.username }}<RIcon name="lock" /></dd>
              </div>
              <div>
                <dt>姓名</dt>
                <dd>{{ profile.realName || '未填写' }}</dd>
              </div>
              <div>
                <dt>手机号码</dt>
                <dd>{{ profile.phone || '未填写' }}</dd>
              </div>
              <div>
                <dt>邮箱</dt>
                <dd>{{ profile.mail || '未填写' }}</dd>
              </div>
            </dl>
          </section>
          <details class="account-section account-security">
            <summary class="account-security-summary">
              <RIcon name="shield" :size="24" />
              <div>
                <h2>账户安全</h2>
                <p>停用账户与安全确认</p>
              </div>
              <span class="account-security-action">
                <span class="account-security-open-label">管理</span>
                <span class="account-security-close-label">收起</span>
                <span class="account-security-chevron" aria-hidden="true" />
              </span>
            </summary>
            <div class="account-danger-row">
              <div>
                <h3>停用当前账户</h3>
                <p>停用后将退出登录，且无法自助恢复账户。</p>
              </div>
              <RButton
                kind="danger"
                @click="
                  () => {
                    showDisable = true
                    disablePassword = ''
                    disableConfirm = false
                    disableError = ''
                  }
                "
                >停用账户</RButton
              >
            </div>
          </details>
        </div>
      </div>
    </div>
    <RModal :open="showDisable" title="停用当前账户" @close="!disabling && (showDisable = false)"
      ><div class="product-dialog-fields">
        <div class="product-confirm-icon product-confirm-danger"><RIcon name="warning" /></div>
        <p class="product-confirm-copy">
          停用后，你将无法使用此账户登录。<strong>此操作无法通过自助流程恢复。</strong>
        </p>
        <RField
          v-model="disablePassword"
          label="当前密码"
          type="password"
          :error="disableError"
          :disabled="disabling"
        /><RCheckbox
          v-model="disableConfirm"
          label="我已了解停用后的影响，并确认停用当前账户"
          :disabled="disabling"
        />
      </div>
      <template #footer
        ><RButton kind="secondary" :disabled="disabling" @click="showDisable = false"
          >保留账户</RButton
        ><RButton kind="danger" :loading="disabling" :disabled="!disableConfirm" @click="disable"
          >确认停用并退出</RButton
        ></template
      ></RModal
    >
  </section>
</template>

<style scoped>
.account-page {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0;
  min-width: 0;
}
.account-content {
  padding: 4px 6px 10px 2px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.account-content:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: -2px;
  border-radius: 12px;
}
.account-layout {
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}
.account-sections {
  grid-template-columns: minmax(0, 1.7fr) minmax(240px, 1fr);
  gap: 16px;
  align-items: start;
}
.account-layout > *,
.account-section > header > div,
.account-details > div {
  min-width: 0;
}
.account-section {
  padding: 20px;
}
.account-section > header {
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
}
.account-section h2 {
  font-size: 19px;
}
.account-section header p {
  font-size: 13px;
}
.account-details {
  column-gap: 20px;
}
.account-details > div {
  padding: 14px 0;
}
.account-details dt {
  margin-bottom: 5px;
}
.account-details dd {
  font-size: 14px;
}
.account-edit {
  gap: 16px;
}
.account-form-actions {
  flex-wrap: wrap;
  padding: 8px 0 4px;
}
.account-danger-row {
  flex-wrap: wrap;
  margin-top: 18px;
  padding-top: 18px;
  gap: 16px;
}
.account-danger-row > button {
  width: 100%;
}
.account-identity {
  flex-direction: row;
  flex-wrap: nowrap;
  gap: 16px;
  padding: 16px 20px;
  min-height: 84px;
  border-color: #e5c365;
  box-shadow: 0 3px 0 #ead89b;
  text-align: left;
}
.account-avatar {
  flex: none;
  width: 48px;
  height: 48px;
  margin: 0;
  border-radius: 14px;
  font-size: 24px;
  line-height: 1;
  box-shadow: 2px 2px 0 var(--text);
}
.account-identity-copy {
  min-width: 0;
  flex: 1;
}
.account-identity h2 {
  margin: 0;
  font-size: 20px;
  line-height: 1.4;
}
.account-identity-copy p {
  margin: 3px 0 0;
  font-size: 13px;
}
.account-identity > :deep(.r-badge) {
  flex: none;
}
.account-security-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  list-style: none;
}
.account-security-summary::-webkit-details-marker {
  display: none;
}
.account-security-summary > div {
  flex: 1;
  min-width: 0;
}
.account-security-summary h2 {
  margin: 0 0 3px;
}
.account-security-summary p {
  color: var(--muted);
  margin: 0;
  font-size: 13px;
}
.account-security-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--blue);
  font-size: 13px;
  font-weight: 650;
  flex: none;
}
.account-security-close-label,
.account-security[open] .account-security-open-label {
  display: none;
}
.account-security[open] .account-security-close-label {
  display: inline;
}
.account-security-chevron {
  width: 8px;
  height: 8px;
  margin: -4px 2px 0 3px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: rotate(45deg);
}
.account-security[open] .account-security-chevron {
  margin-top: 4px;
  transform: rotate(225deg);
}
.account-security-summary:focus-visible {
  outline: 2px solid var(--blue);
  outline-offset: 6px;
  border-radius: 4px;
}
@media (min-width: 901px) and (min-height: 701px) {
  .account-content {
    display: flex;
    flex-direction: column;
  }
  .account-content > * {
    flex: none;
  }
  .account-layout,
  .account-sections {
    gap: clamp(18px, 2.5dvh, 24px);
  }
  .account-identity {
    min-height: 96px;
    padding: 20px 24px;
  }
  .account-identity h2 {
    font-size: 24px;
  }
  .account-section {
    padding: clamp(20px, 3dvh, 28px);
  }
  .account-section h2 {
    font-size: 21px;
  }
  .account-details > div {
    padding-block: 18px;
  }
}
@media (max-width: 900px) {
  .account-sections {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 700px) {
  .account-layout,
  .account-sections {
    gap: 16px;
  }
  .account-identity {
    gap: 12px;
    padding: 14px;
    flex-wrap: wrap;
  }
  .account-avatar {
    width: 40px;
    height: 40px;
    font-size: 22px;
  }
  .account-identity > :deep(.r-badge) {
    margin-left: auto;
  }
  .account-section {
    padding: 18px 16px;
  }
  .account-edit {
    grid-template-columns: minmax(0, 1fr);
  }
  .account-details {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    column-gap: 14px;
  }
  .account-section > header > button {
    width: 100%;
  }
}
@media (max-width: 420px) {
  .account-details {
    grid-template-columns: minmax(0, 1fr);
  }
  .account-details > div {
    padding-block: 12px;
  }
}
</style>
