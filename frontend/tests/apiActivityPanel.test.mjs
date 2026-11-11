import assert from 'node:assert/strict'
import test from 'node:test'
import { mount, findAll, text, update, click, submit, settle } from './helpers/vueComponent.mjs'

const panel = '../../src/ApiActivityPanel.vue'
const row = { id: 'request-1', userId: 'u1', displayName: 'Alice', userNumber: '1001', type: 'tokens', requestedTokens: 100, approvedTokens: 0, reason: 'Research', status: 'pending', reviewNote: '', reviewer: '', createdAt: '2026-11-11T00:00:00Z', reviewedAt: '' }
const log = { requestId: 'trace-1', displayName: 'Alice', userNumber: '1001', keyName: 'Project', maskedKey: 'sk-***', model: 'model/a', endpoint: '/v1/chat/completions', stream: true, httpStatus: 502, upstreamStatus: 500, status: 'failed', errorCode: 'upstream', errorMessage: 'Unavailable', detail: 'ADMIN_DIAGNOSTIC', inputTokens: 10, outputTokens: 0, estimated: true, durationMs: 80, createdAt: row.createdAt }
const byText = (root, type, label) => {
  const result = findAll(root, type).find(el => text(el) === label)
  assert.ok(result, `Missing ${type}: ${label}`)
  return result
}
function mockApi(t, options = {}) {
  const calls = []
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    calls.push({ url: String(url), body: init?.body ? JSON.parse(init.body) : undefined })
    if (url === '/api/developer/summary') return { ok: true, json: async () => ({ quota: {}, models: [], dailyUsage: [] }) }
    if (url === '/api/developer/keys') return { ok: true, json: async () => [] }
    if (options.failGet?.(String(url))) throw new Error('Network unavailable')
    if (init?.method === 'POST') {
      if (options.post) return options.post(url, init)
      return { ok: true, json: async () => ({}) }
    }
    return { ok: true, json: async () => String(url).includes('call-logs')
      ? { items: [options.log ?? log], total: 45, page: 0, size: 20 }
      : { items: [options.row ?? row], total: 45, page: 0, size: 20, pendingCount: options.pending ?? 0 } }
  })
  return calls
}
async function setup(t, props, options) {
  const calls = mockApi(t, options)
  const mounted = await mount(panel, props)
  t.after(mounted.unmount)
  return { ...mounted, calls }
}
const posts = calls => calls.filter(c => c.body)

test('admin call logs show a fallback for unattributed users and retain known identity', async t => {
  for (const [displayName, userNumber, expected] of [
    [null, null, '未识别用户'], ['Alice', '1001', 'Alice'], [null, '1001', '1001'],
  ]) await t.test(expected, async t => {
    const { root } = await setup(t, { mode: 'admin', initialTab: 'logs' }, {
      log: { ...log, displayName, userNumber },
    })
    const column = findAll(root, 'ElTableColumn').find(el => el.props.prop === 'displayName')
    assert.equal(text(column), expected)
  })
})

test('credit form validates reason and positive safe-integer tokens, and normalizes unlimited', async t => {
  const { root, calls } = await setup(t, { mode: 'user' })
  const form = () => findAll(root, 'ElForm')[0]
  const reason = () => findAll(root, 'ElInput')[0]
  await submit(form())
  assert.equal(posts(calls).length, 0)
  assert.match(findAll(root, 'ElAlert')[0].props.title, /原因/)
  await update(reason(), 'Research')
  for (const invalid of [0, -1, 1.5, NaN, Number.MAX_SAFE_INTEGER + 1]) {
    await update(findAll(root, 'ElInputNumber')[0], invalid)
    await submit(form())
  }
  assert.equal(posts(calls).length, 0)
  await update(findAll(root, 'ElInputNumber')[0], 123)
  await update(reason(), 'x'.repeat(1001))
  await submit(form())
  assert.equal(posts(calls).length, 0)
  await update(reason(), '  Research  ')
  await submit(form())
  assert.deepEqual(posts(calls)[0], { url: '/api/developer/credit-requests', body: { type: 'tokens', amount: 123, reason: 'Research' } })
  await update(findAll(root, 'ElSelect')[0], 'unlimited')
  assert.equal(findAll(root, 'ElInputNumber').length, 0)
  await update(reason(), 'Unlimited research')
  await submit(form())
  assert.deepEqual(posts(calls)[1].body, { type: 'unlimited', amount: 0, reason: 'Unlimited research' })
})

