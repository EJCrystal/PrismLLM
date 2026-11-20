import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { pluginFormError } from '../src/utils/pluginForm'

function field(overrides = {}) {
  return {
    key: 'notes', label: '备注', kind: 'text', required: false, defaultValue: null,
    help: '', maxLength: null, min: null, max: null, options: [], ...overrides,
  }
}

describe('plugin form response-contract boundaries', () => {
  for (const kind of ['text', 'textarea']) {
    it('enforces default raw length and NUL restrictions for ' + kind, () => {
      const fields = [field({ kind })]
      assert.equal(pluginFormError(fields, { notes: 'a'.repeat(4000) }), '')
      assert.match(pluginFormError(fields, { notes: 'a'.repeat(4001) }), /4000/)
      assert.match(pluginFormError(fields, { notes: ' '.repeat(4001) }), /4000/)
      assert.match(pluginFormError(fields, { notes: 'a\u0000b' }), /空字符/)
    })
  }

  it('counts ordinary text without trimming against explicit limits', () => {
    const fields = [field({ maxLength: 1 })]
    assert.equal(pluginFormError(fields, { notes: 'a' }), '')
    assert.match(pluginFormError(fields, { notes: ' a ' }), /不能超过 1/)
  })

  for (const key of ['prompt', 'defaultPrompt']) {
    it('preserves trimmed prompt limits without accepting NUL for ' + key, () => {
      const fields = [field({ key, kind: 'textarea', maxLength: 4000 })]
      assert.equal(pluginFormError(fields, { [key]: ' ' + 'a'.repeat(4000) + ' ' }), '')
      assert.match(pluginFormError(fields, { [key]: ' ' + 'a'.repeat(4001) + ' ' }), /4000/)
      assert.match(pluginFormError(fields, { [key]: 'a\u0000b' }), /空字符/)
    })
  }
})
