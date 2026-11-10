import type {
  OAuthAccount,
  OAuthAccountImportRequest,
  OAuthAccountPolicy,
  OAuthAccountPolicyRequest,
  OAuthQuota,
  OAuthQuotaWindow,
} from '../types/api'

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
  if (isOAuthQuotaBlocked(account, now)) parts.push('额度用尽')
  return parts.join(' · ')
}

export interface OAuthQuotaWindowView {
  key: string
  label: string
  usedPercent: number
  remainingPercent: number
  resetsAt: number | null
}

export interface OAuthQuotaGroupView {
  key: string
  label: string
  windows: OAuthQuotaWindowView[]
}

function quotaWindowView(
  key: string,
  window: OAuthQuotaWindow | null,
  fallbackLabel: string,
): OAuthQuotaWindowView | null {
  if (!window) return null
  return {
    key,
    label: window.windowSeconds ? formatWindowSeconds(window.windowSeconds) : fallbackLabel,
    usedPercent: Math.max(0, Math.min(100, window.usedPercent)),
    remainingPercent: Math.max(0, Math.min(100, window.remainingPercent)),
    resetsAt: window.resetsAt,
  }
}

/** 上游额度窗口；按窗口时长标注（如 5 小时 / 7 天），缺失时退回主/次窗口。 */
export function oauthQuotaWindows(quota: OAuthQuota | null | undefined): OAuthQuotaWindowView[] {
  if (!quota) return []
  return [
    quotaWindowView('primary', quota.primary, '主窗口'),
    quotaWindowView('secondary', quota.secondary, '次窗口'),
  ].filter((window): window is OAuthQuotaWindowView => window != null)
}

/**
 * 把上游的模型家族名收敛成看板上的短标签。只做大小写与连接词归一：
 * "Gemini 4 Argon" 这类带版本的单模型分组单独占用额度，必须原样保留；
 * 只有 Claude 的分组也不能被误标成 "Claude & GPT"。
 */
export function oauthQuotaGroupLabel(name: string): string {
  const trimmed = name.trim()
  if (/^claude\s+and\s+gpt$/i.test(trimmed)) return 'Claude & GPT'
  return trimmed
}

/**
 * Antigravity 按模型家族分组的额度；没有分组（如 ChatGPT）时返回空数组，
 * 由调用方回退到 [oauthQuotaWindows]。
 */
export function oauthQuotaGroups(quota: OAuthQuota | null | undefined): OAuthQuotaGroupView[] {
  if (!quota?.groups?.length) return []
  return quota.groups
    .map((group, groupIndex) => ({
      key: `${groupIndex}:${group.name}`,
      label: oauthQuotaGroupLabel(group.name),
      windows: group.windows
        .map((window, windowIndex) =>
          quotaWindowView(`${groupIndex}:${windowIndex}`, window, '窗口'),
        )
        .filter((window): window is OAuthQuotaWindowView => window != null),
    }))
    .filter((group) => group.windows.length > 0)
}

const PROVIDER_PROTOCOLS: Record<string, string[]> = {
  chatgpt: ['responses'],
  antigravity: ['gemini'],
}

/** 订阅账号的官方端点协议；未知提供方没有可测协议。 */
export function oauthProviderProtocols(provider: string): string[] {
  return PROVIDER_PROTOCOLS[provider] ?? []
}

/** 连通性测试可选的模型：端点渠道模型与账号授权模型的交集。 */
export function oauthTestModels(
  account: Pick<OAuthAccount, 'models' | 'channelIds'>,
  channels: Array<{ id: string; models: string[] }>,
): string[] {
  const available = availableOAuthModels(channels, account.channelIds)
  if (account.models.length === 0) return available
  return available.filter((model) => account.models.includes(model))
}

/** 账号设置里可勾选的模型：来自账号关联渠道的模型并集。 */
export function availableOAuthModels(
  channels: Array<{ id: string; models: string[] }>,
  channelIds: string[],
): string[] {
  const selectedChannels = new Set(channelIds)
  const models = new Set<string>()
  for (const channel of channels) {
    if (!selectedChannels.has(channel.id)) continue
    for (const model of channel.models) {
      if (model.trim()) models.add(model.trim())
    }
  }
  return [...models].sort()
}

/** 保存时的模型授权：授权全部（空数组）或勾选 + 自定义模型去重。 */
export function buildOAuthModels(allModels: boolean, selected: string[], custom: string): string[] {
  if (allModels) return []
  const models = new Set(selected.map((model) => model.trim()).filter(Boolean))
  for (const model of custom.split(/[,\n]/)) {
    const value = model.trim()
    if (value) models.add(value)
  }
  return [...models].sort()
}

