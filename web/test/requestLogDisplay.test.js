import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  attemptFailureReason,
  attemptHasError,
  attemptResultLabel,
  protocolLabel,
  reasoningEffortClass,
  reasoningEffortLabel,
  tokenUsageLabel,
  tokenUsageState,
} from '../src/utils/requestLogDisplay'

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

  it('入站与出站协议使用独立标签，未知值原样展示', () => {
    assert.equal(protocolLabel('chat_completions'), 'Chat Completions')
    assert.equal(protocolLabel('responses'), 'Responses')
    assert.equal(protocolLabel('messages'), 'Messages')
    assert.equal(protocolLabel('gemini'), 'Gemini')
    assert.equal(protocolLabel(null), '—')
    assert.equal(protocolLabel('custom_protocol'), 'custom_protocol')
  })

  it('尝试记录区分成功、失败后切换和终止', () => {
    assert.equal(attemptResultLabel({ statusCode: 200, committed: true }), '成功')
    assert.equal(attemptResultLabel({ statusCode: 500, errorCode: 'upstream_error', retryable: true }, true), '失败，已切换')
    assert.equal(attemptResultLabel({ statusCode: 400, errorCode: 'upstream_error', retryable: false }), '失败，终止')
    assert.equal(attemptResultLabel({ statusCode: null, errorCode: 'upstream_unreachable', retryable: true }), '未连接到上游')
    assert.equal(attemptResultLabel({ statusCode: null, errorCode: 'upstream_timeout', retryable: true }), '未收到上游响应')
    assert.equal(attemptResultLabel({ errorCode: 'client_disconnect' }), '客户端断开')
  })

  it('尝试失败原因优先使用错误信息，其次上游错误码和网关错误码', () => {
    assert.equal(attemptFailureReason({ errorMessage: '上游服务不可用', upstreamErrorCode: 'invalid_api_key' }), '上游服务不可用')
    assert.equal(attemptFailureReason({ upstreamErrorCode: 'invalid_api_key' }), 'invalid_api_key')
    assert.equal(attemptFailureReason({ errorCode: 'upstream_timeout' }), 'upstream_timeout')
    assert.equal(attemptFailureReason({ statusCode: 200 }), '—')
  })

  it('HTTP 200 中的上游错误状态仍按失败展示', () => {
    const attempt = { statusCode: 200, upstreamErrorStatusCode: 429, upstreamErrorCode: 'rate_limit_error', committed: true }
    assert.equal(attemptHasError(attempt), true)
    assert.equal(attemptResultLabel(attempt), '失败，终止')
    assert.equal(attemptFailureReason(attempt), 'rate_limit_error')
    assert.equal(attemptHasError({ statusCode: 200, committed: true }), false)
    assert.equal(attemptResultLabel({ statusCode: 200, committed: true }), '成功')
  })

  it('仅在存在下一条实际尝试时显示已切换', () => {
    const exhausted = { statusCode: 500, errorCode: 'upstream_error', retryable: true }
    assert.equal(attemptResultLabel(exhausted), '失败，终止')
    assert.equal(attemptResultLabel(exhausted, false), '失败，终止')
    assert.equal(attemptResultLabel(exhausted, true), '失败，已切换')
    assert.equal(attemptResultLabel({ statusCode: 200, errorCode: 'protocol_conversion_unsupported', retryable: false }, true), '失败，已切换')
  })
})
