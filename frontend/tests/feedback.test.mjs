import test from 'node:test'
import assert from 'node:assert/strict'
import { mount, findAll, click, update, submit } from './helpers/vueComponent.mjs'

test('feedback validates empty input and submits content without caller identity', async t => {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, options) => { calls.push({url, body:JSON.parse(options.body)}); return {ok:true} })
  const app = await mount('../../src/FeedbackPanel.vue', {})
  t.after(app.unmount)
  await click(findAll(app.root, 'button')[0])
  await submit(findAll(app.root, 'form')[0])
  assert.equal(calls.length, 0)
  await update(findAll(app.root, 'ElInput')[0], '  建议  ')
  await submit(findAll(app.root, 'form')[0])
  assert.deepEqual(calls, [{url:'/api/developer/feedback',body:{content:'建议'}}])
  assert.equal(findAll(app.root, 'ElAlert')[0].props.type, 'success')
})
test('administrator feedback list loads its protected endpoint and shows errors', async t => {
  t.mock.method(globalThis, 'fetch', async url => { assert.equal(url, '/api/admin/feedback?page=0'); return {ok:false} })
  const app = await mount('../../src/FeedbackPanel.vue', {admin:true})
  t.after(app.unmount)
  assert.match(findAll(app.root,'ElAlert')[0].props.title, /管理员/)
})
