import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  attemptFailureReason,
  attemptDurationPercent,
  attemptHasError,
  attemptResultLabel,
  effectiveProtocolEndpoint,
  protocolConversion,
  protocolEndpoint,
  protocolLabel,
  reportedModelStatus,
  requestLogStatusLabel,
  requestLogTone,
  reasoningEffortLabel,
  tokenUsageLabel,
  tokenUsageState,
  summarizeAttemptTimings,
} from '../src/utils/requestLogDisplay'

describe('requestLogDisplay: 请求日志状态展示', () => {
  it('compares only recorded call durations and preserves zero without counting skipped candidates', () => {
    const attempts = [{ elapsedMs: 100 }, { elapsedMs: 400 }, { elapsedMs: 0 }, { elapsedMs: 9000, skipped: true }, {}, { elapsedMs: -1 }, { elapsedMs: NaN }]
    assert.deepEqual(summarizeAttemptTimings(attempts), { recordedMs: 500, maxMs: 400, measuredCount: 3, callCount: 6 })
    assert.equal(attemptDurationPercent(attempts[0], 400), 25)
    assert.equal(attemptDurationPercent(attempts[2], 400), 0)
    assert.equal(attemptDurationPercent(attempts[3], 400), null)
    assert.equal(attemptDurationPercent(attempts[4], 400), null)
    assert.equal(attemptDurationPercent(attempts[5], 400), null)
    assert.equal(attemptDurationPercent(attempts[6], 400), null)
    assert.equal(attemptDurationPercent({ elapsedMs: 0 }, 0), 0)
    assert.deepEqual(summarizeAttemptTimings([]), { recordedMs: 0, maxMs: 0, measuredCount: 0, callCount: 0 })
  })
  it('区分服务端失败、客户端异常、流式错误与重试后成功', () => {
    assert.equal(requestLogTone({ statusCode: 200 }), null)
    assert.equal(requestLogTone({ statusCode: 503 }), 'danger')
    assert.equal(requestLogTone({ statusCode: 429 }), 'warning')
    assert.equal(requestLogTone({ statusCode: 499, errorCode: 'client_disconnect' }), 'warning')
    assert.equal(requestLogStatusLabel({ statusCode: 499, errorCode: 'client_disconnect' }), '客户端断开')
    assert.equal(requestLogStatusLabel({ statusCode: 200, errorCode: 'client_cancelled' }), '客户端取消')
    assert.equal(requestLogTone({ statusCode: 200, errorCode: 'stream_interrupted' }), 'danger')
    assert.equal(requestLogTone({ statusCode: 200, upstreamErrorCode: 'upstream_failure' }), 'danger')
    assert.equal(requestLogStatusLabel({ statusCode: 200, errorCode: 'stream_interrupted' }), '请求失败')
    assert.equal(requestLogTone({ statusCode: 200, upstreamAttempts: 2 }), 'recovered')
    assert.equal(requestLogStatusLabel({ statusCode: 200, upstreamAttempts: 2 }), '重试成功')
    assert.equal(requestLogTone({ statusCode: 200, attempts: [{ statusCode: 503 }, { statusCode: 200 }] }), 'recovered')
    assert.equal(requestLogTone({ statusCode: 200, attempts: [{ skipped: true, errorCode: 'cooldown' }, { statusCode: 200 }] }), null)
    assert.equal(requestLogTone({ statusCode: 503, upstreamAttempts: 2 }), 'danger')
  })

  it('把缺省推理强度明确展示为 none，其余档位原样小写展示', () => {
    assert.equal(reasoningEffortLabel(null), 'none')
    assert.equal(reasoningEffortLabel(undefined), 'none')
    assert.equal(reasoningEffortLabel(' HIGH '), 'high')
    assert.equal(reasoningEffortLabel('max'), 'max')
    assert.equal(reasoningEffortLabel('xhigh'), 'xhigh')
    assert.equal(reasoningEffortLabel('unsupported'), 'unsupported')
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

  it('协议端点映射与网关实际的 /v1 端点、Gemini 动态 URL 一致', () => {
    assert.deepEqual(protocolEndpoint('chat_completions'), {
      value: 'chat_completions', label: 'OpenAI Chat Completions', shortName: 'Chat',
      apiFormat: 'openai/chat_completions', endpointPath: '/v1/chat/completions', known: true,
    })
    assert.equal(protocolEndpoint('responses').apiFormat, 'openai/responses')
    assert.equal(protocolEndpoint('responses').endpointPath, '/v1/responses')
    assert.equal(protocolEndpoint('messages').apiFormat, 'anthropic/messages')
    assert.equal(protocolEndpoint('messages').endpointPath, '/v1/messages')
    assert.equal(protocolEndpoint('gemini').apiFormat, 'gemini/contents')
    assert.equal(protocolEndpoint('gemini').endpointPath, '/v1beta/models/{model}:generateContent')
    assert.equal(protocolEndpoint('gemini', false).endpointPath, '/v1beta/models/{model}:generateContent')
    assert.equal(protocolEndpoint('gemini', true).endpointPath, '/v1beta/models/{model}:streamGenerateContent?alt=sse')
    assert.equal(protocolEndpoint('custom_protocol').known, false)
    assert.equal(protocolEndpoint('custom_protocol').apiFormat, 'custom_protocol')
    assert.equal(protocolEndpoint('constructor').known, false)
    assert.equal(protocolEndpoint('toString').known, false)
    assert.equal(protocolEndpoint(null).known, false)
  })

  it('同协议透传不标记转换，异协议才显示由入站协议转换及出站端点', () => {
    const passthrough = protocolConversion('responses', 'responses')
    assert.equal(passthrough.converted, false)
    assert.equal(passthrough.outbound.apiFormat, 'openai/responses')
    assert.match(passthrough.title, /透传未转换（openai\/responses）/)

    const converted = protocolConversion('responses', 'gemini')
    assert.equal(converted.converted, true)
    assert.equal(converted.inbound.apiFormat, 'openai/responses')
    assert.equal(converted.outbound.apiFormat, 'gemini/contents')
    assert.match(converted.title, /出站协议：已由入站协议转换（gemini\/contents）/)

    const unknown = protocolConversion('messages', null)
    assert.equal(unknown.converted, false)
    assert.equal(unknown.outbound, null)
    assert.match(unknown.title, /出站协议：未知（未发起上游请求）/)

    const unknownWithAttempts = protocolConversion('messages', null, false, 2)
    assert.equal(unknownWithAttempts.converted, false)
    assert.equal(unknownWithAttempts.outbound, null)
    assert.match(unknownWithAttempts.title, /出站协议：未知（出站协议未知）/)

    const missingInbound = protocolConversion(null, 'responses')
    assert.equal(missingInbound.converted, false)
    assert.equal(missingInbound.outbound.apiFormat, 'openai/responses')
    assert.match(missingInbound.title, /入站协议：未知/)
    assert.match(missingInbound.title, /出站协议：OpenAI Responses（openai\/responses）/)

    const bothMissing = protocolConversion(null, null, false, 0)
    assert.equal(bothMissing.converted, false)
    assert.equal(bothMissing.outbound, null)
    assert.match(bothMissing.title, /入站协议：未知/)
    assert.match(bothMissing.title, /出站协议：未知（未发起上游请求）/)
  })

  it('有效端点在入站缺失时回退到出站已知端点', () => {
    const missingInbound = protocolConversion(null, 'responses')
    const endpoint = effectiveProtocolEndpoint(missingInbound)
    assert.equal(endpoint.value, 'responses')
    assert.equal(endpoint.shortName, 'Responses')
    assert.equal(endpoint.known, true)

    const normal = protocolConversion('chat_completions', 'gemini')
    assert.equal(effectiveProtocolEndpoint(normal).value, 'chat_completions')

    const bothEmpty = protocolConversion(null, null)
    assert.equal(effectiveProtocolEndpoint(bothEmpty).value, '')
    assert.equal(effectiveProtocolEndpoint(bothEmpty).known, false)
  })

  it('比较最终路由上游自报模型，兼容缺少自报值的历史尝试', () => {
    const log = {
      channelId: 'final-channel',
      channelKeyId: 'final-key',
      upstreamModel: 'model-a',
      attempts: [
        { channelId: 'failed-channel', channelKeyId: 'other-key', upstreamModel: 'model-a', upstreamReportedModel: 'model-b' },
        { channelId: 'final-channel', channelKeyId: 'final-key', upstreamModel: 'model-a', upstreamReportedModel: ' model-a ' },
      ],
    }
    assert.deepEqual(reportedModelStatus(log), { state: 'match', reportedModel: 'model-a' })
    for (const suffix of ['-2024-07-18', '-20240718', '@20240718']) {
      assert.deepEqual(reportedModelStatus({
        ...log,
        attempts: [{ channelId: 'final-channel', channelKeyId: 'final-key', upstreamModel: 'model-a', upstreamReportedModel: `model-a${suffix}` }],
      }), { state: 'mismatch', reportedModel: `model-a${suffix}` })
    }
    assert.deepEqual(reportedModelStatus({
      ...log,
      attempts: [{ channelId: 'final-channel', channelKeyId: 'final-key', upstreamModel: 'model-a', upstreamReportedModel: 'model-b' }],
    }), { state: 'mismatch', reportedModel: 'model-b' })
    assert.deepEqual(reportedModelStatus({
      ...log,
      attempts: [{ channelId: 'final-channel', channelKeyId: 'final-key', upstreamModel: 'model-a' }],
    }), { state: 'unknown', reportedModel: null })
    assert.deepEqual(reportedModelStatus({ ...log, channelId: 'missing-channel' }), { state: 'unknown', reportedModel: null })
    assert.equal(reportedModelStatus({ ...log, upstreamModel: null }), null)
    assert.deepEqual(reportedModelStatus({
      ...log,
      attempts: [...log.attempts, { channelId: 'final-channel', channelKeyId: 'final-key', upstreamModel: 'model-a', skipped: true }],
    }), { state: 'match', reportedModel: 'model-a' })
  })

  it('路由跳过的候选不按失败展示', () => {
    const skipped = { skipped: true, skipReason: 'cooldown' }
    assert.equal(attemptHasError(skipped), false)
    assert.equal(attemptResultLabel(skipped), '跳过（冷却）')
    assert.equal(attemptFailureReason(skipped), '冷却中')
  })

  it('订阅账号级调度跳过原因使用可读标签', () => {
    assert.equal(attemptResultLabel({ skipped: true, skipReason: 'oauth_window' }), '跳过（订阅账号窗口已用尽）')
    assert.equal(attemptResultLabel({ skipped: true, skipReason: 'oauth_concurrency' }), '跳过（订阅账号并发已满）')
    assert.equal(attemptResultLabel({ skipped: true, skipReason: 'oauth_cooldown' }), '跳过（订阅账号限流冷却中）')
    assert.equal(attemptFailureReason({ skipped: true, skipReason: 'oauth_window' }), '订阅账号窗口已用尽')
    assert.equal(attemptResultLabel({ skipped: true, skipReason: 'unknown' }), '路由跳过')
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
