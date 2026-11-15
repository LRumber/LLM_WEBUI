import assert from 'node:assert/strict'
import test from 'node:test'
import { resolveAdminPage } from '../src/adminRouting.ts'

test('maps each administration module to an independent URL', () => {
  assert.equal(resolveAdminPage('/admin'), 'overview')
  assert.equal(resolveAdminPage('/admin/users'), 'users')
  assert.equal(resolveAdminPage('/admin/usage'), 'usage')
  assert.equal(resolveAdminPage('/admin/models'), 'models')
  assert.equal(resolveAdminPage('/admin/prompts'), 'prompts')
  assert.equal(resolveAdminPage('/admin/announcements'), 'announcements')
  assert.equal(resolveAdminPage('/admin/settings'), 'settings')
  assert.equal(resolveAdminPage('/admin/feedback'), 'feedback')
})

test('falls back unknown administration paths to overview', () => {
  assert.equal(resolveAdminPage('/admin/unknown'), 'overview')
})
