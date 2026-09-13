import { createRouter, createWebHistory } from 'vue-router'
import { isNotEmpty } from '@/utils/plugins'
import { getToken, setToken, setUsername } from '@/core/auth'
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/home'
    },
    {
      path: '/login',
      name: 'LoginIndex',
      component: () => import('@/views/login/LoginIndex.vue')
    },
    {
      path: '/home',
      name: 'LayoutIndex',
      redirect: '/home/space',
      component: () => import('@/views/home/HomeIndex.vue'),
      children: [
        {
          // 前面不能加/
          path: 'space',
          name: 'MySpace',
          component: () => import('@/views/mySpace/MySpaceIndex.vue'),
          meta: { title: '我的空间' }
        },
        {
          path: 'recycleBin',
          name: 'RecycleBin',
          component: () => import('@/views/recycleBin/RecycleBinIndex.vue'),
          meta: { title: '回收站' }
        },
        {
          path: 'agent',
          redirect: '/home/agent/campaign-analysis'
        },
        {
          path: 'agent/campaign-analysis',
          name: 'CampaignAnalysisAgent',
          component: () => import('@/views/agent/AgentWorkspace.vue'),
          meta: { title: '投放分析 Agent', agentType: 'campaign-analysis' }
        },
        {
          path: 'agent/security-risk',
          name: 'SecurityRiskAgent',
          component: () => import('@/views/agent/AgentWorkspace.vue'),
          meta: { title: '安全风控 Agent', agentType: 'security-risk' }
        },
        {
          path: 'account',
          name: 'Mine',
          component: () => import('@/views/mine/MineIndex.vue'),
          meta: { title: '个人中心' }
        }
      ]
    }
  ]
})

// eslint-disable-next-line no-unused-vars
router.beforeEach((to) => {
  const localToken = localStorage.getItem('token')
  const localUsername = localStorage.getItem('username')
  if (isNotEmpty(localToken)) {
    setToken(localToken)
  }
  if (isNotEmpty(localUsername)) {
    setUsername(localUsername)
  }
  const token = getToken()
  if (to.path === '/login') {
    return true
  }
  if (isNotEmpty(token)) {
    return true
  }
  return { path: '/login', query: { redirect: to.fullPath } }
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ShortLink` : 'ShortLink'
})

export default router
