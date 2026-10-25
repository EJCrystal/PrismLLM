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

  it('marks historical or unavailable request modes as unknown', () => {
    assert.equal(requestModeLabel(null), '未知')
    assert.equal(requestModeLabel(undefined), '未知')
    assert.equal(requestModeClass(undefined), 'unknown')
  })
})
