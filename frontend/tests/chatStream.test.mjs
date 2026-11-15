import test from 'node:test'
import assert from 'node:assert/strict'
import { readChatEvents } from '../src/chatStream.ts'

test('decodes fragmented CRLF, UTF-8 and final unterminated events', async () => {
  const bytes = new TextEncoder().encode(': ping\r\n\r\ndata: {"type":"delta","content":"你好"}\r\n\r\ndata: [DONE]\r\n\r\ndata: {"type":"error","message":"失败"}')
  const stream = new ReadableStream({ start(controller) { for (const b of bytes) controller.enqueue(new Uint8Array([b])); controller.close() } })
  const events = []
  await readChatEvents(new Response(stream), event => events.push(event))
  assert.deepEqual(events, [{type:'delta',content:'你好'}, {type:'error',message:'失败'}])
})
test('supports LF streams and rejects malformed data instead of silently dropping it', async () => {
  const events = []
  await readChatEvents(new Response('data: {"type":"done"}\n\n'), event => events.push(event))
  assert.equal(events[0].type, 'done')
  await assert.rejects(readChatEvents(new Response('data: not-json'), () => {}))
})
