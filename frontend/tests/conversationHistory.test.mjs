import assert from 'node:assert/strict'
import test from 'node:test'
import { groupConversations } from '../src/conversationHistory.ts'

const conversation = (id, title, updatedAt, preview = '') => ({
  id,
  title,
  model: 'deepseek-chat',
  messageCount: 2,
  preview,
  updatedAt,
})

test('groups conversations into today, recent, and older buckets', () => {
  const groups = groupConversations([
    conversation('today', '今天', '2026-06-18T08:00:00+08:00'),
    conversation('recent', '本周', '2026-06-13T08:00:00+08:00'),
    conversation('older', '历史', '2026-06-01T08:00:00+08:00'),
  ], '', new Date('2026-06-18T12:00:00+08:00'))

  assert.deepEqual(groups.map((group) => [group.label, group.conversations[0].id]), [
    ['今天', 'today'],
    ['近 7 天', 'recent'],
    ['更早', 'older'],
  ])
})

test('searches both titles and message previews without case sensitivity', () => {
  const conversations = [
    conversation('title', 'Deployment Plan', '2026-06-18T08:00:00+08:00'),
    conversation('preview', '会议记录', '2026-06-18T07:00:00+08:00', 'Deployment checklist'),
    conversation('miss', '采购', '2026-06-18T06:00:00+08:00'),
  ]

  const groups = groupConversations(conversations, 'deployment', new Date('2026-06-18T12:00:00+08:00'))
  assert.deepEqual(groups.flatMap((group) => group.conversations.map((item) => item.id)), ['title', 'preview'])
})
