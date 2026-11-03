import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { HEALTH_WINDOWS, HEALTH_LABELS, healthPercent, healthBucketState, healthSymbol } from '../src/utils/channelHealth'

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
})
