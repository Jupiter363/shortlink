<script>
import { defineComponent, h } from 'vue'
import { parseAgentMarkdown } from '../domain/agentMarkdown.js'

function inline(tokens) {
  return tokens.map((token) => {
    if (token.type === 'strong') return h('strong', inline(token.children))
    if (token.type === 'code') return h('code', token.text)
    if (token.type === 'break') return h('br')
    return token.text
  })
}

function blocks(tokens) {
  return tokens.map((token) => {
    if (token.type === 'heading') return h(`h${token.level}`, inline(token.children))
    if (token.type === 'code') return h('pre', { class: 'ap-answer-code' }, [h('code', token.text)])
    if (token.type === 'quote') return h('blockquote', blocks(token.children))
    if (token.type === 'list') {
      return h(
        token.ordered ? 'ol' : 'ul',
        token.ordered ? { start: token.start } : {},
        token.items.map((item) =>
          h('li', token.ordered ? { value: item.value } : {}, inline(item.children))
        )
      )
    }
    if (token.type === 'table') {
      return h(
        'div',
        {
          class: 'ap-answer-table-wrap',
          tabindex: 0,
          role: 'region',
          'aria-label': '分析结果表格，可横向滚动'
        },
        [
          h('table', [
            h('thead', [
              h(
                'tr',
                token.header.map((cell, index) =>
                  h('th', { scope: 'col', style: { textAlign: token.align[index] } }, inline(cell))
                )
              )
            ]),
            h(
              'tbody',
              token.rows.map((row) =>
                h(
                  'tr',
                  row.map((cell, index) =>
                    h('td', { style: { textAlign: token.align[index] } }, inline(cell))
                  )
                )
              )
            )
          ])
        ]
      )
    }
    return h('p', inline(token.children))
  })
}

export default defineComponent({
  name: 'AgentAnswer',
  props: { text: { type: String, default: '' } },
  setup(props) {
    return () => h('div', { class: 'ap-answer-markdown' }, blocks(parseAgentMarkdown(props.text)))
  }
})
</script>