/** 使用率对应的展示等级：用尽 / 偏高 / 正常。 */
export function oauthQuotaTone(usedPercent: number): 'success' | 'warning' | 'danger' {
  if (usedPercent >= 100) return 'danger'
  if (usedPercent >= 80) return 'warning'
  return 'success'
}

export function formatQuotaNumber(value: number): string {
  if (!Number.isFinite(value)) return '—'
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

/** 使用率按一位小数展示；非零但不足 0.1% 时仍显示 0.1，避免看起来完全没用过。 */
export function formatUsedPercent(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0'
  if (value >= 100) return '100'
  if (value < 0.1) return '0.1'
  return formatQuotaNumber(Math.round(value * 10) / 10)
}

/** 额度窗口的重置倒计时；已到重置时间或没有重置时间返回 null。 */
export function formatQuotaReset(resetsAt: number | null | undefined, now: number): string | null {
  if (resetsAt == null || !Number.isFinite(resetsAt)) return null
  const minutes = Math.ceil((resetsAt - now) / 60_000)
  if (minutes <= 0) return null
  if (minutes >= 1_440) return `${Math.ceil(minutes / 1_440)} 天后重置`
  if (minutes >= 60) return `${Math.ceil(minutes / 60)} 小时后重置`
  return `${minutes} 分钟后重置`
}

/** credits 余额；上游没有给出时返回 null。 */
export function oauthQuotaCreditsLabel(quota: OAuthQuota | null | undefined): string | null {
  if (!quota) return null
  if (quota.creditsUnlimited) return 'Credits 不限'
  if (quota.creditsBalance != null) return `Credits ${formatQuotaNumber(quota.creditsBalance)}`
  return null
}

export function isOAuthQuotaBlocked(account: Pick<OAuthAccount, 'runtime'>, now: number): boolean {
  const until = account.runtime.quotaBlockedUntil
  return until != null && until > now
}

/** 额度读取失败时的简短说明；未知错误码保留原文便于排查。 */
export function oauthQuotaErrorLabel(error: string | null | undefined): string | null {
  if (!error) return null
  if (error === 'project_missing') return '未获取到 Antigravity 项目'
  if (error === 'quota_read_timeout') return '额度读取超时'
  if (error === 'credential_unavailable') return '凭据不可用，请刷新 token'
  return `读取失败：${error}`
}

/**
 * 额度读取失败时的处理建议。项目发现失败既可能是账号未开通，也可能是发现请求失败，
 * 所以只把开通列为可能的处理步骤，不能断定账号未开通。
 */
export function oauthQuotaErrorHint(error: string | null | undefined): string | null {
  if (error !== 'project_missing') return null
  return '若账号尚未开通，请先用同一个 Google 账号登录一次 Antigravity 应用，再刷新额度'
}

/** 额度单元格的紧凑文本；无读数时返回 null。 */
export function oauthQuotaSummary(quota: OAuthQuota | null | undefined): string | null {
  if (!quota) return null
  const parts = oauthQuotaWindows(quota).map(
    (window) => `${window.label} 已用 ${formatUsedPercent(window.usedPercent)}%`,
  )
  const credits = oauthQuotaCreditsLabel(quota)
  if (credits) parts.push(credits)
  return parts.length > 0 ? parts.join(' · ') : null
}

/**
 * 浏览器登录回调的提交前检查。回调必须是完整 URL 或裸查询串，
 * 且包含 code（成功）或 error（授权失败）；空值与明显错误在此拦下。
 */
export function oauthLoginCallbackError(raw: string): string | null {
  const text = raw.trim()
  if (!text) return '请粘贴浏览器最终停留的地址'
  if (/(^|[?&])(code|error)=/.test(text)) return null
  return '回调地址缺少 code 参数，请复制登录跳转后浏览器地址栏里的完整 URL'
}

export interface OAuthImportForm {
  name: string
  provider: string
  refreshToken: string
  accountEmail: string
  accountPlan: string
  proxyUrl: string
  accessToken: string
  accessTokenExpiresAt: number | null
  channelIds: string[]
  priority: number
}

/**
 * 校验并转换导入表单。refresh token 必填；access token 与到期时间必须成对出现，
 * 避免后端保存一个无法使用的 token。
 */
export function buildOAuthImportPayload(
  form: OAuthImportForm,
  now: number = Date.now(),
  options: { allowEmptyRefreshToken?: boolean } = {},
): OAuthAccountImportRequest {
  const provider = form.provider.trim()
  if (!provider) throw new Error('请选择账号提供方')
  const refreshToken = form.refreshToken.trim()
  if (!refreshToken && !options.allowEmptyRefreshToken) throw new Error('请填写 refresh token')
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
  const name = (form.name ?? '').trim()
  if (name) payload.name = name
  const channelIds = form.channelIds ?? []
  if (channelIds.length > 0) payload.channelIds = channelIds
  if ((form.priority ?? 1) !== 1) payload.priority = form.priority
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
