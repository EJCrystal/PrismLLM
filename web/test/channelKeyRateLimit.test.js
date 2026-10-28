import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildChannelKeyRateLimitPayload,
  channelKeyRateLimitSummary,
  normalizeChannelKeyRateLimit,
} from '../src/utils/channelKeyRateLimit.js'

describe('channelKeyRateLimit: 渠道密钥限流规则', () => {
  it('未配置规则默认不限流', () => {
    assert.deepEqual(normalizeChannelKeyRateLimit(), {
      enabled: false,
      requestsPerMinute: null,
      maxConcurrentRequests: null,
    })
    assert.equal(channelKeyRateLimitSummary(), '未配置，不限流')
  })

  it('支持只配置 RPM、只配置并发或同时配置', () => {
    assert.deepEqual(
      buildChannelKeyRateLimitPayload({ enabled: true, requestsPerMinute: '60', maxConcurrentRequests: '' }),
      { enabled: true, requestsPerMinute: 60, maxConcurrentRequests: null },
    )
    assert.deepEqual(
      buildChannelKeyRateLimitPayload({ enabled: true, requestsPerMinute: null, maxConcurrentRequests: '4' }),
      { enabled: true, requestsPerMinute: null, maxConcurrentRequests: 4 },
    )
    assert.equal(
      channelKeyRateLimitSummary({ enabled: true, requestsPerMinute: 1200, maxConcurrentRequests: 8 }),
      'RPM 1,200 · 并发 8',
    )
  })

  it('关闭规则时保留已有数值但不执行限流', () => {
    const rule = { enabled: false, requestsPerMinute: 60, maxConcurrentRequests: 3 }
    assert.deepEqual(buildChannelKeyRateLimitPayload(rule), rule)
    assert.equal(channelKeyRateLimitSummary(rule), '已关闭（保留 RPM 60 · 并发 3）')
  })

  it('编辑已知字段时透传服务端新增字段', () => {
    const rule = {
      enabled: true,
      requestsPerMinute: 60,
      maxConcurrentRequests: 2,
      futureWindowSeconds: 30,
    }
    assert.deepEqual(buildChannelKeyRateLimitPayload(rule), rule)
  })

  it('启用规则时至少需要一个限额', () => {
    assert.throws(
      () => buildChannelKeyRateLimitPayload({ enabled: true }),
      /至少填写每分钟请求数或最大并发请求数/,
    )
  })

  it('拒绝越界和非整数配置', () => {
    assert.throws(
      () => buildChannelKeyRateLimitPayload({ enabled: true, requestsPerMinute: 0 }),
      /1\.\.1000000/,
    )
    assert.throws(
      () => buildChannelKeyRateLimitPayload({ enabled: true, maxConcurrentRequests: 1.5 }),
      /1\.\.100000 的整数/,
    )
  })
})
