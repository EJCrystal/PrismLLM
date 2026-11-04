import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { cooldownReasonLabel, cooldownSummaryText, formatCooldownRemaining } from '../src/utils/cooldown'

describe('cooldown display', () => {
  const now = 1_000_000

  it('formats remaining time without negative values', () => {
    assert.equal(formatCooldownRemaining(null, now), '')
    assert.equal(formatCooldownRemaining(now - 1, now), '即将恢复')
    assert.equal(formatCooldownRemaining(now + 500, now), '1 秒')
    assert.equal(formatCooldownRemaining(now + 65_000, now), '1 分 5 秒')
    assert.equal(formatCooldownRemaining(now + 600_000, now), '10 分')
    assert.equal(formatCooldownRemaining(now + 3_600_000, now), '1 小时')
    assert.equal(formatCooldownRemaining(now + 90_000_000, now), '1 天 1 小时')
  })

  it('labels known reasons and preserves unknown ones', () => {
    assert.equal(cooldownReasonLabel('transient'), '瞬时错误')
    assert.equal(cooldownReasonLabel('rate_limited'), '上游限流')
    assert.equal(cooldownReasonLabel('auth_failure'), '鉴权失败')
    assert.equal(cooldownReasonLabel('ttft_slow'), '首 token 过慢')
    assert.equal(cooldownReasonLabel('custom_reason'), 'custom_reason')
    assert.equal(cooldownReasonLabel(null), '')
  })

  it('builds the channel list summary', () => {
    assert.equal(cooldownSummaryText(null, now), '')
    assert.equal(cooldownSummaryText({ coolingCandidates: 0, totalCandidates: 4 }, now), '')
    assert.equal(
      cooldownSummaryText(
        { coolingCandidates: 1, totalCandidates: 4, cooldownUntil: now + 65_000, cooldownReason: 'rate_limited' },
        now,
      ),
      '1/4 候选冷却 · 1 分 5 秒 · 上游限流',
    )
  })
})
