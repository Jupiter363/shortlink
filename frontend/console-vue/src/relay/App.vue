<script setup>
import { computed, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { relay, state, messages } from './core/controller.js'
import { navigation } from './router.js'
import ProductDialogs from './views/ProductDialogs.vue'

provide('relay', relay)
const route = useRoute()
const navOpen = ref(false)
const isAuth = computed(() => route.meta.public)
const title = computed(
  () => navigation.find((item) => item.route === route.path)?.label || '工作台'
)
function navigate(path) {
  if (relay.go(path)) navOpen.value = false
}
watch(
  () => state.session.loggedIn,
  (value) => {
    if (!value) navOpen.value = false
  }
)
</script>
<template>
  <div class="relay-app">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <main v-if="isAuth" id="main-content" class="auth-stage"><RouterView :key="route.path" /></main>
    <template v-else>
      <aside class="sidebar">
        <button class="brand-link" aria-label="返回短链接工作区" @click="navigate('/home/space')">
          <RBrand variant="small" :size="38" /><span
            ><strong>JUPITER RELAY</strong><small>木星中继站</small></span
          >
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
        <header class="topbar">
          <RIconButton
            class="mobile-menu"
            icon="browser"
            label="打开导航"
            @click="navOpen = true"
          />
          <div class="breadcrumb">木星中继站 <span>/</span> {{ title }}</div>
          <div class="topbar-actions">
            <span class="local-badge">{{ state.session.username }}</span>
          </div>
        </header>
        <main id="main-content" class="main-content" tabindex="-1">
          <RouterView :key="route.path" />
        </main>
      </div>
    </template>
    <RModal :open="navOpen" title="工作台导航" drawer @close="navOpen = false"
      ><div class="drawer-nav">
        <RButton
          v-for="item in navigation"
          :key="item.route"
          :kind="route.path === item.route ? 'primary' : 'secondary'"
          :disabled="state.agentBusy && route.path !== item.route"
          @click="navigate(item.route)"
          ><RIcon :name="item.icon" />{{ item.label }}</RButton
        >
      </div></RModal
    >
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
