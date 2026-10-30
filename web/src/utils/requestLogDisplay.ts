const REASONING_LEVELS = new Set(['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'])

export function reasoningEffortLabel(value: string | null | undefined): string {
  const normalized = String(value ?? '').trim().toLowerCase()
  return normalized || 'none'
}

export function reasoningEffortClass(value: string | null | undefined): string {
  const label = reasoningEffortLabel(value)
  return REASONING_LEVELS.has(label) ? `reasoning-${label}` : 'reasoning-custom'
}

export interface TokenUsageInput {
  usageKnown?: boolean
  upstreamAttempts?: number | null
  channelId?: string | null
  upstreamModel?: string | null
}

export function tokenUsageState(log: TokenUsageInput | null | undefined): 'known' | 'unknown' | 'no-request' {
  if (log?.usageKnown === true) return 'known'
  const attempts = Number(log?.upstreamAttempts)
  if (attempts > 0 || (log?.channelId && log?.upstreamModel)) return 'unknown'
  return 'no-request'
}

export function tokenUsageLabel(log: TokenUsageInput | null | undefined): string {
  return tokenUsageState(log) === 'no-request' ? '无请求' : '未知'
}
