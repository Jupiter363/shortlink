<template>
  <div class="login-page">
    <h1 class="title">SaaS 短 链 接 平 台(马丁)</h1>
    <div class="login-box">
      <!-- 登录 -->
      <div class="logon" :class="{ hidden: !isLogin }">
        <h2>用户登录</h2>
        <el-form ref="loginFormRef1" :model="loginForm" label-width="50px" :rules="loginFormRule">
          <div class="form-container1">
            <el-form-item prop="username">
              <el-input v-model="loginForm.username" placeholder="请输入用户名" maxlength="32" clearable>
                <template v-slot:prepend> 用户名 </template>
              </el-input>
            </el-form-item>

            <el-form-item prop="password">
              <el-input v-model="loginForm.password" type="password" clearable placeholder="请输入密码" show-password
                style="margin-top: 20px">
                <template v-slot:prepend> 密<span class="second-font">码</span> </template>
              </el-input>
            </el-form-item>
          </div>
          <div class="btn-gourp">
            <div>
              <el-checkbox class="remeber-password" v-model="checked"
                style="color: #a0a0a0; margin: 0">记住密码</el-checkbox>
            </div>
            <div>
              <el-button :loading="loading" @keyup.enter="login" type="primary" plain
                @click="login(loginFormRef1)">登录</el-button>
            </div>
          </div>
        </el-form>
      </div>
      <!-- 注册 -->
      <div class="register" :class="{ hidden: isLogin }">
        <h2>用户注册</h2>
        <el-form ref="loginFormRef2" :model="addForm" label-width="50px" class="form-container" width="width"
          :rules="addFormRule">
          <el-form-item prop="username">
            <el-input v-model="addForm.username" placeholder="请输入用户名" maxlength="11" show-word-limit clearable>
              <template v-slot:prepend> 用户名 </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="mail">
            <el-input v-model="addForm.mail" placeholder="请输入邮箱" show-word-limit clearable>
              <template v-slot:prepend> 邮<span class="second-font">箱</span> </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="phone">
            <el-input v-model="addForm.phone" placeholder="请输入手机号" show-word-limit clearable>
              <template v-slot:prepend> 手机号 </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="realName">
            <el-input v-model="addForm.realName" placeholder="请输入姓名" show-word-limit clearable>
              <template v-slot:prepend> 姓<span class="second-font">名</span> </template>
            </el-input>
          </el-form-item>

          <el-form-item prop="password">
            <el-input v-model="addForm.password" type="password" clearable placeholder="请输入密码" show-password>
              <template v-slot:prepend> 密<span class="second-font">码</span> </template>
            </el-input>
          </el-form-item>
          <!-- 验证码 -->
          <!-- <el-form-item prop="vertify_code">
            <el-input
              v-model="loginForm.vertify_code"
              placeholder="验证码"
              prefix-icon="el-icon-key"
              clearable
            >
              <template v-slot:append>
                <div class="login-code" @click="refreshCode" title="看不清？点击切换">
                  <vertify-code :identifyCode="loginIdentifyCode"></vertify-code>
                </div>
              </template>
            </el-input>
          </el-form-item> -->
          <div class="btn-gourp">
            <div></div>
            <div>
              <el-button :loading="loading" @keyup.enter="login" type="primary" plain
                @click="addUser(loginFormRef2)">注册</el-button>
            </div>
          </div>
        </el-form>
      </div>
      <!-- 左右移动的切换按钮 -->
      <div class="move" :class="{ 'move-left': !isLogin }">
        <span style="font-size: 18px; margin-bottom: 25px; color: rgb(225, 238, 250)">{{
          !isLogin ? '已有账号？' : '还没有账号？'
        }}</span>
        <span style="font-size: 16px; color: rgb(225, 238, 250)">{{
          !isLogin ? '欢迎登录账号！' : '欢迎注册账号！'
        }}</span>
        <el-button style="width: 100px; margin-top: 30px" @click="changeLogin">{{
          !isLogin ? '去登录' : '去注册'
        }}</el-button>
      </div>
    </div>
    <div ref="vantaRef" class="vanta" aria-hidden="true"></div>
  </div>
  <el-dialog v-model="isWC" title="人机验证" width="40%">
    <div class="verification-flex">
      <span>扫码下方二维码，关注后回复：<strong><span style="color:blue;">link</span></strong>，获取拿个offer-SaaS短链接系统人机验证码</span>
      <img class="img" src="@/assets/png/公众号二维码.png" alt="">
      <el-form class="form" :model="verification" :rules="verificationRule" ref="verificationRef">
        <el-form-item prop="code" label="验证码">
          <el-input v-model="verification.code" />
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <span class="dialog-footer">
        <el-button @click="isWC = false">取消</el-button>
        <el-button type="primary" @click="verificationLogin(verificationRef)">
          确认
        </el-button>
      </span>
    </template>
  </el-dialog>
  <!-- </template> -->
