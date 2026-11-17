import test from 'node:test'
import assert from 'node:assert/strict'
import { groupUsage, sumUsage } from '../src/usageStats.ts'

test('same date has one row with independent sources and combined totals', () => {
  const rows = groupUsage([
    { date:'2026-11-17',source:'chat',requestCount:2,inputTokens:100,outputTokens:50 },
    { date:'2026-11-17',source:'api',requestCount:3,inputTokens:200,outputTokens:80 },
  ], row => row.date)
  assert.equal(rows.length, 1)
  assert.equal(rows[0].chat.requestCount, 2)
  assert.equal(rows[0].api.requestCount, 3)
  assert.deepEqual(rows[0].total, {requestCount:5,inputTokens:300,outputTokens:130,failedRequestCount:0})
})
test('missing source is zero, dates sorted and source rows do not duplicate a precomputed total', () => {
  const result = groupUsage([
    {month:'2026-11',source:'api',requestCount:1,inputTokens:3,outputTokens:4,failedRequestCount:1},
    {month:'2026-10',source:'chat',requestCount:2,inputTokens:5,outputTokens:6},
    {month:'2026-11',source:'all',requestCount:99,inputTokens:99,outputTokens:99},
  ], row => row.month)
  assert.deepEqual(result.map(row => row.key), ['2026-10','2026-11'])
  assert.equal(result[1].chat.requestCount, 0)
  assert.equal(result[1].total.requestCount, 1)
  assert.equal(result[1].total.failedRequestCount, 1)
  assert.equal(sumUsage(result.map(row => row.total)).inputTokens, 8)
  assert.deepEqual(sumUsage([]), {requestCount:0,inputTokens:0,outputTokens:0,failedRequestCount:0})
})
