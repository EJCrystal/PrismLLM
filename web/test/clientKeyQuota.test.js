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
      dailyCnyLimit: '',
      dailyEurLimit: '',
      maxConcurrentRequests: '',
      requestsPerMinute: '',
    }, null)

    assert.deepEqual(payload, {
      name: 'search-service',
      enabled: true,
      expiresAt: null,
      dailyTokenLimit: null,
      dailyUsdLimit: null,
      dailyCnyLimit: null,
      dailyEurLimit: null,
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
      dailyCnyLimit: '36',
      dailyEurLimit: '',
      maxConcurrentRequests: '4',
      requestsPerMinute: '120',
    }, 1_700_000_000_000)

    assert.equal(payload.dailyTokenLimit, 1_000_000)
    assert.equal(payload.dailyUsdLimit, '5.00000000')
    assert.equal(payload.dailyCnyLimit, '36')
    assert.equal(payload.dailyEurLimit, null)
    assert.equal(payload.maxConcurrentRequests, 4)
    assert.equal(payload.requestsPerMinute, 120)
    assert.equal(payload.expiresAt, 1_700_000_000_000)
  })

  it('显示每日用量、费用与并发进度', () => {
    const summary = quotaSummary({
      dailyTokenLimit: 100,
      dailyUsdLimit: '1.5',
      dailyCnyLimit: null,
      dailyEurLimit: '2',
      maxConcurrentRequests: 3,
      requestsPerMinute: 60,
      quotaUsage: {
        dailyTokens: 25,
        dailyUsdCost: '0.25',
        dailyCnyCost: '0',
        dailyEurCost: '0.5',
        concurrentRequests: 1,
        currentMinuteRequests: 7,
      },
    })

    assert.equal(summary, '25 / 100 token · USD 0.25 / 1.5 · EUR 0.5 / 2 · 7 / 60 次/分钟')
  })

  it('无配置时显示未设置限额', () => {
    assert.equal(quotaSummary({ dailyTokenLimit: null, maxConcurrentRequests: null }), '未设置限额')
  })

  it('实时并发占用优先展示上限，未配置上限时只展示在途数', () => {
    assert.equal(concurrencySummary({ maxConcurrentRequests: 3 }, 2), '并发 2 / 3')
    assert.equal(concurrencySummary({ maxConcurrentRequests: null }, 2), '在途 2')
  })

  it('统一美元计费时，仅设置 USD 上限且自动将其他币种限额置空', () => {
    const payload = buildClientKeyPayload({
      name: 'service-usd',
      enabled: true,
      dailyUsdLimit: '10.50',
    }, null)

    assert.equal(payload.dailyUsdLimit, '10.50')
    assert.equal(payload.dailyCnyLimit, null)
    assert.equal(payload.dailyEurLimit, null)
  })

  it('编辑密钥时保留已有的 CNY 和 EUR 每日上限', () => {
    const key = {
      name: 'legacy-caps',
      enabled: true,
      dailyTokenLimit: 1000,
      dailyUsdLimit: '2',
      dailyCnyLimit: '36',
      dailyEurLimit: '4.5',
      maxConcurrentRequests: 3,
      requestsPerMinute: 60,
    }
    const form = {
      name: key.name,
      enabled: key.enabled,
      dailyTokenLimit: String(key.dailyTokenLimit),
      dailyUsdLimit: key.dailyUsdLimit,
      dailyCnyLimit: key.dailyCnyLimit ?? '',
      dailyEurLimit: key.dailyEurLimit ?? '',
      maxConcurrentRequests: String(key.maxConcurrentRequests),
      requestsPerMinute: String(key.requestsPerMinute),
    }

    const payload = buildClientKeyPayload(form, null)
    assert.equal(payload.dailyCnyLimit, '36')
    assert.equal(payload.dailyEurLimit, '4.5')
  })

  it('编辑没有非 USD 上限的密钥时仍提交 null', () => {
    const form = {
      name: 'usd-only',
      enabled: true,
      dailyUsdLimit: '10',
      dailyCnyLimit: '',
      dailyEurLimit: '',
    }
    const payload = buildClientKeyPayload(form, null)
    assert.equal(payload.dailyCnyLimit, null)
    assert.equal(payload.dailyEurLimit, null)
  })

  it('路由模板逐一对应小驼峰费用字段', () => {
    const currencies = [
      { code: 'USD', field: 'dailyUsdLimit' },
      { code: 'CNY', field: 'dailyCnyLimit' },
      { code: 'EUR', field: 'dailyEurLimit' },
    ]
    assert.equal(new Set(currencies.map((item) => item.field)).size, 3)
    const form = { name: '', enabled: true }
    currencies.forEach((item) => { form[item.field] = '' })
    form.requestsPerMinute = ''
    assert.deepEqual(
      Object.keys(buildClientKeyPayload(form, null)).filter((key) => currencies.some((item) => item.field === key)),
      currencies.map((item) => item.field),
    )
  })
})
