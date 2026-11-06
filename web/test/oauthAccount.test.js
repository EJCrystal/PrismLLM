import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  buildOAuthImportPayload,
  buildOAuthPolicyPayload,
  formatCooldownRemaining,
  formatWindowSeconds,
  isOAuthAccountCooling,
  oauthAuthStateBadge,
  oauthAuthStateLabel,
  oauthPolicySummary,
  oauthProviderLabel,
  oauthRuntimeSummary,
} from '../src/utils/oauthAccount'

const NOW = 1_762_400_000_000

function importForm(overrides = {}) {
  return {
    provider: 'chatgpt',
    refreshToken: '  refresh-token  ',
    accountEmail: '',
    accountPlan: '',
    proxyUrl: '',
    accessToken: '',
    accessTokenExpiresAt: null,
    ...overrides,
  }
}

describe('oauthAccount: 订阅账号看板展示', () => {
  it('提供方与授权状态使用可读标签和徽标', () => {
    assert.equal(oauthProviderLabel('chatgpt'), 'ChatGPT')
    assert.equal(oauthProviderLabel('antigravity'), 'Antigravity')
    assert.equal(oauthProviderLabel('custom'), 'custom')
    assert.equal(oauthAuthStateLabel('ACTIVE'), '正常')
    assert.equal(oauthAuthStateLabel('REAUTH_REQUIRED'), '需重新授权')
    assert.equal(oauthAuthStateBadge('ACTIVE'), 'success')
    assert.equal(oauthAuthStateBadge('REAUTH_REQUIRED'), 'danger')
  })

  it('窗口时长按整单位展示，非整单位退回秒数', () => {
    assert.equal(formatWindowSeconds(10_800), '3 小时')
    assert.equal(formatWindowSeconds(604_800), '7 天')
    assert.equal(formatWindowSeconds(1_800), '30 分钟')
    assert.equal(formatWindowSeconds(90), '90 秒')
    assert.equal(formatWindowSeconds(0), '—')
  })

  it('调度摘要包含窗口、并发与冷却剩余时间', () => {
    const policy = { windowLimit: 40, windowSeconds: 10_800, maxConcurrentRequests: 2 }
    const runtime = { windowUsed: 12, cooldownUntil: NOW + 90_000, consecutiveRateLimits: 2, activeRequests: 1 }
    assert.equal(oauthPolicySummary(policy), '40 次 / 3 小时 · 并发 2')
    assert.equal(oauthPolicySummary({ ...policy, windowLimit: null }), '不限窗口 · 并发 2')
    assert.equal(oauthRuntimeSummary({ policy, runtime }, NOW), '窗口 12/40 · 并发 1/2 · 冷却剩余 2 分钟')
    assert.equal(isOAuthAccountCooling({ runtime }, NOW), true)
    assert.equal(isOAuthAccountCooling({ runtime: { ...runtime, cooldownUntil: NOW } }, NOW), false)
  })

  it('冷却剩余时间向上取整并按量级选择单位', () => {
    assert.equal(formatCooldownRemaining(NOW + 500, NOW), '1 秒')
    assert.equal(formatCooldownRemaining(NOW + 61_000, NOW), '2 分钟')
    assert.equal(formatCooldownRemaining(NOW + 3_600_000, NOW), '1 小时')
    assert.equal(formatCooldownRemaining(null, NOW), null)
    assert.equal(formatCooldownRemaining(NOW - 1, NOW), null)
  })
})

describe('oauthAccount: 导入与策略表单', () => {
  it('只提交非空的可选字段，并裁剪 refresh token', () => {
    const payload = buildOAuthImportPayload(importForm({ accountEmail: ' free@example.com ', accountPlan: 'free' }), NOW)
    assert.deepEqual(payload, {
      provider: 'chatgpt',
      refreshToken: 'refresh-token',
      accountEmail: 'free@example.com',
      accountPlan: 'free',
    })
  })

  it('access token 与到期时间必须成对出现且未过期', () => {
    assert.throws(
      () => buildOAuthImportPayload(importForm({ accessToken: 'at' }), NOW),
      /必须同时填写到期时间/,
    )
    assert.throws(
      () => buildOAuthImportPayload(importForm({ accessTokenExpiresAt: NOW + 60_000 }), NOW),
      /必须同时填写 access token/,
    )
    assert.throws(
      () => buildOAuthImportPayload(importForm({ accessToken: 'at', accessTokenExpiresAt: NOW }), NOW),
      /必须晚于当前时间/,
    )
    const payload = buildOAuthImportPayload(
      importForm({ accessToken: ' at ', accessTokenExpiresAt: NOW + 60_000, proxyUrl: ' http://127.0.0.1:7890 ' }),
      NOW,
    )
    assert.equal(payload.accessToken, 'at')
    assert.equal(payload.accessTokenExpiresAt, NOW + 60_000)
    assert.equal(payload.proxyUrl, 'http://127.0.0.1:7890')
  })

  it('缺少 refresh token 或提供方时拒绝提交', () => {
    assert.throws(() => buildOAuthImportPayload(importForm({ refreshToken: '   ' }), NOW), /refresh token/)
    assert.throws(() => buildOAuthImportPayload(importForm({ provider: '  ' }), NOW), /提供方/)
  })

  it('策略表单把空窗口转为不限制，并校验取值范围', () => {
    assert.deepEqual(
      buildOAuthPolicyPayload({ windowLimit: '', windowSeconds: '10800', maxConcurrentRequests: '1' }),
      { windowLimit: null, windowSeconds: 10_800, maxConcurrentRequests: 1 },
    )
    assert.deepEqual(
      buildOAuthPolicyPayload({ windowLimit: '40', windowSeconds: 3600, maxConcurrentRequests: 2 }),
      { windowLimit: 40, windowSeconds: 3_600, maxConcurrentRequests: 2 },
    )
    assert.throws(() => buildOAuthPolicyPayload({ windowLimit: '0', windowSeconds: '3600', maxConcurrentRequests: '1' }), /1\.\.100000/)
    assert.throws(() => buildOAuthPolicyPayload({ windowLimit: '', windowSeconds: '30', maxConcurrentRequests: '1' }), /60\.\.604800/)
    assert.throws(() => buildOAuthPolicyPayload({ windowLimit: '', windowSeconds: '3600', maxConcurrentRequests: '0' }), /1\.\.1000/)
    assert.throws(() => buildOAuthPolicyPayload({ windowLimit: '1.5', windowSeconds: '3600', maxConcurrentRequests: '1' }), /必须是整数/)
  })
})