</template>

<script setup>
import { setToken, setUsername, getUsername } from '@/core/auth.js'
import { ref, reactive, getCurrentInstance, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { responseErrorMessage } from '@/utils/requestId.js'
import * as THREE from 'three'
import WAVES from 'vanta/src/vanta.waves'
const { proxy } = getCurrentInstance()
const API = proxy.$API
const loginFormRef1 = ref()
const loginFormRef2 = ref()
const router = useRouter()
const loginForm = reactive({
  username: '',
  password: '',
})
const addForm = reactive({
  username: '',
  password: '',
  realName: '',
  phone: '',
  mail: ''
})

const addFormRule = reactive({
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '请输入正确的手机号',
      trigger: 'blur'
    },
    { min: 11, max: 11, message: '手机号必须是11位', trigger: 'blur' }
  ],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 15, message: '密码长度请在八位以上', trigger: 'blur' }
  ],
  mail: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    {
      pattern: /^([a-zA-Z]|[0-9])(\w|-)+@[a-zA-Z0-9]+\.([a-zA-Z]{2,4})$/,
      message: '请输入正确的邮箱号',
      trigger: 'blur'
    }
  ],
  realName: [
    { required: true, message: '请输入姓名', trigger: 'blur' },
  ]
})
const loginFormRule = reactive({
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 15, message: '密码长度请在八位以上', trigger: 'blur' }
  ],
})
const persistSession = (username, token) => {
  if (!token) return
  setToken(token)
  setUsername(username)
  if (checked.value) {
    localStorage.setItem('token', token)
    localStorage.setItem('username', username)
  } else {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  }
}

const loginDestination = () => {
  const redirect = router.currentRoute.value.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/home'
}

