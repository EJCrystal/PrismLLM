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

export interface ProtocolEndpointInfo {
  value: string
  label: string
  shortName: string
  apiFormat: string
  endpointPath: string
  known: boolean
}

interface ProtocolEndpointDefinition {
  label: string
  shortName: string
  apiFormat: string
  endpointPath: string
}

const PROTOCOL_ENDPOINTS: Record<string, ProtocolEndpointDefinition> = {
  chat_completions: {
    label: 'OpenAI Chat Completions',
    shortName: 'Chat',
    apiFormat: 'openai/chat_completions',
    endpointPath: '/v1/chat/completions',
  },
  responses: {
    label: 'OpenAI Responses',
    shortName: 'Responses',
    apiFormat: 'openai/responses',
    endpointPath: '/v1/responses',
  },
  messages: {
    label: 'Anthropic Messages',
    shortName: 'Messages',
    apiFormat: 'anthropic/messages',
    endpointPath: '/v1/messages',
  },
  gemini: {
    label: 'Google Gemini',
    shortName: 'Gemini',
    apiFormat: 'gemini/contents',
    endpointPath: '/v1beta/models/{model}:generateContent',
  },
}

export function protocolEndpoint(value: string | null | undefined, stream?: boolean | null): ProtocolEndpointInfo {
  const normalized = String(value ?? '').trim()
  if (!Object.hasOwn(PROTOCOL_ENDPOINTS, normalized)) {
    const label = protocolLabel(normalized)
    return { value: normalized, label, shortName: label, apiFormat: label, endpointPath: '', known: false }
  }
  const definition = PROTOCOL_ENDPOINTS[normalized]
  const endpointPath = normalized === 'gemini' && stream
    ? '/v1beta/models/{model}:streamGenerateContent?alt=sse'
    : definition.endpointPath
  return { value: normalized, ...definition, endpointPath, known: true }
}

export interface ProtocolConversionInfo {
  converted: boolean
  inbound: ProtocolEndpointInfo
  outbound: ProtocolEndpointInfo | null
  title: string
}

export function effectiveProtocolEndpoint(info: ProtocolConversionInfo): ProtocolEndpointInfo {
  if (!info.inbound.value && info.outbound) {
    return info.outbound
  }
  return info.inbound
}

export function protocolConversion(
  inbound: string | null | undefined,
  outbound: string | null | undefined,
  stream?: boolean | null,
  upstreamAttempts?: number | null,
): ProtocolConversionInfo {
  const from = protocolEndpoint(inbound, stream)
  const to = String(outbound ?? '').trim() ? protocolEndpoint(outbound, stream) : null
  if (!from.value) {
    const outboundReason = to ? to.label : ((upstreamAttempts ?? 0) > 0 ? '出站协议未知' : '未发起上游请求')
    return {
      converted: false,
      inbound: from,
      outbound: to,
      title: to
        ? `入站协议：未知\n出站协议：${to.label}（${to.apiFormat}）`
        : `入站协议：未知\n出站协议：未知（${outboundReason}）`,
    }
  }
  if (!to) {
    const outboundReason = (upstreamAttempts ?? 0) > 0 ? '出站协议未知' : '未发起上游请求'
    return {
      converted: false,
      inbound: from,
      outbound: null,
      title: `入站协议：${from.label}（${from.apiFormat}）\n出站协议：未知（${outboundReason}）`,
    }
  }
  if (from.value === to.value) {
    return {
      converted: false,
      inbound: from,
      outbound: to,
      title: `入站 / 出站协议一致，透传未转换（${to.apiFormat}）`,
    }
  }
  return {
    converted: true,
    inbound: from,
    outbound: to,
    title: `入站协议：${from.label}（${from.apiFormat}）\n出站协议：已由入站协议转换（${to.apiFormat}）`,
  }
}

export interface ReportedModelAttemptInput {
  channelId?: string | null
  channelKeyId?: string | null
  upstreamModel?: string | null
  upstreamReportedModel?: string | null
  skipped?: boolean
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
    !candidate.skipped &&
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
  skipped?: boolean
  skipReason?: string | null
}

const SKIP_REASON_LABELS: Record<string, string> = {
  cooldown: '冷却中',
}

export function skipReasonLabel(value: string | null | undefined): string {
  const normalized = String(value ?? '').trim()
  if (!normalized) return ''
  return SKIP_REASON_LABELS[normalized] || normalized
}

export function attemptHasError(attempt: AttemptDisplayInput | null | undefined): boolean {
  if (attempt?.skipped) return false
  return Boolean(attempt?.errorCode || (attempt?.statusCode ?? 0) >= 400 || (attempt?.upstreamErrorStatusCode ?? 0) >= 400)
}

export function attemptResultLabel(attempt: AttemptDisplayInput | null | undefined, hasFollowingAttempt = false): string {
  if (!attempt) return '—'
  if (attempt.skipped) return attempt.skipReason === 'cooldown' ? '跳过（冷却）' : '路由跳过'
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
  if (attempt.skipped) return skipReasonLabel(attempt.skipReason) || '路由跳过'
  return attempt.errorMessage || attempt.upstreamErrorCode || attempt.errorCode || '—'
}
