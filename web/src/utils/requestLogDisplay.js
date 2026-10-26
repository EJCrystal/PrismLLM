const REASONING_LEVELS = new Set(['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'])

export function reasoningEffortLabel(value) {
  const normalized = String(value ?? '').trim().toLowerCase()
  return normalized || 'none'
}

export function reasoningEffortClass(value) {
  const label = reasoningEffortLabel(value)
  return REASONING_LEVELS.has(label) ? `reasoning-${label}` : 'reasoning-custom'
}

export function tokenUsageState(log) {
  if (log?.usageKnown === true) return 'known'
  const attempts = Number(log?.upstreamAttempts)
  if (attempts > 0 || (log?.channelId && log?.upstreamModel)) return 'unknown'
  return 'no-request'
}

export function tokenUsageLabel(log) {
  return tokenUsageState(log) === 'no-request' ? '无请求' : '未知'
}
