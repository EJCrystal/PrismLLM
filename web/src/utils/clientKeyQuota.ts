import type { ClientKey, ClientKeyRequest } from '../types/api'

export interface ClientKeyFormDraft {
  name: string
  enabled: boolean
  dailyTokenLimit: number | string | null
  dailyUsdLimit: number | string | null
  maxConcurrentRequests: number | string | null
  requestsPerMinute: number | string | null
}

export function buildClientKeyPayload(form: ClientKeyFormDraft, expiresAt: number | null): ClientKeyRequest {
  return {
    name: form.name.trim(),
    enabled: form.enabled,
    expiresAt,
    dailyTokenLimit: optionalInteger(form.dailyTokenLimit),
    dailyUsdLimit: optionalText(form.dailyUsdLimit),
    maxConcurrentRequests: optionalInteger(form.maxConcurrentRequests),
    requestsPerMinute: optionalInteger(form.requestsPerMinute),
  }
}

export function quotaSummary(key: ClientKey): string {
  const quotas: string[] = []
  if (key.dailyTokenLimit != null) {
    quotas.push(`${key.quotaUsage?.dailyTokens ?? 0} / ${key.dailyTokenLimit} token`)
  }
  if (key.dailyUsdLimit != null) {
    quotas.push(`$${key.quotaUsage?.dailyUsdCost ?? '0'} / ${key.dailyUsdLimit}`)
  }
  if (key.requestsPerMinute != null) {
    quotas.push(`${key.quotaUsage?.currentMinuteRequests ?? 0} / ${key.requestsPerMinute} 次/分钟`)
  }
  return quotas.length ? quotas.join(' · ') : '未设置限额'
}

/** 实时并发占用；配置了并发上限时同时展示上限。 */
export function concurrencySummary(key: ClientKey, concurrentRequests = key.quotaUsage?.concurrentRequests ?? 0): string {
  return key.maxConcurrentRequests != null
    ? `并发 ${concurrentRequests} / ${key.maxConcurrentRequests}`
    : `在途 ${concurrentRequests}`
}

function optionalInteger(value: unknown): number | null {
  const text = String(value ?? '').trim()
  return text ? Number(text) : null
}

function optionalText(value: unknown): string | null {
  const text = String(value ?? '').trim()
  return text || null
}
