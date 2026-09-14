<script setup>
import { computed, provide, ref } from 'vue'
import { useRoute } from 'vue-router'
import { relay, state, messages } from './core/controller.js'
import { navigation } from './router.js'
import ProductDialogs from './views/ProductDialogs.vue'

provide('relay', relay)
const route = useRoute()
const isAuth = computed(() => route.meta.public)
const compactNavigation = ref(false)
function navigate(path) {
  relay.go(path)
}
</script>
<template>
  <div
    :class="[
      'relay-app',
      { 'relay-workbench': !isAuth, 'relay-workbench--compact': !isAuth && compactNavigation }
    ]"
  >
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <main v-if="isAuth" id="main-content" class="auth-stage"><RouterView :key="route.path" /></main>
    <template v-else>
      <aside id="workspace-sidebar" class="sidebar" aria-label="工作台侧栏">
        <button class="brand-link" aria-label="返回短链接工作区" @click="navigate('/home/space')">
          <RBrand variant="small" :show-text="true" size="var(--sidebar-brand-size, 42px)" />
          <span class="brand-tagline" aria-hidden="true">连接 · 洞察 · 守护</span>
        </button>
        <nav aria-label="主导航">
          <button
            v-for="item in navigation.filter((item) => !item.route.endsWith('/account'))"
            :key="item.route"
            :class="['nav-item', { selected: route.path === item.route }]"
            :aria-current="route.path === item.route ? 'page' : undefined"
            :title="item.label"
            :aria-label="item.label"
            :disabled="state.agentBusy && route.path !== item.route"
            @click="navigate(item.route)"
          >
            <RIcon :name="item.icon" /><span>{{ item.label }}</span>
          </button>
        </nav>
        <div class="sidebar-bottom">
          <button
            class="nav-item"
            :class="{ selected: route.path === '/home/account' }"
            :aria-current="route.path === '/home/account' ? 'page' : undefined"
            aria-label="账户中心"
            title="账户中心"
            :disabled="state.agentBusy"
            @click="navigate('/home/account')"
          >
            <RIcon name="user" /><span>{{ state.session.username }} · 账户</span>
          </button>
        </div>
      </aside>
      <div class="main-column">
        <header class="workspace-topnav">
          <div class="workspace-topnav-art" aria-hidden="true">
            <span class="topnav-orbit"></span>
            <RIcon name="sparkle" class="topnav-star topnav-star--small" :size="18" />
            <RBrand class="topnav-planet" :show-text="false" :size="58" />
            <RIcon name="sparkle" class="topnav-star topnav-star--large" :size="26" />
            <RRobot role="navigator" expression="success" class="topnav-robot" :size="86" />
          </div>
          <div id="workspace-page-header" class="workspace-page-header"></div>
          <button
            class="workspace-nav-toggle"
            :aria-expanded="!compactNavigation"
            aria-controls="workspace-sidebar"
            @click="compactNavigation = !compactNavigation"
          >
            {{ compactNavigation ? '展开导航' : '收起导航' }}
          </button>
        </header>
        <main id="main-content" class="main-content" tabindex="-1">
          <RouterView :key="route.path" />
        </main>
      </div>
    </template>
    <RModal
      :open="state.modal.type === 'copyFallback'"
      title="手动复制短链接"
      @close="relay.close()"
      ><RField label="短链接" :model-value="state.modal.payload?.value || ''" readonly />
      <p>可以选中上方链接手动复制。</p></RModal
    >
    <ProductDialogs v-if="state.session.loggedIn" />
    <div class="toast-stack" aria-live="polite" aria-atomic="false">
      <div
        v-for="message in messages"
        :key="message.id"
        :class="['toast', 'toast-' + message.tone]"
      >
        {{ message.message }}
      </div>
    </div>
  </div>
</template>
