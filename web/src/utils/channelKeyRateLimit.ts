import type { ChannelKeyRateLimit, ChannelKeyRateLimitRequest } from '../types/api'

export const DEFAULT_CHANNEL_KEY_RATE_LIMIT: Readonly<ChannelKeyRateLimit> = Object.freeze({
  enabled: false,
  requestsPerMinute: null,
  maxConcurrentRequests: null,
})

export type ChannelKeyRateLimitDraft = Omit<ChannelKeyRateLimit, 'requestsPerMinute' | 'maxConcurrentRequests'> & {
  requestsPerMinute: number | string | null
  maxConcurrentRequests: number | string | null
}

export function normalizeChannelKeyRateLimit(rule: unknown = {}): ChannelKeyRateLimitDraft & Record<string, unknown> {
  const source = (rule && typeof rule === 'object' ? rule : {}) as Record<string, unknown>
  return {
    ...source,
    enabled: source.enabled === true,
    requestsPerMinute: optionalNumber(source.requestsPerMinute),
    maxConcurrentRequests: optionalNumber(source.maxConcurrentRequests),
  }
}

export function validateChannelKeyRateLimit(rule: unknown = {}): ChannelKeyRateLimit & Record<string, unknown> {
  const normalized = normalizeChannelKeyRateLimit(rule)
  const requestsPerMinute = validateOptionalInteger(
    normalized.requestsPerMinute,
    1,
    1_000_000,
    '每分钟请求数必须是 1..1000000 的整数',
  )
  const maxConcurrentRequests = validateOptionalInteger(
    normalized.maxConcurrentRequests,
    1,
    100_000,
    '最大并发请求数必须是 1..100000 的整数',
  )
  if (normalized.enabled && requestsPerMinute === null && maxConcurrentRequests === null) {
    throw new Error('启用限流时至少填写每分钟请求数或最大并发请求数')
  }
  return { ...normalized, requestsPerMinute, maxConcurrentRequests }
}

export function buildChannelKeyRateLimitPayload(rule: unknown = {}): ChannelKeyRateLimitRequest {
  const normalized = validateChannelKeyRateLimit(rule)
  return {
    ...normalized,
    requestsPerMinute: normalized.requestsPerMinute,
    maxConcurrentRequests: normalized.maxConcurrentRequests,
  }
}

export function channelKeyRateLimitSummary(rule: unknown = {}): string {
  const normalized = normalizeChannelKeyRateLimit(rule)
  const parts = []
  if (normalized.requestsPerMinute !== null) {
    parts.push(`RPM ${formatNumber(normalized.requestsPerMinute)}`)
  }
  if (normalized.maxConcurrentRequests !== null) {
    parts.push(`并发 ${formatNumber(normalized.maxConcurrentRequests)}`)
  }
  if (!parts.length) return '未配置，不限流'
  return normalized.enabled ? parts.join(' · ') : `已关闭（保留 ${parts.join(' · ')}）`
}

function optionalNumber(value: unknown): number | string | null {
  if (value === '' || value === null || value === undefined) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : String(value)
}

function validateOptionalInteger(
  value: number | string | null,
  min: number,
  max: number,
  message: string,
): number | null {
  if (value === null) return null
  const number = Number(value)
  if (!Number.isInteger(number) || number < min || number > max) throw new Error(message)
  return number
}

function formatNumber(value: unknown): string {
  return Number(value).toLocaleString('zh-CN')
}
