import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { requestModeLabel } from '../src/utils/requestMode.js'

describe('requestModeLabel', () => {
  it('distinguishes streaming and non-streaming requests', () => {
    assert.equal(requestModeLabel(true), '流式')
    assert.equal(requestModeLabel(false), '非流式')
  })

  it('marks historical or unavailable request modes as unknown', () => {
    assert.equal(requestModeLabel(null), '未知')
    assert.equal(requestModeLabel(undefined), '未知')
  })
})
