import { reactive, ref } from 'vue'
import QRCode from 'qrcode'
import * as sessionStore from './session.js'
import { onSessionExpired } from '../api/http.js'
import { listGroups, listLinks, listRecycled } from '../api/product.js'

const saved = sessionStore.getSession()
export const state = reactive({
  route: '/login',
  redirect: '/home/space',
  session: {
    loggedIn: !!saved,
    username: saved?.username || '',
    token: saved?.token || '',
    remember: !!saved?.remember
  },
  initialization: { state: 'PENDING', groupId: '' },
  groups: [],
  groupId: '',
  defaultGroupId: '',
  links: [],
  list: {
    current: 1,
    size: 10,
    total: null,
    pages: null,
    orderTag: 'createTime',
    statsMeta: null,
    loading: false,
    error: ''
  },
  modal: { type: '', payload: null },
  batchJob: null,
  activeJobs: [],
  profile: null,
  analyticsScope: null,
  agentBusy: false,
  agentSessions: {},
  riskCommands: {}
})
export const messages = ref([])
let router,
  messageId = 0,
  listGeneration = 0,
  groupsGeneration = 0,
  listController
export function notify(message, tone = 'info') {
  const id = ++messageId
  messages.value.push({ id, message: String(message), tone })
  setTimeout(() => {
    messages.value = messages.value.filter((item) => item.id !== id)
  }, 6000)
}
export function attachRouter(value) {
  router = value
}
function go(path, replace = false) {
  if (state.agentBusy && path !== state.route) {
    notify('当前分析尚未结束，请等待结果后再切换。', 'warning')
    return false
  }
  if (!String(path).startsWith('/home') && !['/login', '/register'].includes(path))
    path = '/home/space'
  router?.[replace ? 'replace' : 'push'](path)
  return true
}
function close() {
  state.modal = { type: '', payload: null }
}
function resetPrivateState() {
  ++listGeneration
  ++groupsGeneration
  listController?.abort()
  state.groups = []
  state.links = []
  state.groupId = ''
  state.defaultGroupId = ''
  state.initialization = { state: 'PENDING', groupId: '' }
  state.profile = null
  state.batchJob = null
  state.activeJobs = []
  state.agentSessions = {}
  state.riskCommands = {}
  state.agentBusy = false
  state.analyticsScope = null
  Object.assign(state.list, {
    current: 1,
    total: null,
    pages: null,
    orderTag: 'createTime',
    statsMeta: null,
    loading: false,
    error: ''
  })
  close()
}
function setSession(value) {
  resetPrivateState()
  sessionStore.setSession(value)
  Object.assign(state.session, { ...value, loggedIn: true })
}
function clearSession() {
  resetPrivateState()
  sessionStore.clearSession()
  Object.assign(state.session, { loggedIn: false, username: '', token: '', remember: false })
}
async function refreshGroups() {
  const generation = ++groupsGeneration
  const groups = await listGroups()
  if (generation !== groupsGeneration || !state.session.loggedIn) return []
  state.groups = groups
  state.defaultGroupId = state.initialization.groupId || state.defaultGroupId
  if (!groups.some((group) => group.id === state.groupId))
    state.groupId =
      groups.find((group) => group.id === state.defaultGroupId)?.id || groups[0]?.id || ''
  return groups
}
async function refreshLinks({ reset = false, targetCurrent = null } = {}) {
  const generation = ++listGeneration
  listController?.abort()
  listController = new AbortController()
  const requestedCurrent = reset ? 1 : (targetCurrent ?? state.list.current)
  // Creation-order pages authorize different link IDs; only metric ranking uses a
  // snapshot of the entire (at most 500-link) group across its display pages.
  const requestedStatsMeta =
    reset || state.list.orderTag === 'createTime' ? null : state.list.statsMeta
  state.list.loading = true
  state.list.error = ''
  state.links = []
  if (reset)
    Object.assign(state.list, {
      current: 1,
      total: null,
      pages: null,
      statsMeta: null
    })
  try {
    const recycled = state.route === '/home/recycleBin'
    if (!recycled && !state.groupId) {
      Object.assign(state.list, {
        current: 1,
        total: 0,
        pages: 0,
        statsMeta: null
      })
      return
    }
    const { size, orderTag } = state.list
    const loadPage = (current) =>
      recycled
        ? listRecycled({ current, size }, { signal: listController.signal })
        : listLinks(
            {
              gid: state.groupId,
              current,
              size,
              orderTag,
              statsSnapshotId: requestedStatsMeta?.snapshotId,
              statsEnd: requestedStatsMeta?.window?.end || requestedStatsMeta?.statsEnd
            },
            { signal: listController.signal }
          )
    let page = await loadPage(requestedCurrent)
    if (generation !== listGeneration) return
    // A deletion can shrink the last page. Fetch a real first page rather than inventing rows or totals.
    if (requestedCurrent > 1 && requestedCurrent > Math.max(1, page.pages)) page = await loadPage(1)
    if (generation !== listGeneration) return
    state.links = page.records
    Object.assign(state.list, {
      total: page.total,
      pages: page.pages,
      current: page.current,
      statsMeta: page.statsMeta
    })
  } catch (error) {
    if (generation === listGeneration && error.name !== 'AbortError')
      state.list.error = error.message
  } finally {
    if (generation === listGeneration) state.list.loading = false
  }
}
async function refreshWorkspace() {
  await refreshGroups()
  await refreshLinks({ reset: true })
}
onSessionExpired(() => {
  if (!state.session.loggedIn) return
  state.redirect = state.route.startsWith('/home') ? state.route : '/home/space'
  clearSession()
  notify('登录已过期，请重新登录后继续。', 'warning')
  go('/login', true)
})
export const relay = {
  state,
  notify,
  go,
  close,
  open(type, payload = null) {
    state.modal = { type, payload }
  },
  setSession,
  clearSession,
  refreshGroups,
  refreshLinks,
  refreshWorkspace,
  async copy(value) {
    if (!value) {
      notify('短链接地址尚未就绪。', 'warning')
      return
    }
    try {
      await navigator.clipboard.writeText(value)
      notify('已复制短链接', 'success')
    } catch {
      state.modal = { type: 'copyFallback', payload: { value } }
    }
  },
  qrDataUrl(value) {
    return QRCode.toDataURL(value, {
      width: 480,
      margin: 3,
      errorCorrectionLevel: 'M',
      color: { dark: '#182847', light: '#ffffff' }
    })
  }
}
