function appendText(tokens, value) {
  if (!value) return
  const last = tokens[tokens.length - 1]
  if (last?.type === 'text') last.text += value
  else tokens.push({ type: 'text', text: value })
}

function closingBackticks(source, marker, from) {
  let index = source.indexOf(marker, from)
  while (index !== -1) {
    if (source[index - 1] !== '`' && source[index + marker.length] !== '`') return index
    index = source.indexOf(marker, index + marker.length)
  }
  return -1
}

/** Unrecognized inline syntax, including links and HTML, remains literal text. */
export function agentMarkdownInline(source, depth = 0) {
  if (depth >= 10) return [{ type: 'text', text: source }]
  const tokens = []
  let index = 0
  while (index < source.length) {
    const char = source[index]
    if (char === '\n') {
      tokens.push({ type: 'break' })
      index += 1
      continue
    }
    if (char === '\\' && /[\\`*{}[\]()#+\-.!_|>]/.test(source[index + 1] || '')) {
      appendText(tokens, source[index + 1])
      index += 2
      continue
    }
    if (char === '`') {
      const marker = source.slice(index).match(/^`+/)[0]
      const end = closingBackticks(source, marker, index + marker.length)
      if (end !== -1) {
        tokens.push({ type: 'code', text: source.slice(index + marker.length, end) })
        index = end + marker.length
        continue
      }
      appendText(tokens, marker)
      index += marker.length
      continue
    }
    if (source.startsWith('**', index)) {
      let end = index + 2
      while (end < source.length) {
        if (source[end] === '\\') {
          end += 2
          continue
        }
        if (source[end] === '`') {
          const marker = source.slice(end).match(/^`+/)[0]
          const codeEnd = closingBackticks(source, marker, end + marker.length)
          if (codeEnd !== -1) {
            end = codeEnd + marker.length
            continue
          }
        }
        if (source.startsWith('**', end)) break
        end += 1
      }
      if (end > index + 2 && end < source.length) {
        tokens.push({
          type: 'strong',
          children: agentMarkdownInline(source.slice(index + 2, end), depth + 1)
        })
        index = end + 2
        continue
      }
    }
    appendText(tokens, char)
    index += 1
  }
  return tokens
}

function heading(line) {
  const match = line.match(/^ {0,3}(#{1,4})[ \t]+(.*)$/)
  return (
    match && {
      level: Math.min(5, match[1].length + 2),
      text: match[2].replace(/[ \t]+#+[ \t]*$/, '')
    }
  )
}

function listItem(line) {
  const match = line.match(/^( {0,3})(?:([-+*])|(\d{1,9})[.)、])[ \t]+(.*)$/)
  return (
    match && {
      indent: match[1].length,
      ordered: Boolean(match[3]),
      value: match[3] ? Number(match[3]) : null,
      text: match[4]
    }
  )
}

function openingFence(line) {
  const match = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/)
  if (!match || (match[1][0] === '`' && match[2].includes('`'))) return null
  return { marker: match[1], language: match[2].trim() }
}

function tableCells(line) {
  const source = line.trim()
  const cells = []
  let current = ''
  let codeMarker = ''
  let pipes = 0
  let index = 0
  while (index < source.length) {
    if (source[index] === '\\' && index + 1 < source.length) {
      current += source.slice(index, index + 2)
      index += 2
      continue
    }
    if (source[index] === '`') {
      const marker = source.slice(index).match(/^`+/)[0]
      if (!codeMarker) codeMarker = marker
      else if (codeMarker === marker) codeMarker = ''
      current += marker
      index += marker.length
      continue
    }
    if (source[index] === '|' && !codeMarker) {
      cells.push(current.trim())
      current = ''
      pipes += 1
    } else current += source[index]
    index += 1
  }
  if (!pipes) return null
  cells.push(current.trim())
  if (cells[0] === '' && source.startsWith('|')) cells.shift()
  if (cells[cells.length - 1] === '' && source.endsWith('|')) cells.pop()
  return cells
}

function tableHeader(lines, index) {
  if (index + 1 >= lines.length) return null
  const cells = tableCells(lines[index])
  const separators = tableCells(lines[index + 1])
  if (
    !cells?.length ||
    cells.length !== separators?.length ||
    !separators.every((cell) => /^:?-{3,}:?$/.test(cell))
  )
    return null
  return {
    cells,
    align: separators.map((cell) =>
      cell.startsWith(':') && cell.endsWith(':') ? 'center' : cell.endsWith(':') ? 'right' : 'left'
    )
  }
}

function isBlockStart(lines, index) {
  return (
    !lines[index].trim() ||
    heading(lines[index]) ||
    listItem(lines[index]) ||
    openingFence(lines[index]) ||
    /^ {0,3}>/.test(lines[index]) ||
    tableHeader(lines, index)
  )
}

function parseBlocks(lines, depth) {
  if (depth >= 10) return [{ type: 'paragraph', children: agentMarkdownInline(lines.join('\n')) }]
  const blocks = []
  let index = 0
  while (index < lines.length) {
    if (!lines[index].trim()) {
      index += 1
      continue
    }
    const fence = openingFence(lines[index])
    if (fence) {
      const content = []
      index += 1
      while (index < lines.length) {
        const close = lines[index].match(/^ {0,3}(`+|~+)[ \t]*$/)
        if (close && close[1][0] === fence.marker[0] && close[1].length >= fence.marker.length) {
          index += 1
          break
        }
        content.push(lines[index])
        index += 1
      }
      blocks.push({ type: 'code', text: content.join('\n'), language: fence.language })
      continue
    }
    const title = heading(lines[index])
    if (title) {
      blocks.push({
        type: 'heading',
        level: title.level,
        children: agentMarkdownInline(title.text)
      })
      index += 1
      continue
    }
    const header = tableHeader(lines, index)
    if (header) {
      const rows = []
      index += 2
      while (index < lines.length && lines[index].trim()) {
        const cells = tableCells(lines[index])
        if (!cells || cells.length !== header.cells.length) break
        rows.push(cells.map((cell) => agentMarkdownInline(cell)))
        index += 1
      }
      blocks.push({
        type: 'table',
        header: header.cells.map((cell) => agentMarkdownInline(cell)),
        align: header.align,
        rows
      })
      continue
    }
    if (/^ {0,3}>/.test(lines[index])) {
      const content = []
      while (index < lines.length && /^ {0,3}>/.test(lines[index])) {
        content.push(lines[index].replace(/^ {0,3}> ?/, ''))
        index += 1
      }
      blocks.push({ type: 'quote', children: parseBlocks(content, depth + 1) })
      continue
    }
    const firstItem = listItem(lines[index])
    if (firstItem) {
      const items = []
      while (index < lines.length) {
        const item = listItem(lines[index])
        if (!item || item.ordered !== firstItem.ordered || item.indent !== firstItem.indent) break
        const content = [item.text]
        index += 1
        while (
          index < lines.length &&
          lines[index].trim() &&
          /^ +/.test(lines[index]) &&
          lines[index].match(/^ */)[0].length > item.indent
        ) {
          content.push(lines[index].trimStart())
          index += 1
        }
        items.push({ value: item.value, children: agentMarkdownInline(content.join('\n')) })
      }
      blocks.push({ type: 'list', ordered: firstItem.ordered, start: firstItem.value, items })
      continue
    }
    const paragraph = [lines[index]]
    index += 1
    while (index < lines.length && !isBlockStart(lines, index)) {
      paragraph.push(lines[index])
      index += 1
    }
    blocks.push({ type: 'paragraph', children: agentMarkdownInline(paragraph.join('\n')) })
  }
  return blocks
}

export function parseAgentMarkdown(value) {
  const source = typeof value === 'string' ? value : ''
  return parseBlocks(source.replace(/\r\n?/g, '\n').split('\n'), 0)
}
