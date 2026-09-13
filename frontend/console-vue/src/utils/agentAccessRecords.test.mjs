import assert from 'node:assert/strict'
import test from 'node:test'
import { toAccessRecordRow } from './agentAccessRecords.js'

test('canonical access-record kind and actual HTTP status reach their cells', () => {
  const row = toAccessRecordRow({ kind: 'CLICK', status: 307 })
  assert.equal(row.eventType, 'CLICK')
  assert.equal(row.status, '307')
})

test('legacy names and textual result labels remain compatible', () => {
  const row = toAccessRecordRow({ eventType: 'REDIRECT', httpStatus: '302', status: 302, result: 'ALLOWED' })
  assert.equal(row.eventType, 'REDIRECT')
  assert.equal(row.status, '302 · ALLOWED')
  assert.equal(toAccessRecordRow({ eventType: 'UNKNOWN', kind: 'CLICK' }).eventType, 'CLICK')
})

test('absent or placeholder fields never invent a click or successful response', () => {
  for (const source of [{}, { kind: null, status: null }, { eventType: 'UNKNOWN', httpStatus: 'N/A', status: 0 }, { status: '0' }]) {
    const row = toAccessRecordRow(source)
    assert.equal(row.status, '—')
    assert.equal(row.eventType, '—')
  }
  for (const status of [-1, 99, 600, 302.5, NaN]) assert.equal(toAccessRecordRow({ status }).status, '—')
})

test('canonical and legacy valid status codes are displayed without rewriting them to 302', () => {
  assert.equal(toAccessRecordRow({ status: 404, kind: 'REQUEST' }).status, '404')
  assert.equal(toAccessRecordRow({ httpStatus: 0, status: 429 }).status, '429')
})

test('adding result fields preserves geography, visitor classification, time and masking', () => {
  const row = toAccessRecordRow({ kind: 'CLICK', status: 302, country: 'CN', province: '广东省', city: '广州市', network: '电信',
    occurredAt: '2026-09-13T13:52:00Z', visitorHash: 'a'.repeat(64), ipHash: 'b'.repeat(64), uvType: 'newUser' })
  assert.equal(row.createTime, '2026-09-13 21:52:00')
  assert.equal(row.locale, 'CN · 广东省 · 广州市')
  assert.equal(row.network, '电信')
  assert.equal(row.visitorType, '新访客')
  assert.equal(row.status, '302')
  assert.equal(row.eventType, 'CLICK')
  assert.ok(!JSON.stringify(row).includes('a'.repeat(64)))
  assert.ok(!JSON.stringify(row).includes('b'.repeat(64)))
})