// 注册
const addUser = (formEl) => {
  if (!formEl) return
  formEl.validate(async (valid) => {
    if (!valid) return false
    loading.value = true
    try {
      const usernameResponse = await API.user.hasUsername({ username: addForm.username })
      if (usernameResponse.data.data === true) {
        ElMessage.warning('用户名已存在！')
        return
      }
      const registerResponse = await API.user.addUser(addForm)
      if (registerResponse.data.success === false) {
        ElMessage.warning(registerResponse.data.message)
        return
      }
      const loginResponse = await API.user.login({
        username: addForm.username,
        password: addForm.password
      })
      if (String(loginResponse?.data?.code) !== '0') {
        ElMessage.error(loginResponse?.data?.message || '注册成功，请重新登录')
        return
      }
      persistSession(addForm.username, loginResponse?.data?.data?.token)
      ElMessage.success('注册登录成功！')
      await router.replace(loginDestination())
    } catch (error) {
      ElMessage.error(responseErrorMessage(error, '注册失败，请稍后重试'))
    } finally {
      loading.value = false
    }
  })
}
// 公众号验证码
const isWC = ref(false)
const verificationRef = ref()
const verification = reactive({
  code: ''
})
const verificationRule = reactive({
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
})
const verificationLogin = (formEl) => {
  if (!formEl) return
  formEl.validate(async (valid) => {
    if (!valid) return false
    const tempPassword = loginForm.password
    loginForm.password = verification.code
    loading.value = true
    try {
      const res1 = await API.user.login(loginForm)
      if (String(res1.data.code) === '0') {
        const token = res1?.data?.data?.token
        persistSession(loginForm.username, token)
        ElMessage.success('登录成功！')
        await router.replace(loginDestination())
      } else if (res1.data.message === '用户已登录') {
        // 如果已经登录了，判断一下浏览器保存的登录信息是不是再次登录的信息，如果是就正常登录
        const cookiesUsername = getUsername()
        if (cookiesUsername === loginForm.username) {
          ElMessage.success('登录成功！')
          await router.replace(loginDestination())
        } else {
          ElMessage.warning('用户已在别处登录，请勿重复登录！')
        }
      } else {
        ElMessage.error('请输入正确的验证码!')
      }
    } catch (error) {
      ElMessage.error(responseErrorMessage(error, '验证失败，请稍后重试'))
    } finally {
      loginForm.password = tempPassword
      loading.value = false
    }
  })
}
// 登录
const login = (formEl) => {
  if (!formEl) return
  formEl.validate(async (valid) => {
    if (!valid) return false
    loading.value = true
    try {
      // 当域名为下面这两个时，弹出公众号弹框
      // let domain = window.location.host
      // if (domain === 'shortlink.magestack.cn' || domain === 'shortlink.nageoffer.com') {
      //   isWC.value = true
      //   return
      // }
      const res1 = await API.user.login(loginForm)
      if (String(res1.data.code) === '0') {
        const token = res1?.data?.data?.token
        persistSession(loginForm.username, token)
        ElMessage.success('登录成功！')
        await router.replace(loginDestination())
      } else if (res1.data.message === '用户已登录') {
        // 如果已经登录了，判断一下浏览器保存的登录信息是不是再次登录的信息，如果是就正常登录
        const cookiesUsername = getUsername()
        if (cookiesUsername === loginForm.username) {
          ElMessage.success('登录成功！')
          await router.replace(loginDestination())
        } else {
          ElMessage.warning('用户已在别处登录，请勿重复登录！')
        }
      } else if (res1.data.message === '用户不存在') {
        ElMessage.error('请输入正确的账号密码!')
      } else {
        ElMessage.error(res1.data.message || '登录失败，请检查账号密码')
      }
    } catch (error) {
      ElMessage.error(responseErrorMessage(error, '登录失败，请稍后重试'))
    } finally {
      loading.value = false
    }
  })
}

const loading = ref(false)
// 是否记住密码
const checked = ref(true)
const vantaRef = ref()
let vantaEffect = null

onMounted(() => {
  vantaEffect = WAVES({
    el: vantaRef.value,
    THREE,
    mouseControls: true,
    touchControls: true,
    gyroControls: false,
    minHeight: 200,
    minWidth: 200,
    scale: 1,
    scaleMobile: 1
  })
})

onBeforeUnmount(() => {
  vantaEffect?.destroy()
})

// 展示登录还是展示注册
const isLogin = ref(true)
const changeLogin = () => {
  let domain = window.location.host
  if (domain === 'shortlink.magestack.cn' || domain === 'shortlink.nageoffer.com') {
    ElMessage.warning('演示环境暂不支持注册')
    return
  }
  isLogin.value = !isLogin.value
}
</script>

<style lang="less" scoped>
.login-box {
  border: 1px solid #c8daf7;
  overflow: hidden;
  display: flex;
  justify-content: space-between;
  border-radius: 24px;
  padding: 0 46px;
  width: 760px;
  min-height: 470px;
  position: absolute;
  z-index: 2;
  top: 54%;
  left: 50%;
  transform: translate(-50%, -50%);
  box-sizing: border-box;
  box-shadow: 0 28px 70px rgba(34, 75, 145, 0.2);
  background-color: #fff;
  animation: hideIndex 0.5s;

  h2 {
    font-size: 30px;
    font-family:
      PingFangSC-Semibold,
      PingFang SC;
    font-weight: 600;
    color: #17315c;
    width: 100%;
    text-align: center;
    padding: 20px;
  }

  .el-form-item {
    margin-bottom: 23px;
  }

  .btn-gourp {
    margin-top: 30px;
    display: flex;
    justify-content: space-between;
    margin-bottom: 20px;

    .el-button {
      width: 100px;
    }

    .remeber-password {
      left: 0;
      line-height: 0.5rem;
    }
  }

  .el-checkbox {
    width: 100%;
    text-align: center;
    margin-top: 1rem;
  }
}

:deep(.el-form-item__content) {
  margin-left: 0 !important;
}

@keyframes hideIndex {

  // <!--具体细节自己可以调整-->
  0% {
    opacity: 0;
    transform: translate(7.3125rem, -50%);
  }

  100% {
    opacity: 1;
    transform: translate(-50%, -50%);
  }
}

