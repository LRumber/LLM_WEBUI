import test from 'node:test'
import assert from 'node:assert/strict'
import { compactUsage, usageScale, usageTicks, modelDisplayName } from '../src/usageChart.ts'
import { monthlyUsage } from '../src/usageStats.ts'

test('hybrid scale preserves zero, one and decade intervals', () => {
  assert.deepEqual([0, 0.5, 1, 10, 100].map(usageScale), [0, 0.5, 1, 2, 3])
  const ticks = usageTicks(3_413_000_000)
  assert.equal(ticks[0], 0)
  assert.ok(ticks.at(-1) >= 3_413_000_000)
  assert.ok(usageTicks(0).every(Number.isFinite))
  assert.equal(compactUsage(3413000000), '34.13亿')
  assert.equal(compactUsage(201600), '20.16万')
})
test('monthly charts fill missing months without combining independent sources', () => {
  const rows = monthlyUsage([
    { month: '2026-11', source: 'api', requestCount: 7, inputTokens: 10, outputTokens: 15 },
    { month: '2026-11', source: 'chat', requestCount: 2, inputTokens: 3, outputTokens: 4 },
  ], '2026-11')
  assert.equal(rows.length, 12)
  assert.equal(rows[0].key, '2025-12')
  assert.equal(rows[0].total.requestCount, 0)
  assert.equal(rows.at(-1).api.requestCount, 7)
  assert.equal(rows.at(-1).chat.requestCount, 2)
  assert.equal(rows.at(-1).total.inputTokens + rows.at(-1).total.outputTokens, 32)
})
test('model labels use display names while unknown or removed models retain their identity', () => {
  const models = [{ key: 'deepseek-v4', display_name: 'DeepSeek V4' }, { key: 'blank', display_name: ' ' }]
  assert.equal(modelDisplayName('deepseek-v4', models), 'DeepSeek V4')
  assert.equal(modelDisplayName('retired-model', models), 'retired-model')
  assert.equal(modelDisplayName('blank', models), 'blank')
})