test('pending requests block duplicates and submission stays locked while the POST is pending', async t => {
  let resolvePost
  const { root, calls } = await setup(t, { mode: 'user' }, {
    post: () => new Promise(resolve => { resolvePost = resolve }),
  })
  await update(findAll(root, 'ElInput')[0], 'Research')
  await submit(findAll(root, 'ElForm')[0])
  assert.equal(byText(root, 'ElButton', '提交申请').props.loading, true)
  await submit(findAll(root, 'ElForm')[0])
  assert.equal(posts(calls).length, 1)
  resolvePost({ ok: true, json: async () => ({}) }); await settle()
})

test('existing pending request disables application and business errors remain visible', async t => {
  await t.test('pending', async t => {
    const { root, calls } = await setup(t, { mode: 'user' }, { pending: 1 })
    assert.equal(byText(root, 'ElButton', '提交申请').props.disabled, true)
    await update(findAll(root, 'ElInput')[0], 'Research')
    await submit(findAll(root, 'ElForm')[0])
    assert.equal(posts(calls).length, 0)
  })
  await t.test('business validation', async t => {
    const { root } = await setup(t, { mode: 'user' }, {
      post: async () => ({ ok: false, json: async () => ({ error: { message: '已有待审批申请' } }) }),
    })
    await update(findAll(root, 'ElInput')[0], 'Research')
    await submit(findAll(root, 'ElForm')[0])
    assert.equal(findAll(root, 'ElAlert')[0].props.title, '已有待审批申请')
    assert.equal(byText(root, 'ElButton', '提交申请').props.loading, false)
  })
})

test('admin approval prefills amount, validates adjustments, bounds notes and posts once', async t => {
  let resolvePost
  const { root, calls } = await setup(t, { mode: 'admin' }, {
    post: () => new Promise(resolve => { resolvePost = resolve }),
  })
  assert.equal(findAll(root, 'ElInputNumber').length, 0)
  await click(byText(root, 'ElButton', '审批'))
  assert.equal(findAll(root, 'ElInputNumber')[0].props.modelValue, 100)
  const dialog = findAll(root, 'ElDialog')[0]
  assert.equal(dialog.props['close-on-click-modal'], false)
  const reviewForm = () => findAll(dialog, 'ElForm')[0]
  for (const invalid of [0, -1, 2.5, Number.MAX_SAFE_INTEGER + 1]) {
    await update(findAll(dialog, 'ElInputNumber')[0], invalid); await submit(reviewForm())
  }
  assert.equal(posts(calls).length, 0)
  await update(findAll(dialog, 'ElInputNumber')[0], 250)
  await update(findAll(dialog, 'ElInput')[0], 'x'.repeat(1001)); await submit(reviewForm())
  assert.equal(posts(calls).length, 0)
  await update(findAll(dialog, 'ElInput')[0], '  Approved  ')
  await submit(reviewForm()); await submit(reviewForm())
  assert.deepEqual(posts(calls), [{ url: '/api/admin/api/credit-requests/request-1/review', body: { approved: true, approvedTokens: 250, note: 'Approved' } }])
  assert.equal(dialog.props['close-on-press-escape'], false)
  assert.equal(byText(dialog, 'ElButton', '取消').props.disabled, true)
  resolvePost({ ok: true, json: async () => ({}) }); await settle()
  assert.equal(findAll(dialog, 'ElForm').length, 0)
})

