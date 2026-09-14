import { createRouter, createWebHistory } from 'vue-router'
import { state, relay, attachRouter } from './core/controller.js'

export const navigation = [
  { route: '/home/space', label: '短链接', icon: 'link' },
  { route: '/home/analytics', label: '访问统计', icon: 'chart' },
  { route: '/home/risk-center', label: '风险中心', icon: 'shield' },
  { route: '/home/agent/campaign-analysis', label: '投放分析 Agent', icon: 'chart' },
  { route: '/home/agent/security-risk', label: '安全风控 Agent', icon: 'brain' },
  { route: '/home/recycleBin', label: '回收站', icon: 'archive' },
  { route: '/home/account', label: '账户中心', icon: 'user' }
]
const routes = [
  {
    path: '/login',
    component: () => import('./views/AuthView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    component: () => import('./views/AuthView.vue'),
    props: { register: true },
    meta: { title: '注册', public: true }
  },
  { path: '/home/space', component: () => import('./views/WorkspaceView.vue') },
  { path: '/home/recycleBin', component: () => import('./views/WorkspaceView.vue') },
  { path: '/home/analytics', component: () => import('./views/AnalyticsView.vue') },
  { path: '/home/risk-center', component: () => import('./views/RiskView.vue') },
  {
    path: '/home/agent/campaign-analysis',
    component: () => import('./views/AgentView.vue'),
    props: { type: 'campaign-analysis' }
  },
  {
    path: '/home/agent/security-risk',
    component: () => import('./views/AgentView.vue'),
    props: { type: 'security-risk' }
  },
  { path: '/home/account', component: () => import('./views/AccountView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/home/space' }
]
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})
router.beforeEach((to) => {
  if (state.agentBusy && to.path !== state.route) {
    relay.notify('当前分析尚未结束，请等待结果后再切换。', 'warning')
    return false
  }
  if (!to.meta.public && (!state.session.loggedIn || state.initialization.state !== 'READY')) {
    state.redirect = to.fullPath
    return { path: '/login', query: { redirect: to.fullPath }, replace: true }
  }
  if (to.meta.public && state.session.loggedIn && state.initialization.state === 'READY')
    return '/home/space'
  if (
    to.path === '/login' &&
    typeof to.query.redirect === 'string' &&
    navigation.some((item) => item.route === to.query.redirect.split('?')[0])
  )
    state.redirect = to.query.redirect
})
router.afterEach((to, from, failure) => {
  if (failure) return
  state.route = to.path
  if (to.path !== from.path) relay.close()
  document.title = `${to.meta.title || navigation.find((item) => item.route === to.path)?.label || '工作台'} · 木星中继站`
})
attachRouter(router)
export default router
