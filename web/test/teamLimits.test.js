import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildTeamLimitPayload,
  teamConcurrencySummary,
  teamLimitValidationMessage,
  teamRequestSummary,
} from '../src/utils/teamLimits.js'

describe('teamLimits: 团队限流配置与展示', () => {
  it('空字段保持不限制语义，0 请求速率会被保留', () => {
    assert.deepEqual(
      buildTeamLimitPayload({ requestsPerMinute: '', maxConcurrentRequests: ' ' }),
      { requestsPerMinute: null, maxConcurrentRequests: null },
    )
    assert.deepEqual(
      buildTeamLimitPayload({ requestsPerMinute: '0', maxConcurrentRequests: '4' }),
      { requestsPerMinute: 0, maxConcurrentRequests: 4 },
    )
  })

  it('要求团队名称和至少一项限制', () => {
    assert.equal(
      teamLimitValidationMessage('', { requestsPerMinute: null, maxConcurrentRequests: null }),
      '请填写团队名称',
    )
    assert.equal(
      teamLimitValidationMessage('search', { requestsPerMinute: null, maxConcurrentRequests: null }),
      '每分钟请求数和最大并发数至少设置一项',
    )
    assert.equal(
      teamLimitValidationMessage('search', { requestsPerMinute: 60, maxConcurrentRequests: null }),
      '',
    )
  })

  it('同时展示实时用量与可选上限', () => {
    const item = {
      currentMinuteRequests: 12,
      activeConcurrentRequests: 2,
      requestsPerMinute: 60,
      maxConcurrentRequests: null,
    }
    assert.equal(teamRequestSummary(item), '12 次/分钟 · 上限 60')
    assert.equal(teamConcurrencySummary(item), '2 并发 · 不限制')
  })
})