test('rejecting a token request sends zero tokens even if the amount is invalid', async t => {
  const { root, calls } = await setup(t, { mode: 'admin' })
  await click(byText(root, 'ElButton', '审批'))
  const dialog = findAll(root, 'ElDialog')[0]
  await update(findAll(dialog, 'ElInputNumber')[0], -1)
  await update(findAll(dialog, 'ElRadioGroup')[0], false)
  assert.equal(findAll(dialog, 'ElInputNumber').length, 0)
  await submit(findAll(dialog, 'ElForm')[0])
  assert.deepEqual(posts(calls)[0].body, { approved: false, approvedTokens: 0, note: '' })
})

test('unlimited approval has no amount field and sends zero tokens', async t => {
  const { root, calls } = await setup(t, { mode: 'admin' }, { row: { ...row, type: 'unlimited', requestedTokens: 0 } })
  await click(byText(root, 'ElButton', '审批'))
  const dialog = findAll(root, 'ElDialog')[0]
  assert.equal(findAll(dialog, 'ElInputNumber').length, 0)
  assert.ok(text(dialog).includes('批准后额度为无限制'))
  await submit(findAll(dialog, 'ElForm')[0])
  assert.deepEqual(posts(calls)[0].body, { approved: true, approvedTokens: 0, note: '' })
})

test('request history paginates with its status and resets page/status on tab change', async t => {
  const { root, calls } = await setup(t, { mode: 'admin' })
  await update(findAll(root, 'ElSelect')[0], 'approved')
  await submit(findAll(root, 'ElForm')[0])
  findAll(root, 'ElPagination')[0].props.onCurrentChange(2); await settle()
  let url = new URL(calls.at(-1).url, 'http://local')
  assert.equal(url.pathname, '/api/admin/api/credit-requests')
  assert.equal(url.searchParams.get('status'), 'approved')
  assert.equal(url.searchParams.get('page'), '1')
  await update(findAll(root, 'ElTabs')[0], 'logs')
  url = new URL(calls.at(-1).url, 'http://local')
  assert.equal(url.pathname, '/api/admin/api/call-logs')
  assert.equal(url.searchParams.get('status'), 'all')
  assert.equal(url.searchParams.get('page'), '0')
})

test('log filters encode search/model/date/status, paginate zero-based and reset on search', async t => {
  const { root, calls } = await setup(t, { mode: 'user', initialTab: 'logs' })
  await update(findAll(root, 'ElSelect')[0], 'failed')
  await update(findAll(root, 'ElInput')[0], ' Alice & 1001 ')
  await update(findAll(root, 'ElInput')[1], ' model/a ')
  await update(findAll(root, 'ElDatePicker')[0], '2026-11-01')
  await update(findAll(root, 'ElDatePicker')[1], '2026-11-11')
  await submit(findAll(root, 'ElForm')[0])
  let url = new URL(calls.at(-1).url, 'http://local')
  assert.equal(url.pathname, '/api/developer/call-logs')
  assert.deepEqual(Object.fromEntries(url.searchParams), { page: '0', size: '20', status: 'failed', q: 'Alice & 1001', model: 'model/a', from: '2026-11-01', to: '2026-11-11' })
  const pagination = findAll(root, 'ElPagination')[0]
  assert.equal(pagination.props.total, 45)
  pagination.props.onCurrentChange(3); await settle()
  assert.equal(new URL(calls.at(-1).url, 'http://local').searchParams.get('page'), '2')
  assert.equal(pagination.props['current-page'], 3)
  await submit(findAll(root, 'ElForm')[0])
  assert.equal(pagination.props['current-page'], 1)
  assert.equal(new URL(calls.at(-1).url, 'http://local').searchParams.get('page'), '0')
  const count = calls.length
  await update(findAll(root, 'ElDatePicker')[0], '2026-12-01')
  await submit(findAll(root, 'ElForm')[0])
  assert.equal(calls.length, count)
  assert.match(findAll(root, 'ElAlert')[0].props.title, /开始日期/)
  await update(findAll(root, 'ElDatePicker')[0], null)
  await update(findAll(root, 'ElDatePicker')[1], null)
  await submit(findAll(root, 'ElForm')[0])
  url = new URL(calls.at(-1).url, 'http://local')
  assert.equal(url.searchParams.has('from'), false)
  assert.equal(url.searchParams.has('to'), false)
})

