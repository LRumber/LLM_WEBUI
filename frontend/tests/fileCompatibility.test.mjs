import assert from 'node:assert/strict'
import test from 'node:test'
import { assessFileCompatibility } from '../src/fileCompatibility.ts'

test('allows extracted documents for language models', () => {
  assert.equal(assessFileCompatibility({ name: 'report.pdf', type: 'application/pdf' }, ['chat']).supported, true)
})

test('requires vision capability for images', () => {
  assert.equal(assessFileCompatibility({ name: 'photo.png', type: 'image/png' }, ['chat']).supported, false)
  assert.equal(assessFileCompatibility({ name: 'photo.png', type: 'image/png' }, ['chat', 'vision']).supported, true)
})

test('requires speech capability for audio', () => {
  const result = assessFileCompatibility({ name: 'meeting.wav', type: 'audio/wav' }, ['chat'])
  assert.equal(result.kind, 'audio')
  assert.equal(result.supported, false)
})
