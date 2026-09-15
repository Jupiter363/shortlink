import assert from 'node:assert/strict'
import test from 'node:test'
import { agentHistoryEntries, buildAgentReport } from './agentWorkspace.js'

test('completed snapshots appear newest first with stable history keys and do not mutate inputs', () => {
  const session = {
    runState: 'SUCCESS',
    result: { answer: 'latest', cards: [{ title: 'current evidence' }] },
    lastPrompt: 'current question',
    completedAt: '2026-09-15 14:00',
    history: [
      { result: { answer: 'first' }, prompt: 'first question', completedAt: '2026-09-14 10:00' },
      { result: { answer: 'second' }, prompt: 'second question', completedAt: '2026-09-15 10:00' }
    ]
  }
  const original = structuredClone(session)
  const entries = agentHistoryEntries(session)
  assert.deepEqual(
    entries.map((entry) => entry.key),
    ['current', 'history-1', 'history-0']
  )
  assert.deepEqual(
    entries.map((entry) => entry.result.answer),
    ['latest', 'second', 'first']
  )
  assert.equal(entries[0].label, '当前完整结果')
  assert.ok(Object.isFrozen(entries))
  assert.ok(Object.isFrozen(entries[0].result.cards[0]))
  assert.notEqual(entries[0].result, session.result)
  assert.throws(() => entries.reverse(), TypeError)
  assert.throws(() => {
    entries[0].result.answer = 'changed'
  }, TypeError)
  assert.deepEqual(session, original)
})

test('failed and running sessions retain the last complete result without calling it current', () => {
  for (const runState of ['ERROR', 'RUNNING']) {
    const [entry] = agentHistoryEntries({ runState, result: { answer: 'retained answer' } })
    assert.equal(entry.label, '上一次完整结果')
    assert.equal(entry.result.answer, 'retained answer')
    assert.match(buildAgentReport('Agent 报告', entry), /> 上一次完整结果/)
  }
})

test('missing results never become history entries and skipped entries do not renumber keys', () => {
  for (const session of [null, {}, { result: null }, { history: [null, {}, { result: null }] }])
    assert.deepEqual(agentHistoryEntries(session), [])
  assert.deepEqual(
    agentHistoryEntries({ history: [{}, { result: { answer: 'kept' } }] }).map(
      (entry) => entry.key
    ),
    ['history-1']
  )
})

test('original prompts are preferred while older entries fall back to stored messages', () => {
  const entries = agentHistoryEntries({
    runState: 'SUCCESS',
    result: { answer: '' },
    lastPrompt: '  human prompt  ',
    lastMessage: 'compiled current message',
    history: [
      { result: { answer: '' }, prompt: '', message: 'legacy message' },
      { result: { answer: '' }, prompt: 'history prompt', message: 'compiled history message' }
    ]
  })
  assert.deepEqual(
    entries.map((entry) => entry.message),
    ['human prompt', 'history prompt', 'legacy message']
  )
  assert.ok(entries.every((entry) => entry.scopeLabel === ''))
})

test('completed scope snapshots survive selection changes and remain redacted in exported reports', () => {
  const session = {
    runState: 'SUCCESS',
    groupId: 'currently-selected',
    result: { answer: 'current answer' },
    lastPrompt: '分析当前数据',
    lastScopeLabel: '  分组：上次完成组 token=CURRENT_SCOPE_PRIVATE  ',
    history: [
      {
        result: { answer: 'historical answer' },
        prompt: '分析历史数据',
        scopeLabel: '分组：历史组 Bearer HISTORY_SCOPE_PRIVATE'
      }
    ]
  }
  const before = agentHistoryEntries(session)
  session.groupId = 'different-selection'
  session.selectedGroup = { name: '尚未执行的新分组' }
  const after = agentHistoryEntries(session)
  assert.equal(after[0].scopeLabel, before[0].scopeLabel)
  assert.equal(after[1].scopeLabel, before[1].scopeLabel)
  assert.equal(after[0].scopeLabel, '分组：上次完成组 token=[已脱敏]')
  assert.equal(after[1].scopeLabel, '分组：历史组 Bearer [已脱敏]')
  const report = buildAgentReport('历史报告', after[1])
  assert.match(report, /分析范围：分组：历史组 Bearer \[已脱敏\]/)
  assert.doesNotMatch(report, /PRIVATE|尚未执行的新分组|different-selection/)
  assert.match(
    buildAgentReport('旧报告', { result: { answer: '旧结果' } }),
    /分析范围：未单独记录，请核对原始问题/
  )
})

