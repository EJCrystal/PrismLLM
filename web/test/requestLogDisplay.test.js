import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { reasoningEffortClass, reasoningEffortLabel, tokenUsageLabel, tokenUsageState } from '../src/utils/requestLogDisplay'

describe('requestLogDisplay: 请求日志状态展示', () => {
  it('把缺省推理强度明确展示为 none，并为等级提供样式类别', () => {
    assert.equal(reasoningEffortLabel(null), 'none')
    assert.equal(reasoningEffortClass(undefined), 'reasoning-none')
    assert.equal(reasoningEffortLabel(' HIGH '), 'high')
    assert.equal(reasoningEffortClass('max'), 'reasoning-max')
    assert.equal(reasoningEffortLabel('xhigh'), 'xhigh')
    assert.equal(reasoningEffortLabel('unsupported'), 'unsupported')
    assert.equal(reasoningEffortClass('unsupported'), 'reasoning-custom')
  })

  it('区分没有发起上游请求和上游未返回用量', () => {
    assert.equal(tokenUsageState({ usageKnown: false, upstreamAttempts: 0 }), 'no-request')
    assert.equal(tokenUsageLabel({ usageKnown: false, upstreamAttempts: 0 }), '无请求')
    assert.equal(tokenUsageState({ usageKnown: false, upstreamAttempts: 1 }), 'unknown')
    assert.equal(tokenUsageLabel({ usageKnown: false, upstreamAttempts: 1 }), '未知')
    assert.equal(tokenUsageState({ usageKnown: true, upstreamAttempts: 1 }), 'known')
  })
})
