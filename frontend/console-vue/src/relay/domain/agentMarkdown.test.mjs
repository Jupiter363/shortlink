import assert from 'node:assert/strict'
import test from 'node:test'
import { agentMarkdownInline, parseAgentMarkdown } from './agentMarkdown.js'

function inlineText(tokens) {
  return tokens
    .map((token) =>
      token.type === 'break'
        ? '\n'
        : token.type === 'strong'
          ? inlineText(token.children)
          : token.text
    )
    .join('')
}

test('analysis answers retain paragraphs, numbered recommendations, emphasis and inline identifiers', () => {
  const blocks = parseAgentMarkdown(
    [
      '## 风险分析结果',
      '',
      '总体风险为**高风险**，建议先核验。',
      '',
      '1. **可疑请求**：检查异常访问来源。',
      '2. 检查 `policyId` 对应的策略。',
      '',
      '后续处置应以实际核验结果为准。'
    ].join('\n')
  )
  assert.deepEqual(
    blocks.map((block) => block.type),
    ['heading', 'paragraph', 'list', 'paragraph']
  )
  assert.equal(blocks[0].level, 4)
  assert.equal(inlineText(blocks[0].children), '风险分析结果')
  assert.deepEqual(
    blocks[1].children.find((token) => token.type === 'strong'),
    { type: 'strong', children: [{ type: 'text', text: '高风险' }] }
  )
  assert.equal(blocks[2].ordered, true)
  assert.deepEqual(
    blocks[2].items.map((item) => item.value),
    [1, 2]
  )
  assert.deepEqual(
    blocks[2].items[1].children.find((token) => token.type === 'code'),
    { type: 'code', text: 'policyId' }
  )
  assert.equal(inlineText(blocks[3].children), '后续处置应以实际核验结果为准。')
})

test('HTML, script tags and unsafe links remain text rather than executable token types', () => {
  const html = '<script>alert("x")</script>\n<img src=x onerror=alert(1)>'
  const links =
    '[危险](javascript:alert(1))\n[数据](data:text/html,<script>x</script>)\n[普通](https://example.test/path)\n![图片](javascript:alert(2))'
  const blocks = parseAgentMarkdown(`${html}\n\n${links}`)
  assert.equal(blocks.length, 2)
  assert.equal(inlineText(blocks[0].children), html)
  assert.equal(inlineText(blocks[1].children), links)
  for (const block of blocks) {
    assert.equal(block.type, 'paragraph')
    assert.ok(block.children.every((token) => token.type === 'text' || token.type === 'break'))
  }
})

test('fenced and inline code preserve literal markup without parsing emphasis or HTML inside it', () => {
  const blocks = parseAgentMarkdown(
    '````html\n<script>**literal**</script>\n```\n[link](javascript:alert(1))\n````\n\nUse ``a ` b``.'
  )
  assert.equal(blocks[0].type, 'code')
  assert.equal(blocks[0].language, 'html')
  assert.equal(blocks[0].text, '<script>**literal**</script>\n```\n[link](javascript:alert(1))')
  assert.deepEqual(
    blocks[1].children.find((token) => token.type === 'code'),
    { type: 'code', text: 'a ` b' }
  )
  assert.equal(inlineText(blocks[1].children), 'Use a ` b.')
})

test('simple tables retain aligned columns, escaped pipes and code pipes without discarding extra rows', () => {
  const blocks = parseAgentMarkdown(
    '| 维度 | 数值 |\n| :--- | ---: |\n| **来源** | `a|b` |\n| A\\|B | 10 |\n| 多余 | 数据 | 保留 |'
  )
  assert.equal(blocks[0].type, 'table')
  assert.deepEqual(blocks[0].align, ['left', 'right'])
  assert.deepEqual(blocks[0].header.map(inlineText), ['维度', '数值'])
  assert.deepEqual(
    blocks[0].rows.map((row) => row.map(inlineText)),
    [
      ['来源', 'a|b'],
      ['A|B', '10']
    ]
  )
  assert.equal(blocks[1].type, 'paragraph')
  assert.equal(inlineText(blocks[1].children), '| 多余 | 数据 | 保留 |')
})

test('unsupported or incomplete syntax remains visible instead of silently disappearing', () => {
  const source =
    '##### 未支持的标题深度\n*斜体* ~~删除线~~ __下划线__\n未闭合 **加粗 和 `code\n---\n#没有空格'
  const blocks = parseAgentMarkdown(source)
  assert.equal(blocks.length, 1)
  assert.equal(blocks[0].type, 'paragraph')
  assert.equal(inlineText(blocks[0].children), source)
  assert.deepEqual(agentMarkdownInline('\\*字面星号\\*'), [{ type: 'text', text: '*字面星号*' }])
})

test('ordered lists preserve nonsequential source numbers and unordered lists remain separate', () => {
  const blocks = parseAgentMarkdown('3. 第一项\n7) 原始编号七\n\n- **检查**证据\n- 后续处理')
  assert.equal(blocks[0].start, 3)
  assert.deepEqual(
    blocks[0].items.map((item) => item.value),
    [3, 7]
  )
  assert.equal(blocks[1].ordered, false)
  assert.deepEqual(
    blocks[1].items.map((item) => inlineText(item.children)),
    ['检查证据', '后续处理']
  )
})

test('headings stay within result-page hierarchy and quoted paragraphs remain structured', () => {
  const blocks = parseAgentMarkdown(
    '# 一级\n## 二级\n### 三级\n#### 四级\n\n> **说明**\n>\n> 第二段\n> - 引用事项'
  )
  assert.deepEqual(
    blocks.slice(0, 4).map((block) => block.level),
    [3, 4, 5, 5]
  )
  assert.equal(blocks[4].type, 'quote')
  assert.deepEqual(
    blocks[4].children.map((block) => block.type),
    ['paragraph', 'paragraph', 'list']
  )
  assert.equal(inlineText(blocks[4].children[1].children), '第二段')
})

test('empty answers invent no content and an unfinished code fence retains its remaining text', () => {
  for (const value of ['', null, undefined, '\n  \n'])
    assert.deepEqual(parseAgentMarkdown(value), [])
  const blocks = parseAgentMarkdown('~~~text\r\n<raw>\r\n**not bold**')
  assert.deepEqual(blocks, [{ type: 'code', text: '<raw>\n**not bold**', language: 'text' }])
})
