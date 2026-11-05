import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { HEALTH_WINDOWS, HEALTH_LABELS, healthPercent, healthBucketState, healthSymbol, channelHealthSuccessRateText } from '../src/utils/channelHealth'

describe('channel health display', () => {
  it('shows unknown rather than zero for missing samples', () => {
    assert.equal(healthPercent(null), '—')
    assert.equal(healthPercent(undefined), '—')
    assert.equal(healthPercent(Number.NaN), '—')
    assert.equal(healthPercent(0), '0.0%')
    assert.equal(healthPercent(1), '100.0%')
    assert.equal(healthPercent(0.9999), '>99.9%')
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
    for (const state of ['HEALTHY', 'WARNING', 'CRITICAL', 'UNKNOWN', 'DISABLED']) {
      assert.ok(healthSymbol(state))
      assert.ok(HEALTH_LABELS[state])
    }
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
})
