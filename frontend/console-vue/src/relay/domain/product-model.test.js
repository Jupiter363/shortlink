/* eslint-env node */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  absoluteShortUrl,
  mapLink,
  mapPage,
  mapGroup,
  lifecycleBody,
  updateBody,
  createBody,
  inspectBatch,
  validateBatch,
  BATCH_LIMIT_BYTES,
  lines,
  profileBody,
  passwordError,
  dateEpoch,
  displayDate,
  expiryStatus,
  mapJob,
  mapBatchRow,
  appendCursorRows,
  prepareCreateAttempt,
  validOriginUrl,
  validUsername
} from './product-model.js'

const form = {
  url: 'https://example.com/campaign',
  title: '秋季活动',
  groupId: 'group-a',
  validity: 'forever',
  expires: ''
}
const address = {
  linkId: '9007199254740999',
  shortUri: 'AbCd12345',
  fullShortUrl: 's.example/AbCd12345',
  gid: 'group-a',
  routeVersion: 12,
  originUrl: form.url,
  describe: form.title,
  validDateType: 0
}

test('mapping preserves identity, optimistic version and unavailable statistics', () => {
  const link = mapLink({
    ...address,
    totalPv: null,
    todayPv: '0',
    todayUv: 18,
    createTime: '2026-09-14 09:30:00'
  })
  assert.equal(link.id, '9007199254740999')
  assert.equal(link.fullShortUrl, address.fullShortUrl)
  assert.equal(link.version, 12)
  assert.equal(link.pv, null)
  assert.equal(link.uv, null)
  assert.equal(link.todayPv, 0)
  assert.equal(link.created, '2026-09-14 09:30')
  assert.equal(mapLink({ ...address, routeVersion: undefined }).version, null)
  assert.equal(mapLink(address, true).recycled, true)
  assert.equal(mapGroup({ gid: 'a', name: 'A' }).count, null)
})

test('page metadata remains authoritative and malformed pagination is rejected', () => {
  const statsMeta = { totalStatus: 'LIFECYCLE_COVERAGE_UNKNOWN', snapshotId: 'snapshot-1' }
  const page = mapPage({ records: [address], total: 23, size: 10, current: 1, statsMeta })
  assert.equal(page.pages, 3)
  assert.equal(page.statsMeta, statsMeta)
  assert.throws(() => mapPage({ records: [] }), /不完整/)
  assert.throws(() => mapPage({ records: [], total: 0, size: 0, current: 1 }), /不完整/)
})

test('local public origin changes only the configured host protocol', () => {
  assert.equal(
    absoluteShortUrl('localhost:19080/AbCd12345', 'http://localhost:19080'),
    'http://localhost:19080/AbCd12345'
  )
  assert.equal(
    absoluteShortUrl('s.example/AbCd12345', 'http://localhost:19080'),
    'https://s.example/AbCd12345'
  )
  assert.equal(
    absoluteShortUrl('localhost:19081/AbCd12345', 'http://localhost:19080'),
    'https://localhost:19081/AbCd12345'
  )
  assert.equal(
    absoluteShortUrl('localhost:19080/AbCd12345', 'http://localhost:19080/path'),
    'https://localhost:19080/AbCd12345'
  )
  assert.equal(
    absoluteShortUrl('localhost:19080/AbCd12345', 'http://user:pass@localhost:19080'),
    'https://localhost:19080/AbCd12345'
  )
  assert.equal(
    absoluteShortUrl({ fullShortUrl: 's.example/AbCd12345', domain: 'http://s.example' }, ''),
    'http://s.example/AbCd12345'
  )
  assert.equal(
    absoluteShortUrl(
      { fullShortUrl: 's.example/AbCd12345', domain: 'http://different.example' },
      ''
    ),
    'https://s.example/AbCd12345'
  )
  assert.equal(absoluteShortUrl('https://s.example/AbCd12345', ''), 'https://s.example/AbCd12345')
  assert.equal(absoluteShortUrl('https://user:pass@s.example/AbCd12345', ''), '')
  assert.equal(absoluteShortUrl('javascript://evil', ''), '')
})

