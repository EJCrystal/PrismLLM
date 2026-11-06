import type { OAuthAccount, OAuthAccountImportRequest, OAuthAccountPolicy, OAuthAccountPolicyRequest } from '../types/api'

/**
 * 订阅账号看板的展示与表单转换工具。全部为纯函数，便于在 node:test 中直接验证。
 */

const PROVIDER_LABELS: Record<string, string> = {
  chatgpt: 'ChatGPT',
  antigravity: 'Antigravity',
}

export function oauthProviderLabel(provider: string): string {
  return PROVIDER_LABELS[provider] || provider
}

export function oauthAuthStateLabel(state: string): string {
  if (state === 'ACTIVE') return '正常'
  if (state === 'REAUTH_REQUIRED') return '需重新授权'
  return state
}

export function oauthAuthStateBadge(state: string): 'success' | 'danger' | 'neutral' {
  if (state === 'ACTIVE') return 'success'
  if (state === 'REAUTH_REQUIRED') return 'danger'
  return 'neutral'
}

/** 把秒数转成易读时长；非整单位时退回秒数。 */
export function formatWindowSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '—'
  if (seconds % 86_400 === 0) return `${seconds / 86_400} 天`
  if (seconds % 3_600 === 0) return `${seconds / 3_600} 小时`
  if (seconds % 60 === 0) return `${seconds / 60} 分钟`
  return `${seconds} 秒`
}

export function oauthPolicySummary(policy: OAuthAccountPolicy): string {
  const window = policy.windowLimit == null
    ? '不限窗口'
    : `${policy.windowLimit} 次 / ${formatWindowSeconds(policy.windowSeconds)}`
  return `${window} · 并发 ${policy.maxConcurrentRequests}`
}

/** 冷却剩余时长；已结束或未设置返回 null。 */
export function formatCooldownRemaining(until: number | null | undefined, now: number): string | null {
  if (until == null || !Number.isFinite(until) || until <= now) return null
  const seconds = Math.ceil((until - now) / 1_000)
  if (seconds >= 3_600) return `${Math.ceil(seconds / 3_600)} 小时`
  if (seconds >= 60) return `${Math.ceil(seconds / 60)} 分钟`
  return `${seconds} 秒`
}

export function isOAuthAccountCooling(account: Pick<OAuthAccount, 'runtime'>, now: number): boolean {
  return formatCooldownRemaining(account.runtime.cooldownUntil, now) != null
}

export function oauthRuntimeSummary(
  account: Pick<OAuthAccount, 'policy' | 'runtime'>,
  now: number,
): string {
  const parts: string[] = []
  if (account.policy.windowLimit != null) {
    parts.push(`窗口 ${account.runtime.windowUsed}/${account.policy.windowLimit}`)
  }
  parts.push(`并发 ${account.runtime.activeRequests}/${account.policy.maxConcurrentRequests}`)
  const cooldown = formatCooldownRemaining(account.runtime.cooldownUntil, now)
  if (cooldown) parts.push(`冷却剩余 ${cooldown}`)
  return parts.join(' · ')
}

export interface OAuthImportForm {
  provider: string
  refreshToken: string
  accountEmail: string
  accountPlan: string
  proxyUrl: string
  accessToken: string
  accessTokenExpiresAt: number | null
}

/**
 * 校验并转换导入表单。refresh token 必填；access token 与到期时间必须成对出现，
 * 避免后端保存一个无法使用的 token。
 */
export function buildOAuthImportPayload(form: OAuthImportForm, now: number = Date.now()): OAuthAccountImportRequest {
  const provider = form.provider.trim()
  if (!provider) throw new Error('请选择账号提供方')
  const refreshToken = form.refreshToken.trim()
  if (!refreshToken) throw new Error('请填写 refresh token')
  const accessToken = form.accessToken.trim()
  if (accessToken && form.accessTokenExpiresAt == null) {
    throw new Error('填写 access token 时必须同时填写到期时间')
  }
  if (!accessToken && form.accessTokenExpiresAt != null) {
    throw new Error('填写到期时间时必须同时填写 access token')
  }
  if (form.accessTokenExpiresAt != null && form.accessTokenExpiresAt <= now) {
    throw new Error('access token 到期时间必须晚于当前时间')
  }

  const payload: OAuthAccountImportRequest = { provider, refreshToken }
  const email = form.accountEmail.trim()
  if (email) payload.accountEmail = email
  const plan = form.accountPlan.trim()
  if (plan) payload.accountPlan = plan
  const proxy = form.proxyUrl.trim()
  if (proxy) payload.proxyUrl = proxy
  if (accessToken) {
    payload.accessToken = accessToken
    payload.accessTokenExpiresAt = form.accessTokenExpiresAt
  }
  return payload
}

export interface OAuthPolicyForm {
  windowLimit: number | string | null
  windowSeconds: number | string
  maxConcurrentRequests: number | string
}

function parseInteger(value: number | string | null | undefined, label: string): number | null {
  if (value == null) return null
  const text = String(value).trim()
  if (!text) return null
  const parsed = Number(text)
  if (!Number.isInteger(parsed)) throw new Error(`${label}必须是整数`)
  return parsed
}

/** 校验调度策略表单；窗口请求数留空表示不限制。 */
export function buildOAuthPolicyPayload(form: OAuthPolicyForm): OAuthAccountPolicyRequest {
  const windowLimit = parseInteger(form.windowLimit, '窗口请求数')
  if (windowLimit != null && (windowLimit < 1 || windowLimit > 100_000)) {
    throw new Error('窗口请求数必须在 1..100000 之间')
  }
  const windowSeconds = parseInteger(form.windowSeconds, '窗口时长')
  if (windowSeconds == null || windowSeconds < 60 || windowSeconds > 604_800) {
    throw new Error('窗口时长必须在 60..604800 秒之间')
  }
  const maxConcurrentRequests = parseInteger(form.maxConcurrentRequests, '单号最大并发')
  if (maxConcurrentRequests == null || maxConcurrentRequests < 1 || maxConcurrentRequests > 1_000) {
    throw new Error('单号最大并发必须在 1..1000 之间')
  }
  return { windowLimit, windowSeconds, maxConcurrentRequests }
}
