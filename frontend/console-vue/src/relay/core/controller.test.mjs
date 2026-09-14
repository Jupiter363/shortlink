import assert from 'node:assert/strict'
import test from 'node:test'

import { relay, state } from './controller.js'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: (key) => (values.has(key) ? values.get(key) : null),
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key)
  }
}

function response(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  })
}

function page({ id, gid, current = 1, total = 1, pages = 1, statsMeta = null }) {
  return response({
    code: '0',
    data: {
      records: [
        {
          linkId: id,
          gid,
          domain: 'localhost:19080',
          shortUri: id,
          fullShortUrl: `localhost:19080/${id}`,
          originUrl: `https://example.test/${id}`,
          describe: id
        }
      ],
      current,
      size: 10,
      total,
      pages,
      statsMeta
    }
  })
}

function seed({ groupId = 'group-a', current = 2, total = 20, pages = 2 } = {}) {
  globalThis.localStorage = memoryStorage()
  globalThis.sessionStorage = memoryStorage()
  globalThis.location = { origin: 'https://admin.local.test' }
  relay.setSession({ username: 'jupiter', token: 'opaque-token', remember: false })
  state.route = '/home/space'
  state.groupId = groupId
  state.list.current = current
  state.list.total = total
  state.list.pages = pages
  state.list.orderTag = 'totalPv'
  state.list.statsMeta = {
    snapshotId: 'confirmed-snapshot',
    window: { end: '2026-09-14' }
  }
  state.links = [{ id: 'confirmed-row', groupId }]
}

test('切换分组失败时清除旧范围的总数、页码和统计快照', { concurrency: false }, async () => {
  seed()
  state.groupId = 'group-b'
  globalThis.fetch = async () =>
    response({ code: 'SERVICE_UNAVAILABLE', message: 'temporary failure' }, 503)

  await relay.refreshLinks({ reset: true })

  assert.equal(state.list.current, 1)
  assert.equal(state.list.total, null)
  assert.equal(state.list.pages, null)
  assert.equal(state.list.statsMeta, null)
  assert.deepEqual(state.links, [])
  assert.equal(state.list.error, 'temporary failure')
})

test('同范围翻页失败保留已确认页码与快照供重试', { concurrency: false }, async () => {
  seed({ current: 2, total: 30, pages: 3 })
  let requestedUrl = ''
  globalThis.fetch = async (url) => {
    requestedUrl = url
    return response({ code: 'SERVICE_UNAVAILABLE', message: 'temporary failure' }, 503)
  }

  await relay.refreshLinks({ targetCurrent: 3 })

  assert.equal(state.list.current, 2)
  assert.equal(state.list.total, 30)
  assert.equal(state.list.pages, 3)
  assert.deepEqual(state.list.statsMeta, {
    snapshotId: 'confirmed-snapshot',
    window: { end: '2026-09-14' }
  })
  assert.match(requestedUrl, /current=3/)
  assert.match(requestedUrl, /statsSnapshotId=confirmed-snapshot/)
})

test('旧范围的迟到响应不能覆盖新范围结果', { concurrency: false }, async () => {
  seed({ current: 1, total: 1, pages: 1 })
  let releaseOld
  let oldStarted
  const started = new Promise((resolve) => {
    oldStarted = resolve
  })
  globalThis.fetch = (url) => {
    if (url.includes('gid=group-a')) {
      oldStarted()
      return new Promise((resolve) => {
        releaseOld = () => resolve(page({ id: 'old-row', gid: 'group-a' }))
      })
    }
    return Promise.resolve(
      page({
        id: 'new-row',
        gid: 'group-b',
        total: 11,
        pages: 2,
        statsMeta: { snapshotId: 'new-snapshot' }
      })
    )
  }

  const oldRequest = relay.refreshLinks({ reset: true })
  await started
  state.groupId = 'group-b'
  const newRequest = relay.refreshLinks({ reset: true })
  await newRequest
  releaseOld()
  await oldRequest

  assert.equal(state.groupId, 'group-b')
  assert.equal(state.list.total, 11)
  assert.equal(state.list.pages, 2)
  assert.equal(state.list.statsMeta.snapshotId, 'new-snapshot')
  assert.deepEqual(
    state.links.map((link) => link.id),
    ['new-row']
  )
})

test('没有可选分组时早退会清除全部分页与快照元数据', { concurrency: false }, async () => {
  seed()
  state.groupId = ''
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    return page({ id: 'unexpected', gid: 'group-a' })
  }

  await relay.refreshLinks({ reset: true })

  assert.equal(calls, 0)
  assert.deepEqual(
    {
      current: state.list.current,
      total: state.list.total,
      pages: state.list.pages,
      statsMeta: state.list.statsMeta
    },
    { current: 1, total: 0, pages: 0, statsMeta: null }
  )
})
