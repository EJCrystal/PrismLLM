import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  availableOAuthModels,
  buildOAuthImportPayload,
  buildOAuthModels,
  buildOAuthPolicyPayload,
  formatCooldownRemaining,
  formatQuotaReset,
  formatUsedPercent,
  formatWindowSeconds,
  isOAuthAccountCooling,
  isOAuthQuotaBlocked,
  oauthAuthStateBadge,
  oauthAuthStateLabel,
  oauthLoginCallbackError,
  oauthPolicySummary,
  oauthProviderLabel,
  oauthProviderProtocols,
  oauthQuotaCreditsLabel,
  oauthQuotaErrorHint,
  oauthQuotaErrorLabel,
  oauthQuotaGroupLabel,
  oauthQuotaGroups,
  oauthQuotaSummary,
  oauthQuotaTone,
  oauthQuotaWindows,
  oauthRuntimeSummary,
  oauthTestModels,
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

describe('oauthAccount: 上游额度展示', () => {
  const quota = {
    primary: { usedPercent: 100, remainingPercent: 0, windowSeconds: 18_000, resetsAt: NOW + 600_000 },
    secondary: { usedPercent: 42.5, remainingPercent: 57.5, windowSeconds: 604_800, resetsAt: NOW + 86_400_000 },
    allowed: false,
    creditsBalance: 12.5,
    creditsUnlimited: false,
    resetCredits: 1,
    checkedAt: NOW,
    error: null,
  }

  it('按窗口时长标注并保留使用率', () => {
    const windows = oauthQuotaWindows(quota)
    assert.equal(windows.length, 2)
    assert.equal(windows[0].label, '5 小时')
    assert.equal(windows[1].label, '7 天')
    assert.equal(windows[1].usedPercent, 42.5)
    assert.deepEqual(oauthQuotaWindows(null), [])
  })

  it('使用率分级用于进度条配色', () => {
    assert.equal(oauthQuotaTone(12), 'success')
    assert.equal(oauthQuotaTone(79.9), 'success')
    assert.equal(oauthQuotaTone(80), 'warning')
    assert.equal(oauthQuotaTone(100), 'danger')
  })

  it('credits 与紧凑摘要', () => {
    assert.equal(oauthQuotaCreditsLabel(quota), 'Credits 12.5')
    assert.equal(oauthQuotaCreditsLabel({ ...quota, creditsUnlimited: true }), 'Credits 不限')
    assert.equal(oauthQuotaCreditsLabel({ ...quota, creditsBalance: null }), null)
    assert.equal(oauthQuotaSummary(quota), '5 小时 已用 100% · 7 天 已用 42.5% · Credits 12.5')
    assert.equal(oauthQuotaSummary(null), null)
  })

  it('Antigravity 按模型家族分组展示窗口', () => {
    const grouped = {
      ...quota,
      groups: [
        {
          name: 'Gemini',
          windows: [
            { usedPercent: 0.61, remainingPercent: 99.39, windowSeconds: 18_000, resetsAt: NOW + 4 * 3_600_000 },
            { usedPercent: 8.68, remainingPercent: 91.32, windowSeconds: 604_800, resetsAt: NOW + 3 * 86_400_000 },
          ],
        },
        {
          name: 'Claude and GPT',
          windows: [
            { usedPercent: 0, remainingPercent: 100, windowSeconds: 18_000, resetsAt: null },
          ],
        },
        { name: '空分组', windows: [] },
      ],
    }
    assert.equal(oauthQuotaGroupLabel('Gemini'), 'Gemini')
    assert.equal(oauthQuotaGroupLabel('Claude and GPT'), 'Claude & GPT')
    assert.equal(oauthQuotaGroupLabel('Claude'), 'Claude')
    assert.equal(oauthQuotaGroupLabel('Gemini 4 Argon'), 'Gemini 4 Argon')

    const groups = oauthQuotaGroups(grouped)
    assert.equal(groups.length, 2)
    assert.equal(groups[0].label, 'Gemini')
    assert.deepEqual(groups[0].windows.map((window) => window.label), ['5 小时', '7 天'])
    assert.equal(groups[1].label, 'Claude & GPT')
    assert.deepEqual(oauthQuotaGroups(quota), [])
    assert.deepEqual(oauthQuotaGroups(null), [])
  })

  it('使用率保留一位小数，重置时间按量级展示', () => {
    assert.equal(formatUsedPercent(0), '0')
    assert.equal(formatUsedPercent(0.04), '0.1')
    assert.equal(formatUsedPercent(0.61), '0.6')
    assert.equal(formatUsedPercent(8.68), '8.7')
    assert.equal(formatUsedPercent(42.5), '42.5')
    assert.equal(formatUsedPercent(100), '100')

    assert.equal(formatQuotaReset(NOW + 30_000, NOW), '1 分钟后重置')
    assert.equal(formatQuotaReset(NOW + 2 * 3_600_000, NOW), '2 小时后重置')
    assert.equal(formatQuotaReset(NOW + 49 * 3_600_000, NOW), '3 天后重置')
    assert.equal(formatQuotaReset(NOW - 1, NOW), null)
    assert.equal(formatQuotaReset(null, NOW), null)
  })

  it('额度读取失败时给出可读原因，未知错误码保留原文', () => {
    assert.equal(oauthQuotaErrorLabel(null), null)
    assert.equal(oauthQuotaErrorLabel(''), null)
    assert.equal(oauthQuotaErrorLabel('project_missing'), '未获取到 Antigravity 项目')
    assert.equal(oauthQuotaErrorLabel('quota_read_timeout'), '额度读取超时')
    assert.equal(oauthQuotaErrorLabel('credential_unavailable'), '凭据不可用，请刷新 token')
    assert.equal(oauthQuotaErrorLabel('upstream_429'), '读取失败：upstream_429')
  })

  it('模型授权只列出所关联渠道的模型，保存时去重并支持全部模型', () => {
    const channels = [
      { id: 'a', models: ['gemini-3-pro', 'gemini-3-flash'] },
      { id: 'b', models: ['gemini-3-flash', 'claude-sonnet'] },
      { id: 'c', models: ['gpt-5'] },
    ]
    assert.deepEqual(availableOAuthModels(channels, ['a', 'b']), ['claude-sonnet', 'gemini-3-flash', 'gemini-3-pro'])
    assert.deepEqual(availableOAuthModels(channels, []), [])
    assert.deepEqual(buildOAuthModels(true, ['gemini-3-pro'], 'gpt-5'), [])
    assert.deepEqual(buildOAuthModels(false, ['gemini-3-pro'], 'gpt-5, gemini-3-pro\n claude-sonnet '), [
      'claude-sonnet',
      'gemini-3-pro',
      'gpt-5',
    ])
    assert.deepEqual(buildOAuthModels(false, [], ''), [])
  })

  it('连通性测试的协议与模型来自官方端点渠道和账号授权', () => {
    const channels = [
      { id: 'provider', models: ['gemini-3-pro', 'gemini-3-flash', 'claude-sonnet'] },
      { id: 'other', models: ['gpt-5'] },
    ]
    assert.deepEqual(oauthProviderProtocols('antigravity'), ['gemini'])
    assert.deepEqual(oauthProviderProtocols('chatgpt'), ['responses'])
    assert.deepEqual(oauthProviderProtocols('custom'), [])
    assert.deepEqual(
      oauthTestModels({ models: [], channelIds: ['provider'] }, channels),
      ['claude-sonnet', 'gemini-3-flash', 'gemini-3-pro'],
    )
    assert.deepEqual(
      oauthTestModels({ models: ['gemini-3-pro'], channelIds: ['provider'] }, channels),
      ['gemini-3-pro'],
    )
    assert.deepEqual(oauthTestModels({ models: [], channelIds: [] }, channels), [])
  })

  it('项目发现失败只把开通列为可能的处理步骤', () => {
    assert.equal(oauthQuotaErrorHint(null), null)
    assert.equal(oauthQuotaErrorHint('quota_read_timeout'), null)
    assert.match(oauthQuotaErrorHint('project_missing'), /若账号尚未开通/)
    assert.match(oauthQuotaErrorHint('project_missing'), /Antigravity 应用/)
  })

  it('浏览器登录回调只接受带 code 或 error 的地址', () => {
    assert.equal(oauthLoginCallbackError(''), '请粘贴浏览器最终停留的地址')
    assert.equal(oauthLoginCallbackError('   '), '请粘贴浏览器最终停留的地址')
    assert.match(oauthLoginCallbackError('http://localhost:1455/auth/callback'), /缺少 code/)
    assert.equal(
      oauthLoginCallbackError('http://localhost:1455/auth/callback?code=abc&state=xyz'),
      null,
    )
    assert.equal(oauthLoginCallbackError('?code=abc&state=xyz'), null)
    assert.equal(oauthLoginCallbackError('code=abc'), null)
    assert.equal(
      oauthLoginCallbackError('http://localhost:1455/auth/callback?error=access_denied&state=xyz'),
      null,
    )
    assert.match(oauthLoginCallbackError('https://example.com/auth/callback'), /缺少 code/)
  })

  it('额度拦截只在后端给出未过期的恢复时间时生效', () => {
    assert.equal(isOAuthQuotaBlocked({ runtime: { quotaBlockedUntil: NOW + 60_000 } }, NOW), true)
    assert.equal(isOAuthQuotaBlocked({ runtime: { quotaBlockedUntil: NOW } }, NOW), false)
    assert.equal(isOAuthQuotaBlocked({ runtime: { quotaBlockedUntil: null } }, NOW), false)
    assert.match(
      oauthRuntimeSummary(
        {
          policy: { windowLimit: null, windowSeconds: 10_800, maxConcurrentRequests: 2 },
          runtime: {
            windowUsed: 0,
            cooldownUntil: null,
            consecutiveRateLimits: 0,
            activeRequests: 0,
            quotaBlockedUntil: NOW + 60_000,
          },
        },
        NOW,
      ),
      /额度用尽/,
    )
  })
})
