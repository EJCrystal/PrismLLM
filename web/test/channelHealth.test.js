import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { HEALTH_WINDOWS, HEALTH_LABELS, healthPercent, healthBucketState, healthSymbol, channelHealthSuccessRateText, healthCacheHitText, channelListHealthState } from '../src/utils/channelHealth'

describe('channel health display', () => {
  it('shows unknown rather than zero for missing samples', () => {
    assert.equal(healthPercent(null), '—')
    assert.equal(healthPercent(undefined), '—')
    assert.equal(healthPercent(Number.NaN), '—')
    assert.equal(healthPercent(0), '0.0%')
    assert.equal(healthPercent(1), '100.0%')
    assert.equal(healthPercent(0.9999), '>99.9%')
    assert.equal(healthPercent(1.2), '100.0%')
    assert.equal(healthPercent(-0.2), '0.0%')
  })

  it('keeps sparse buckets unknown and distinguishes the failure bands', () => {
    assert.equal(healthBucketState({ attempts: 0, failures: 0, successRate: null }), 'UNKNOWN')
    assert.equal(healthBucketState({ attempts: 19, failures: 19, successRate: 0 }), 'UNKNOWN')
    assert.equal(healthBucketState({ attempts: 100, failures: 4, successRate: .96 }), 'HEALTHY')
    assert.equal(healthBucketState({ attempts: 20, failures: 1, successRate: .95 }), 'WARNING')
    assert.equal(healthBucketState({ attempts: 20, failures: 4, successRate: .8 }), 'CRITICAL')
  })

  it('uses health thresholds supplied by the server', () => {
    assert.equal(healthBucketState({ attempts: 20, failures: 2, successRate: .9 }, 20, .1, .3), 'WARNING')
    assert.equal(healthBucketState({ attempts: 20, failures: 6, successRate: .7 }, 20, .1, .3), 'CRITICAL')
    assert.equal(healthBucketState({ attempts: 20, failures: 1, successRate: .95 }, 20, .1, .3), 'HEALTHY')
  })

  it('provides symbols and labels independent of colour in all windows', () => {
    assert.deepEqual(HEALTH_WINDOWS.map(row => row.id), ['24h', '7d', '15d', '30d'])
    for (const state of ['HEALTHY', 'WARNING', 'CRITICAL', 'UNKNOWN', 'DISABLED', 'NO_CREDENTIALS']) {
      assert.ok(healthSymbol(state))
      assert.ok(HEALTH_LABELS[state])
    }
    assert.equal(HEALTH_LABELS.NO_CREDENTIALS, '无可用凭据')
    // 已停用与无可用凭据都必须与「健康」区分开，不能显示对勾。
    assert.equal(healthSymbol('NO_CREDENTIALS'), '−')
  })

  it('keeps existing percentage during revalidation to avoid flickering', () => {
    const existing = { metrics: { successRate: 0.985 } }
    // 已有数据时，即便 loading 中也保持旧值显示，避免跳变
    assert.equal(channelHealthSuccessRateText(existing, true, ''), '98.5%')
    assert.equal(channelHealthSuccessRateText(existing, false, ''), '98.5%')
    assert.equal(channelHealthSuccessRateText(existing, false, '网络异常'), '98.5%')

    // 无数据时才根据 loading 和 error 状态提示
    assert.equal(channelHealthSuccessRateText(null, true, ''), '汇总中…')
    assert.equal(channelHealthSuccessRateText(null, false, '网络异常'), '读取失败')
    assert.equal(channelHealthSuccessRateText(null, false, ''), '—')

    // 缓存中 successRate 为 null 时继续进入 loading/error 状态判定
    const nullRateRow = { metrics: { successRate: null } }
    assert.equal(channelHealthSuccessRateText(nullRateRow, true, ''), '汇总中…')
    assert.equal(channelHealthSuccessRateText(nullRateRow, false, '网络异常'), '读取失败')
    assert.equal(channelHealthSuccessRateText(nullRateRow, false, ''), '—')
  })

  it('shows cache hit rate from usage totals without inventing data', () => {
    assert.equal(healthCacheHitText(null), '—')
    assert.equal(healthCacheHitText(undefined), '—')
    assert.equal(healthCacheHitText(undefined, true), '汇总中…')
    assert.equal(healthCacheHitText({ inputTokens: 0, cachedTokens: 0 }), '—')
    assert.equal(healthCacheHitText({ inputTokens: 1000, cachedTokens: 250 }), '25.0%')
    assert.equal(healthCacheHitText({ inputTokens: 1000, cachedTokens: 0 }), '0.0%')
    assert.equal(healthCacheHitText({ inputTokens: 100, cachedTokens: 120 }), '100.0%')
    // 刷新时保留旧值，避免数字闪烁
    assert.equal(healthCacheHitText({ inputTokens: 1000, cachedTokens: 250 }, true), '25.0%')
  })

  it('channelListHealthState 只信任后端报告，不用静态密钥冒充无凭据', () => {
    // 后端报什么就显示什么：报告基于静态密钥 + 订阅账号的完整凭据集。
    assert.equal(channelListHealthState({ enabled: true }, 'NO_CREDENTIALS'), 'NO_CREDENTIALS')
    assert.equal(channelListHealthState({ enabled: true }, 'HEALTHY'), 'HEALTHY')
    // 停用渠道优先显示已停用，不被报告覆盖。
    assert.equal(channelListHealthState({ enabled: false }, 'HEALTHY'), 'DISABLED')
    assert.equal(channelListHealthState({ enabled: false }, null), 'DISABLED')
    // 报告未加载、读取失败或只报了 DISABLED：显示未知，不能断言无可用凭据——
    // 渠道列表不加载订阅账号，密钥全停用的渠道仍可能由账号支撑。
    assert.equal(channelListHealthState({ enabled: true }, null), 'UNKNOWN')
    assert.equal(channelListHealthState({ enabled: true }, undefined), 'UNKNOWN')
    assert.equal(channelListHealthState({ enabled: true }, 'DISABLED'), 'UNKNOWN')
    assert.equal(channelListHealthState(null, 'HEALTHY'), 'DISABLED')
    assert.equal(channelListHealthState(undefined, null), 'DISABLED')
  })
})
