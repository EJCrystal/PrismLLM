import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { buildClientKeyPayload, concurrencySummary, quotaSummary } from '../src/utils/clientKeyQuota'

describe('clientKeyQuota: 调用方密钥额度配置', () => {
  it('空限额不提交为 0，保持不限制语义', () => {
    const payload = buildClientKeyPayload({
      name: ' search-service ',
      enabled: true,
      dailyTokenLimit: '',
      dailyUsdLimit: ' ',
      maxConcurrentRequests: '',
      requestsPerMinute: '',
    }, null)

    assert.deepEqual(payload, {
      name: 'search-service',
      enabled: true,
      expiresAt: null,
      dailyTokenLimit: null,
      dailyUsdLimit: null,
      maxConcurrentRequests: null,
      requestsPerMinute: null,
    })
  })

  it('保留数字上限字符串和整数配置', () => {
    const payload = buildClientKeyPayload({
      name: 'batch',
      enabled: false,
      dailyTokenLimit: '1000000',
      dailyUsdLimit: '5.00000000',
      maxConcurrentRequests: '4',
      requestsPerMinute: '120',
    }, 1_700_000_000_000)

    assert.equal(payload.dailyTokenLimit, 1_000_000)
    assert.equal(payload.dailyUsdLimit, '5.00000000')
    assert.equal(payload.maxConcurrentRequests, 4)
    assert.equal(payload.requestsPerMinute, 120)
    assert.equal(payload.expiresAt, 1_700_000_000_000)
  })

  it('显示每日用量、费用与并发进度', () => {
    const summary = quotaSummary({
      dailyTokenLimit: 100,
      dailyUsdLimit: '1.5',
      maxConcurrentRequests: 3,
      requestsPerMinute: 60,
      quotaUsage: {
        dailyTokens: 25,
        dailyUsdCost: '0.25',
        concurrentRequests: 1,
        currentMinuteRequests: 7,
      },
    })

    assert.equal(summary, '25 / 100 token · $0.25 / 1.5 · 7 / 60 次/分钟')
  })

  it('无配置时显示未设置限额', () => {
    assert.equal(quotaSummary({ dailyTokenLimit: null, maxConcurrentRequests: null }), '未设置限额')
  })

  it('实时并发占用优先展示上限，未配置上限时只展示在途数', () => {
    assert.equal(concurrencySummary({ maxConcurrentRequests: 3 }, 2), '并发 2 / 3')
    assert.equal(concurrencySummary({ maxConcurrentRequests: null }, 2), '在途 2')
  })

  it('费用上限按 USD 提交', () => {
    const payload = buildClientKeyPayload({
      name: 'service-usd',
      enabled: true,
      dailyUsdLimit: '10.50',
    }, null)

    assert.equal(payload.dailyUsdLimit, '10.50')
  })
})