test('creation uses server domain and Beijing validity contract', () => {
  assert.deepEqual(createBody(form, 'request-1'), {
    requestId: 'request-1',
    originUrl: form.url,
    gid: 'group-a',
    createdType: 0,
    validDateType: 0,
    validDate: null,
    describe: '秋季活动'
  })
  const custom = createBody(
    { ...form, validity: 'custom', expires: '2099-02-28T18:35' },
    'request-2'
  )
  assert.equal(custom.validDate, '2099-02-28 18:35:00')
  assert.equal(dateEpoch(custom.validDate), Date.parse('2099-02-28T10:35:00Z'))
  assert.equal(displayDate('2026-09-14T02:30:00Z'), '2026-09-14 10:30')
  assert.equal(expiryStatus('2026-09-14 10:30:00', Date.parse('2026-09-14T03:30:00Z')), 'expired')
  assert.throws(() => createBody({ ...form, validity: 'custom', expires: '' }, 'a'), /未来/)
})

test('mutation requires actual version and preserves origin group during move', () => {
  const link = mapLink(address)
  assert.deepEqual(lifecycleBody(link), {
    fullShortUrl: address.fullShortUrl,
    gid: 'group-a',
    expectedVersion: 12
  })
  const moved = updateBody({ ...form, groupId: 'group-b' }, link)
  assert.equal(moved.originGid, 'group-a')
  assert.equal(moved.gid, 'group-b')
  assert.equal(moved.expectedVersion, 12)
  assert.equal(Object.hasOwn(moved, 'requestId'), false)
  for (const version of [null, 0, NaN, '12', Number.MAX_SAFE_INTEGER + 1])
    assert.throws(() => lifecycleBody({ ...link, version }), /版本/)
})

test('unknown create retry reuses request body and forbids silent replacement', () => {
  const original = prepareCreateAttempt(createBody(form, 'id-1'), null)
  original.uncertain = true
  assert.equal(prepareCreateAttempt(createBody(form, 'new-id'), original), original)
  assert.equal(original.body.requestId, 'id-1')
  assert.throws(
    () => prepareCreateAttempt(createBody({ ...form, title: 'Changed' }, 'id-2'), original),
    /尚未确认/
  )
  original.uncertain = false
  const revised = prepareCreateAttempt(
    createBody({ ...form, title: 'Changed' }, 'id-1'),
    original,
    () => 'fresh-id'
  )
  assert.equal(revised.body.requestId, 'fresh-id')
  assert.equal(revised.body.describe, 'Changed')
})

test('batch routing and description alignment reflect 500 / 501 boundary', () => {
  const build = (count) => ({
    urls: Array.from({ length: count }, (_, index) => `https://example.com/${index}`).join('\n'),
    titles: '',
    groupId: 'group-a'
  })
  const sync = inspectBatch(build(500), 'batch-a'),
    async = inspectBatch(build(501), 'batch-b')
  assert.equal(sync.isAsync, false)
  assert.equal(async.isAsync, true)
  assert.equal(validateBatch(sync).originUrls.length, 500)
  assert.equal(validateBatch(async).originUrls.length, 501)
  assert.throws(() => validateBatch(inspectBatch(build(1))), /2–50,000/)
  assert.throws(() => validateBatch(inspectBatch({ ...build(2), titles: 'one' })), /逐行匹配/)
  assert.deepEqual(lines('first\r\n\r\nthird\n'), ['first', '', 'third'])
  assert.throws(
    () => validateBatch(inspectBatch({ ...build(2), urls: 'https://example.com/a\ninvalid' })),
    /格式/
  )
  assert.equal(
    validateBatch(inspectBatch({ ...build(501), urls: `${build(500).urls}\ninvalid` })).originUrls
      .length,
    501
  )
})