test('reports whitelist visible fields and redact private values even inside diagnostic sections', () => {
  const report = buildAgentReport('安全 token=TITLE_PRIVATE', {
    label: '当前完整结果',
    message: '检查 Bearer MESSAGE_PRIVATE 和 203.0.113.42',
    completedAt: '2026-09-15 14:00',
    id: 'SESSION_PRIVATE',
    runId: 'RUN_PRIVATE',
    controller: { signal: 'CONTROLLER_PRIVATE' },
    unexpected: 'ENTRY_EXTRA_PRIVATE',
    result: {
      answer: '访问 2001:db8:1234:5678::1，apiKey=ANSWER_PRIVATE',
      cards: [{ title: 'card marker', visitorId: 'VISITOR_PRIVATE', ip: '192.0.2.99' }],
      warnings: ['warning marker sk-1234567890abcdefghijkl'],
      dataSources: [{ source: 'source marker', headers: { cookie: 'COOKIE_PRIVATE' } }],
      toolCalls: [
        {
          name: 'tool marker',
          apiKey: 'KEY_PRIVATE',
          raw: 'RAW_PRIVATE',
          runId: 'NESTED_RUN_PRIVATE'
        }
      ],
      traceEvents: [
        {
          nodeName: 'trace marker',
          token: 'TOKEN_PRIVATE',
          controller: 'NESTED_CONTROLLER_PRIVATE'
        }
      ],
      pendingActions: [{ description: 'pending marker', password: 'PASSWORD_PRIVATE' }],
      sessionId: 'RESULT_SESSION_PRIVATE',
      id: 'RESULT_ID_PRIVATE',
      raw: 'RESULT_RAW_PRIVATE',
      other: 'RESULT_EXTRA_PRIVATE'
    }
  })
  for (const marker of [
    'card marker',
    'warning marker',
    'source marker',
    'tool marker',
    'trace marker',
    'pending marker'
  ])
    assert.ok(report.includes(marker), marker)
  assert.match(report, /\[已脱敏\]/)
  assert.match(report, /203\.0\.\*\.\*/)
  assert.match(report, /192\.0\.\*\.\*/)
  assert.match(report, /2001:db8:\*\*\*\*/)
  assert.doesNotMatch(report, /[A-Z_]+PRIVATE|1234567890abcdefghijkl/)
  assert.doesNotMatch(report, /"(?:runId|sessionId|controller|raw)"/)
})

test('evidence identifiers remain exportable while root and nested runtime metadata stay excluded', () => {
  const report = buildAgentReport('证据核验', {
    id: 'ENTRY_RUNTIME_PRIVATE',
    result: {
      id: 'RESULT_RUNTIME_PRIVATE',
      answer: '按证据标识核验结果。',
      cards: [
        { id: 'evidence-17', snapshotId: 'snapshot-21', sessionId: 'NESTED_SESSION_PRIVATE' }
      ],
      toolCalls: [{ id: 'tool-23', traceId: 'trace-29', runId: 'NESTED_RUN_PRIVATE' }],
      traceEvents: [{ id: 'node-31', traceId: 'trace-29', snapshotId: 'snapshot-21' }]
    }
  })
  for (const identifier of ['evidence-17', 'snapshot-21', 'tool-23', 'trace-29', 'node-31'])
    assert.ok(report.includes(identifier), identifier)
  assert.match(report, /"id": "evidence-17"/)
  assert.match(report, /"traceId": "trace-29"/)
  assert.match(report, /"snapshotId": "snapshot-21"/)
  assert.doesNotMatch(report, /[A-Z_]+PRIVATE/)
  assert.doesNotMatch(report, /"(?:sessionId|runId)"/)
})

test('missing report sections explicitly stay missing instead of inventing successful findings', () => {
  const report = buildAgentReport('', { result: { answer: '   ', cards: [], warnings: null } })
  assert.match(report, /^# Agent 分析报告/m)
  for (const missing of [
    '未提供完成时间',
    '未提供原始问题',
    '未提供分析正文',
    '未提供分析证据',
    '未提供提示',
    '未提供数据来源',
    '未提供工具调用',
    '未提供执行轨迹',
    '未提供待处理事项'
  ])
    assert.ok(report.includes(missing), missing)
  assert.doesNotMatch(report, /undefined|null|无风险|已完成/)
})

test('JSON code fences remain intact when returned content contains longer backtick runs', () => {
  const ticks = '`'.repeat(7)
  const report = buildAgentReport('围栏验证', {
    result: { answer: '正文', toolCalls: [{ note: `${ticks}json\nunsafe-looking text\n${ticks}` }] }
  })
  const opening = report.match(/^(`{4,})json$/m)
  assert.ok(opening)
  assert.equal(opening[1].length, 8)
  assert.ok(report.includes(`\n${opening[1]}\n`))
  assert.match(report, /unsafe-looking text/)
})
