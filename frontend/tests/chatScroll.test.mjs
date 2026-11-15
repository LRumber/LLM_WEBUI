import test from 'node:test'
import assert from 'node:assert/strict'
import { isChatAtBottom } from '../src/chatScroll.ts'

test('bottom position does not depend on old scroll direction or follow mode', () => {
  assert.equal(isChatAtBottom({scrollHeight:1000,clientHeight:500,scrollTop:500}), true)
  assert.equal(isChatAtBottom({scrollHeight:1000,clientHeight:500,scrollTop:499.5}), true)
  assert.equal(isChatAtBottom({scrollHeight:100,clientHeight:500,scrollTop:0}), true)
  assert.equal(isChatAtBottom({scrollHeight:1000,clientHeight:500,scrollTop:400}), false)
})
test('new output becomes unread when user has paused following', () => {
  const area = {scrollHeight:1000,clientHeight:500,scrollTop:500}
  assert.equal(isChatAtBottom(area), true)
  area.scrollHeight += 100
  assert.equal(isChatAtBottom(area), false)
  area.scrollTop = 600
  assert.equal(isChatAtBottom(area), true)
})
