/** Handles SSE line endings and a provider's final unterminated event. */
export async function readChatEvents(response: Response, apply: (event: any) => void) {
  if (!response.body) throw new Error('浏览器不支持流式响应')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const dispatch = (block: string) => {
    const data = block.split(/\r\n|\r|\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
    if (data.trim() && data.trim() !== '[DONE]') apply(JSON.parse(data))
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      let match: RegExpExecArray | null
      while ((match = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
        dispatch(buffer.slice(0, match.index))
        buffer = buffer.slice(match.index + match[0].length)
      }
      if (done) { dispatch(buffer); break }
    }
  } finally { reader.releaseLock() }
}
