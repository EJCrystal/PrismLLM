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

const PROTOCOL_LABELS: Record<string, string> = {
  chat_completions: 'Chat Completions',
  responses: 'Responses',
  messages: 'Messages',
  gemini: 'Gemini',
}

export function protocolLabel(value: string | null | undefined): string {
  const normalized = String(value ?? '').trim()
  if (!normalized) return '—'
  return PROTOCOL_LABELS[normalized] || normalized
}

export interface AttemptDisplayInput {
  statusCode?: number | null
  errorCode?: string | null
  upstreamErrorCode?: string | null
  errorMessage?: string | null
  committed?: boolean
  retryable?: boolean
}

export function attemptResultLabel(attempt: AttemptDisplayInput | null | undefined): string {
  if (!attempt) return '—'
  if (attempt.errorCode === 'client_disconnect') return '客户端断开'
  if (attempt.errorCode === 'client_cancelled') return '客户端取消'
  if (attempt.statusCode == null && attempt.errorCode) return '未到达上游'
  if (attempt.errorCode) return attempt.retryable ? '失败，已切换' : '失败，终止'
  return attempt.committed ? '成功' : '完成'
}

export function attemptFailureReason(attempt: AttemptDisplayInput | null | undefined): string {
  if (!attempt) return '—'
  return attempt.errorMessage || attempt.upstreamErrorCode || attempt.errorCode || '—'
}
