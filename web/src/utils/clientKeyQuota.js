export function buildClientKeyPayload(form, expiresAt) {
  return {
    name: form.name.trim(),
    team: optionalText(form.team),
    enabled: form.enabled,
    expiresAt,
    dailyTokenLimit: optionalInteger(form.dailyTokenLimit),
    dailyUsdLimit: optionalText(form.dailyUsdLimit),
    dailyCnyLimit: optionalText(form.dailyCnyLimit),
    dailyEurLimit: optionalText(form.dailyEurLimit),
    maxConcurrentRequests: optionalInteger(form.maxConcurrentRequests),
  }
}

export function quotaSummary(key) {
  const quotas = []
  if (key.dailyTokenLimit != null) {
    quotas.push(`${key.quotaUsage?.dailyTokens ?? 0} / ${key.dailyTokenLimit} token`)
  }
  for (const currency of ['USD', 'CNY', 'EUR']) {
    const suffix = `${currency[0]}${currency.slice(1).toLowerCase()}`
    const limit = key[`daily${suffix}Limit`]
    if (limit != null) {
      const usageField = `daily${suffix}Cost`
      quotas.push(`${currency} ${key.quotaUsage?.[usageField] ?? '0'} / ${limit}`)
    }
  }
  if (key.maxConcurrentRequests != null) {
    quotas.push(`并发 ${key.quotaUsage?.concurrentRequests ?? 0} / ${key.maxConcurrentRequests}`)
  }
  return quotas.length ? quotas.join(' · ') : '未设置限额'
}

function optionalInteger(value) {
  const text = String(value ?? '').trim()
  return text ? Number(text) : null
}

function optionalText(value) {
  const text = String(value ?? '').trim()
  return text || null
}