.login-page {
  position: relative;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: #edf5ff;
}

.login-background {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
}

.background-shape {
  position: absolute;
  display: block;
  border-radius: 999px;
  opacity: 0.88;
}

.shape-blue {
  width: 42vw;
  height: 42vw;
  min-width: 520px;
  min-height: 520px;
  top: -25vw;
  right: -11vw;
  background: #2f6fed;
}

.shape-cyan {
  width: 320px;
  height: 320px;
  left: -130px;
  bottom: -90px;
  background: #2dc7c9;
}

.shape-violet {
  width: 180px;
  height: 180px;
  right: 9vw;
  bottom: 7vh;
  background: #8d72e8;
}

.brand {
  position: absolute;
  z-index: 2;
  top: 8vh;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 14px;
  color: #14336c;
  white-space: nowrap;

  .brand-mark {
    width: 48px;
    height: 48px;
    display: grid;
    place-items: center;
    border-radius: 15px;
    background: #146ef5;
    color: #fff;
    box-shadow: 0 12px 28px rgba(20, 110, 245, 0.28);
    font-size: 26px;
  }

  strong,
  small {
    display: block;
  }

  strong {
    font-size: 30px;
    line-height: 1.05;
    letter-spacing: -0.5px;
  }

  small {
    margin-top: 6px;
    color: #5b7195;
    font-size: 14px;
    letter-spacing: 2px;
  }
}

.logon {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.hidden {
  animation: hidden 1s;
  animation-fill-mode: forwards; // 保持最后的状态
  pointer-events: none;
}

@keyframes hidden {

  // <!--具体细节自己可以调整-->
  0% {
    opacity: 1;
  }

  70% {
    opacity: 0;
  }

  100% {
    opacity: 0;
  }
}

.move {
  position: absolute;
  right: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 40%;
  transition: transform 0.45s ease;
  align-items: center;
  background: #146ef5;
}

.move-left {
  transform: translateX(-150%);
}

.move-kicker {
  margin-bottom: 18px;
  color: #bcd7ff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 1.8px;
}

:deep(.el-input__suffix-inner) {
  width: 60px;
}

.form-container1 {
  margin-top: 28px;
}

.second-font {
  margin-left: 13px;
}

.verification-flex {
  display: flex;
  flex-direction: column;
  align-items: flex-start;

  .img {
    margin-top: 10px;
    align-self: center;
  }
  .form {
    transform: translateY(15px);
    width: 90%;
  }
}

/* 保留 NageOffer 原版登录页的视觉和空间关系。 */
.login-box {
  width: 700px;
  min-height: 0;
  top: 50%;
  z-index: 2;
  padding: 0 40px;
  border: 2px solid #0984e3;
  border-radius: 20px;
  box-shadow: 0 0 10px rgba(0, 0, 0, 0.2);

  h2 {
    color: #3a3f63;
  }
}

.login-page {
  background: transparent;
}

.vanta {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.title {
  position: absolute;
  top: 15%;
  left: 50%;
  z-index: 2;
  transform: translateX(-50%);
  color: #fff;
  font-size: 40px;
  font-weight: 700;
  white-space: nowrap;
}

.form-container1 {
  margin-top: 0;
  transform: translateY(-80%);
}

.move {
  background: linear-gradient(to right, #1a8fd5, #0984e3);
}

@media (max-width: 820px) {
  .brand {
    top: 4vh;
  }

  .login-box {
    width: calc(100vw - 32px);
    max-width: 560px;
    min-height: 640px;
    top: 57%;
    padding: 128px 28px 18px;
  }

  .logon,
  .register {
    position: absolute;
    top: 112px;
    left: 0;
    width: 100%;
    padding: 12px 28px 20px;
    box-sizing: border-box;
  }

  .move {
    top: 0;
    right: 0;
    width: 100%;
    height: 112px;
    transform: none;
    flex-direction: row;
    gap: 14px;
    padding: 18px 22px;
    box-sizing: border-box;

    .move-kicker {
      display: none;
    }

    span {
      margin: 0 !important;
    }

    .el-button {
      margin: 0 0 0 auto !important;
    }
  }

  .shape-violet {
    display: none;
  }
}
</style>
