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

export interface ReportedModelAttemptInput {
  channelId?: string | null
  channelKeyId?: string | null
  upstreamModel?: string | null
  upstreamReportedModel?: string | null
}

export interface ReportedModelLogInput {
  channelId?: string | null
  channelKeyId?: string | null
  upstreamModel?: string | null
  attempts?: ReportedModelAttemptInput[] | null
}

export interface ReportedModelStatus {
  state: 'match' | 'mismatch' | 'unknown'
  reportedModel: string | null
}

/** Resolve the final route's report so retries from other channels cannot mislabel the summary row. */
export function reportedModelStatus(log: ReportedModelLogInput | null | undefined): ReportedModelStatus | null {
  const channelId = String(log?.channelId ?? '').trim()
  const upstreamModel = String(log?.upstreamModel ?? '').trim()
  if (!channelId || !upstreamModel) return null

  const attempt = [...(log?.attempts ?? [])].reverse().find((candidate) =>
    candidate.channelId === channelId &&
    (log?.channelKeyId == null || candidate.channelKeyId === log.channelKeyId) &&
    String(candidate.upstreamModel ?? '').trim() === upstreamModel,
  )
  if (!attempt) return { state: 'unknown', reportedModel: null }

  const reportedModel = String(attempt.upstreamReportedModel ?? '').trim() || null
  if (!reportedModel) return { state: 'unknown', reportedModel: null }

  const normalize = (value: string) => value.replace(/\s+/g, ' ')
  return {
    state: normalize(reportedModel) === normalize(upstreamModel) ? 'match' : 'mismatch',
    reportedModel,
  }
}

export interface AttemptDisplayInput {
  statusCode?: number | null
  upstreamErrorStatusCode?: number | null
  errorCode?: string | null
  upstreamErrorCode?: string | null
  errorMessage?: string | null
  committed?: boolean
  retryable?: boolean
}

export function attemptHasError(attempt: AttemptDisplayInput | null | undefined): boolean {
  return Boolean(attempt?.errorCode || (attempt?.statusCode ?? 0) >= 400 || (attempt?.upstreamErrorStatusCode ?? 0) >= 400)
}

export function attemptResultLabel(attempt: AttemptDisplayInput | null | undefined, hasFollowingAttempt = false): string {
  if (!attempt) return '—'
  if (attempt.errorCode === 'client_disconnect') return '客户端断开'
  if (attempt.errorCode === 'client_cancelled') return '客户端取消'
  // 缺少状态码只说明没收到响应：连接失败与超时前都已发出请求，不能一律判为未到达上游。
  if (attempt.statusCode == null && attempt.errorCode) {
    return attempt.errorCode === 'upstream_unreachable' ? '未连接到上游' : '未收到上游响应'
  }
  if (attemptHasError(attempt)) return hasFollowingAttempt ? '失败，已切换' : '失败，终止'
  return attempt.committed ? '成功' : '完成'
}

export function attemptFailureReason(attempt: AttemptDisplayInput | null | undefined): string {
  if (!attempt) return '—'
  return attempt.errorMessage || attempt.upstreamErrorCode || attempt.errorCode || '—'
}
