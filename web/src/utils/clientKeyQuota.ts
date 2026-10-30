import type { ClientKey, ClientKeyRequest } from '../types/api'

export interface ClientKeyFormDraft {
  name: string
  enabled: boolean
  dailyTokenLimit: number | string | null
  dailyUsdLimit: number | string | null
  dailyCnyLimit: number | string | null
  dailyEurLimit: number | string | null
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
    dailyCnyLimit: optionalText(form.dailyCnyLimit),
    dailyEurLimit: optionalText(form.dailyEurLimit),
    maxConcurrentRequests: optionalInteger(form.maxConcurrentRequests),
    requestsPerMinute: optionalInteger(form.requestsPerMinute),
  }
}

export function quotaSummary(key: ClientKey): string {
  const quotas: string[] = []
  if (key.dailyTokenLimit != null) {
    quotas.push(`${key.quotaUsage?.dailyTokens ?? 0} / ${key.dailyTokenLimit} token`)
  }
  for (const currency of ['USD', 'CNY', 'EUR']) {
    const suffix = `${currency[0]}${currency.slice(1).toLowerCase()}` as 'Usd' | 'Cny' | 'Eur'
    const limit = key[`daily${suffix}Limit`]
    if (limit != null) {
      const usageField = `daily${suffix}Cost` as 'dailyUsdCost' | 'dailyCnyCost' | 'dailyEurCost'
      quotas.push(`${currency} ${key.quotaUsage?.[usageField] ?? '0'} / ${limit}`)
    }
  }
  if (key.maxConcurrentRequests != null) {
    quotas.push(`并发 ${key.quotaUsage?.concurrentRequests ?? 0} / ${key.maxConcurrentRequests}`)
  }
  if (key.requestsPerMinute != null) {
    quotas.push(`${key.quotaUsage?.currentMinuteRequests ?? 0} / ${key.requestsPerMinute} 次/分钟`)
  }
  return quotas.length ? quotas.join(' · ') : '未设置限额'
}

function optionalInteger(value: unknown): number | null {
  const text = String(value ?? '').trim()
  return text ? Number(text) : null
}

function optionalText(value: unknown): string | null {
  const text = String(value ?? '').trim()
  return text || null
}
