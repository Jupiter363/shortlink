<template>
  <div class="common-layout">
    <el-container>
      <el-header height="54px" style="padding: 0">
        <div class="header">
          <button class="logo" type="button" @click="toMySpace">
            拿个offer-SaaS短链接@马丁
          </button>
          <nav class="header-links" aria-label="顶部导航">
            <button
              class="link-span agent-link"
              :class="{ active: route.meta.agentType === 'campaign-analysis' }"
              type="button"
              @click="router.push('/home/agent/campaign-analysis')"
            >
              <DataAnalysis />
              投放分析 Agent
            </button>
            <button
              class="link-span agent-link"
              :class="{ active: route.meta.agentType === 'security-risk' }"
              type="button"
              @click="router.push('/home/agent/security-risk')"
            >
              <Lock />
              安全风控 Agent
            </button>
            <a class="link-span" target="_blank" rel="noreferrer" href="https://nageoffer.com/shortlink/">
              官方文档
            </a>
            <a class="link-span" target="_blank" rel="noreferrer" href="https://nageoffer.com/planet/group/">
              加沟通群
            </a>
            <a class="link-span" target="_blank" rel="noreferrer" href="https://nageoffer.com/shortlink/video/">
              🔥视频教程
            </a>
            <a class="link-span" target="_blank" rel="noreferrer" href="http://shortlink.nageoffer.com">
              演示环境
            </a>
            <el-dropdown trigger="click">
              <button class="name-span" type="button">{{ username }}</button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="toMine">个人信息</el-dropdown-item>
                  <el-dropdown-item divided @click="logout">退出</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </nav>
        </div>
      </el-header>
      <el-main style="padding: 0">
        <div class="content-box">
          <RouterView class="content-space" />
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { getCurrentInstance, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { removeKey, removeUsername, getToken, getUsername } from '@/core/auth.js'
import { ElMessage } from 'element-plus'

const { proxy } = getCurrentInstance()
const API = proxy.$API
const route = useRoute()
const router = useRouter()
const username = ref(getUsername() || localStorage.getItem('username') || '用户')

const toMine = () => router.push('/home/account')
const toMySpace = () => router.push('/home/space')

const logout = async () => {
  const token = getToken()
  const currentUsername = getUsername()
  try {
    await API.user.logout({ token, username: currentUsername })
  } catch {
    // 本地登录态仍需清理，避免服务不可用时用户被困在当前会话。
  } finally {
    removeUsername()
    removeKey()
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    await router.push('/login')
    ElMessage.success('已退出登录')
  }
}

onMounted(async () => {
  const currentUsername = getUsername()
  if (!currentUsername) return
  try {
    const response = await API.user.queryUserInfo(currentUsername)
    username.value = response?.data?.data?.realName || currentUsername
  } catch {
    username.value = currentUsername
  }
})
</script>

<style lang="scss" scoped>
.app-shell {
  min-width: 960px;
  min-height: 100vh;
  background: #f5f7fb;
  color: #172033;
}

.app-header {
  position: relative;
  z-index: 20;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto minmax(220px, 1fr);
  align-items: center;
  min-height: 64px;
  padding: 0 24px;
  border-bottom: 1px solid #e5eaf2;
  background: #ffffff;
  box-shadow: 0 2px 10px rgba(31, 42, 68, 0.04);
}

.brand,
.nav-item,
.account-button {
  border: 0;
  font: inherit;
}

.brand {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 11px;
  padding: 0;
  background: transparent;
  color: #18233a;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 10px;
  background: #2368e8;
  color: #ffffff;
  box-shadow: 0 6px 14px rgba(35, 104, 232, 0.22);
}

.brand-mark :deep(svg) {
  width: 19px;
  height: 19px;
}

.brand-copy {
  display: grid;
  text-align: left;
}

.brand-copy strong {
  font-size: 16px;
  line-height: 20px;
  letter-spacing: 0.01em;
}

.brand-copy small {
  color: #8490a5;
  font-size: 11px;
  line-height: 16px;
}

.primary-nav {
  display: flex;
  align-self: stretch;
  align-items: center;
  gap: 6px;
}

.nav-item {
  position: relative;
  display: inline-flex;
  height: 40px;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  border-radius: 8px;
  background: transparent;
  color: #657189;
  font-size: 14px;
  font-weight: 600;
  transition: color 160ms ease, background-color 160ms ease;
}

.nav-item :deep(svg) {
  width: 17px;
  height: 17px;
}

.nav-item:hover {
  background: #f1f5fc;
  color: #235fc8;
}

.nav-item.active {
  background: #eaf2ff;
  color: #1e5ecf;
}

.nav-item.active::after {
  position: absolute;
  right: 14px;
  bottom: -12px;
  left: 14px;
  height: 2px;
  border-radius: 2px;
  background: #2368e8;
  content: '';
}

.header-actions {
  display: flex;
  justify-self: end;
  align-items: center;
  gap: 18px;
}

.document-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #657189;
  font-size: 13px;
}

.document-link:hover {
  color: #2368e8;
  text-decoration: none;
}

.document-link :deep(svg) {
  width: 15px;
}

.account-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 7px 5px 5px;
  border-radius: 9px;
  background: #f6f8fb;
  color: #34415a;
}

.account-button:hover {
  background: #eef2f8;
}

.account-button > :deep(svg) {
  width: 13px;
}

.avatar {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 8px;
  background: #dfeaff;
  color: #235fc8;
  font-size: 13px;
  font-weight: 700;
}

.account-name {
  max-width: 110px;
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-main {
  height: calc(100vh - 64px);
  overflow: auto;
  background: #f5f7fb;
}

@media (max-width: 1120px) {
  .app-header {
    grid-template-columns: 210px auto 1fr;
    padding: 0 16px;
  }

  .document-link {
    display: none;
  }
}

.common-layout,
.common-layout > .el-container {
  min-width: 960px;
  height: 100vh;
}

.common-layout .el-main {
  background-color: #e8e8e8;
}

.header {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: space-between;
  padding-left: 20px;
  background-color: #252b30;
  color: rgba(255, 255, 255, 0.85);
}

.header-links {
  display: flex;
  height: 100%;
  align-items: center;
}

.content-box {
  height: calc(100vh - 54px);
  background-color: #fff;
}

.logo,
.link-span,
.name-span {
  border: 0;
  background: transparent;
  font: inherit;
}

.logo {
  padding: 0;
  color: #e8e8e8;
  cursor: pointer;
  font-family: Helvetica, Tahoma, Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  font-size: 15px;
  font-weight: 600;
}

.logo:hover {
  color: #fff;
}

.link-span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-right: 30px;
  color: #fff;
  cursor: pointer;
  font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 16px;
  opacity: 0.6;
  text-decoration: none;
}

.link-span:hover {
  color: #fff;
  opacity: 1;
  text-decoration: underline !important;
}

.agent-link :deep(svg) {
  width: 16px;
  height: 16px;
}

.agent-link.active {
  color: #62b0ff;
  opacity: 1;
  text-decoration: none !important;
}

.name-span {
  max-width: 120px;
  margin-right: 30px;
  overflow: hidden;
  color: #fff;
  cursor: pointer;
  font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;
  font-size: 12px;
  opacity: 0.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-span:hover {
  opacity: 1;
}

:deep(.el-tooltip__trigger:focus-visible) {
  outline: unset;
}
</style>
