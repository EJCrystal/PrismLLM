export const DEFAULT_CHANNEL_KEY_RATE_LIMIT = Object.freeze({
  enabled: false,
  requestsPerMinute: null,
  maxConcurrentRequests: null,
})

export function normalizeChannelKeyRateLimit(rule = {}) {
  const source = rule && typeof rule === 'object' ? rule : {}
  return {
    ...source,
    enabled: source.enabled === true,
    requestsPerMinute: optionalNumber(source.requestsPerMinute),
    maxConcurrentRequests: optionalNumber(source.maxConcurrentRequests),
  }
}

export function validateChannelKeyRateLimit(rule = {}) {
  const normalized = normalizeChannelKeyRateLimit(rule)
  if (normalized.requestsPerMinute !== null) {
    assertIntegerInRange(normalized.requestsPerMinute, 1, 1_000_000, '每分钟请求数必须是 1..1000000 的整数')
  }
  if (normalized.maxConcurrentRequests !== null) {
    assertIntegerInRange(normalized.maxConcurrentRequests, 1, 100_000, '最大并发请求数必须是 1..100000 的整数')
  }
  if (normalized.enabled && normalized.requestsPerMinute === null && normalized.maxConcurrentRequests === null) {
    throw new Error('启用限流时至少填写每分钟请求数或最大并发请求数')
  }
  return normalized
}

export function buildChannelKeyRateLimitPayload(rule = {}) {
  return validateChannelKeyRateLimit(rule)
}

export function channelKeyRateLimitSummary(rule = {}) {
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

function optionalNumber(value) {
  if (value === '' || value === null || value === undefined) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : value
}

function assertIntegerInRange(value, min, max, message) {
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(message)
}

function formatNumber(value) {
  return Number(value).toLocaleString('zh-CN')
}
