import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { requestModeClass, requestModeLabel } from '../src/utils/requestMode.js'

describe('requestModeLabel', () => {
  it('distinguishes streaming and non-streaming requests', () => {
    assert.equal(requestModeLabel(true), '流式')
    assert.equal(requestModeLabel(false), '同步')
    assert.equal(requestModeClass(true), 'streaming')
    assert.equal(requestModeClass(false), 'sync')
  })

  it('treats historical or unavailable stream flags as synchronous', () => {
    assert.equal(requestModeLabel(null), '同步')
    assert.equal(requestModeLabel(undefined), '同步')
    assert.equal(requestModeLabel('true'), '同步')
    assert.equal(requestModeClass(null), 'sync')
    assert.equal(requestModeClass(undefined), 'sync')
  })
})