test('8 MiB bound is serialized UTF8 bytes, including Chinese descriptions', () => {
  const batch = {
    urls: Array.from({ length: 2800 }, (_, i) => `https://example.com/${i}`).join('\n'),
    titles: Array(2800).fill('汉'.repeat(1024)).join('\n'),
    groupId: 'group-a'
  }
  const info = inspectBatch(batch, 'batch-id')
  assert.equal(info.bytes, Buffer.byteLength(JSON.stringify(info.body), 'utf8'))
  assert.ok(info.bytes > BATCH_LIMIT_BYTES)
  assert.ok(JSON.stringify(info.body).length < BATCH_LIMIT_BYTES)
  assert.throws(() => validateBatch(info), /8 MiB/)
})

test('job and rows retain actual server states and only advance bounded cursors', () => {
  const pending = mapJob(
    { jobId: 'job-1', state: 'VALIDATING' },
    { owner: 'Jupiter', requestId: 'batch-1', groupId: 'g' }
  )
  assert.equal(pending.totalRows, null)
  assert.equal(pending.owner, 'Jupiter')
  const cancelled = mapJob(
    {
      jobId: 'job-1',
      state: 'CANCELLED',
      totalRows: 501,
      succeededRows: 20,
      failedRows: 0,
      invalidRows: 1
    },
    pending
  )
  assert.equal(cancelled.state, 'CANCELLED')
  assert.equal(cancelled.succeededRows, 20)
  assert.throws(() => mapJob({ jobId: 'x', state: 'COMPLETE' }), /有效状态/)
  const rows = [
    mapBatchRow({ row: 1, state: 'INVALID', error: 'Invalid URL' }),
    mapBatchRow({ row: 3, state: 'SUCCEEDED', result: address })
  ]
  const page = appendCursorRows([], rows, 0)
  assert.equal(page.after, 3)
  assert.equal(page.hasMore, false)
  assert.equal(page.rows[0].shortUrl, '')
  assert.equal(page.rows[1].shortUrl, 'https://s.example/AbCd12345')
  assert.throws(() => appendCursorRows(rows, [rows[1]], 3), /游标/)
  assert.throws(() => appendCursorRows(rows, [rows[1]], 0), /重复/)
  assert.throws(() => appendCursorRows([], Array(21).fill(rows[0]), 0), /分页/)
})

test('profile updates never replay masked phone or blank passwords', () => {
  const base = {
    realName: ' 用户 ',
    mail: 'jupiter@example.com',
    phone: '',
    password: '',
    currentPassword: ''
  }
  assert.deepEqual(profileBody('Jupiter', base), {
    username: 'Jupiter',
    realName: '用户',
    mail: 'jupiter@example.com'
  })
  assert.throws(() => profileBody('Jupiter', { ...base, phone: '138****5678' }), /脱敏/)
  assert.throws(() => profileBody('Jupiter', { ...base, password: 'newPass123' }), /当前密码/)
  assert.equal(
    profileBody('Jupiter', { ...base, password: 'newPass123', currentPassword: 'oldPass123' })
      .currentPassword,
    'oldPass123'
  )
  assert.equal(passwordError('12345678'), '')
  assert.notEqual(passwordError('1234567'), '')
  assert.notEqual(passwordError('1234567890123456'), '')
})

test('URL and username validation reject unsupported schemes and control characters', () => {
  assert.equal(validOriginUrl('https://example.com/?a=中文'), true)
  for (const url of [
    'javascript:alert(1)',
    'ftp://example.com',
    'https://user:password@example.com',
    'https://example.com/a b',
    'https://example.com/\n',
    'https://example.com/\0'
  ])
    assert.equal(validOriginUrl(url), false)
  assert.equal(validUsername('a_b-c1'), true)
  assert.equal(validUsername('名字'), false)
  assert.equal(validUsername('ab'), false)
})