test('log identity and diagnostic details are admin-only even if the user payload includes detail', async t => {
  for (const mode of ['user', 'admin']) await t.test(mode, async t => {
    const { root, calls } = await setup(t, { mode, initialTab: 'logs' })
    assert.ok(calls.some(c => c.url.startsWith(mode === 'admin' ? '/api/admin/api/call-logs' : '/api/developer/call-logs')))
    const columns = findAll(root, 'ElTableColumn')
    assert.equal(columns.some(c => c.props.prop === 'userNumber'), mode === 'admin')
    assert.equal(columns.some(c => c.props.prop === 'displayName'), mode === 'admin')
    assert.equal(columns.some(c => c.props.prop === 'maskedKey'), true)
    assert.equal(columns.some(c => c.props.prop === 'secret' || c.props.prop === 'prompt'), false)
    await click(byText(root, 'ElButton', '详情'))
    assert.equal(text(root).includes('ADMIN_DIAGNOSTIC'), mode === 'admin')
    assert.ok(text(root).includes('trace-1'))
  })
})

test('front sidebar and internal tabs stay synchronized in both directions', async t => {
  mockApi(t)
  const { root, unmount } = await mount('../../src/ApiPlatformPage.vue')
  t.after(unmount)
  const sidebar = () => findAll(root, 'aside')[0]
  await click(byText(sidebar(), 'button', '额度申请'))
  assert.equal(byText(sidebar(), 'button', '额度申请').props.class, 'active')
  await update(findAll(root, 'ElTabs')[0], 'logs')
  assert.equal(byText(sidebar(), 'button', '调用记录').props.class, 'active')
  assert.equal(findAll(root, 'ElTabs')[0].props.modelValue, 'logs')
  await update(findAll(root, 'ElTabs')[0], 'requests')
  assert.equal(byText(sidebar(), 'button', '额度申请').props.class, 'active')
  await click(byText(sidebar(), 'button', '调用记录'))
  assert.equal(findAll(root, 'ElTabs')[0].props.modelValue, 'logs')
})

test('failed request refresh invalidates pending state and retry restores submission', async t => {
  let fail = false
  const { root, calls } = await setup(t, { mode: 'user' }, { failGet: () => fail })
  const refresh = () => findAll(root, 'ElButton').find(el => el.props.title === '刷新')
  assert.equal(byText(root, 'ElButton', '提交申请').props.disabled, false)
  fail = true
  await click(refresh())
  assert.equal(byText(root, 'ElButton', '提交申请').props.disabled, true)
  assert.equal(findAll(root, 'ElAlert')[0].props.title, 'Network unavailable')
  await update(findAll(root, 'ElInput')[0], 'Research')
  await submit(findAll(root, 'ElForm')[0])
  assert.equal(posts(calls).length, 0)
  fail = false
  const count = calls.length
  await click(refresh())
  assert.equal(calls.length - count, 1)
  assert.equal(byText(root, 'ElButton', '提交申请').props.disabled, false)
  assert.equal(findAll(root, 'ElAlert').length, 0)
})

test('pending badge refresh failure is visible without breaking logs and recovers on retry', async t => {
  let fail = true
  const { root } = await setup(t, { mode: 'user', initialTab: 'logs' }, {
    pending: 2, failGet: url => fail && url.includes('credit-requests'),
  })
  assert.match(findAll(root, 'ElAlert')[0].props.title, /待审批数量刷新失败/)
  assert.equal(findAll(root, 'ElBadge').length, 0)
  assert.equal(findAll(root, 'ElTable')[0].props.data.length, 1)
  fail = false
  await click(findAll(root, 'ElButton').find(el => el.props.title === '刷新'))
  assert.equal(findAll(root, 'ElAlert').length, 0)
  assert.equal(findAll(root, 'ElBadge')[0].props.value, 2)
})
