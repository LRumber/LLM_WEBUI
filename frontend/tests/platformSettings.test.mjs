import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultPlatformSettings, uploadPolicyError } from '../src/platformSettings.ts'
test('upload policy applies exact extension and byte limits including uppercase filenames', () => {
  const settings = { ...defaultPlatformSettings, maxUploadBytes: 1024, allowedExtensions: '.pdf,.txt' }
  assert.equal(uploadPolicyError({ name: 'report.PDF', size: 1024 }, settings), '')
  for (const file of [{ name: 'report.pdf', size: 1025 }, { name: 'report.pdf', size: 0 }, { name: 'report.pdf.exe', size: 1 }, { name: 'image.png', size: 1 }]) {
    assert.notEqual(uploadPolicyError(file, settings), '')
  }
})
