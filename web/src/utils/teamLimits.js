export function buildTeamLimitPayload(form) {
  return {
    requestsPerMinute: optionalInteger(form.requestsPerMinute),
    maxConcurrentRequests: optionalInteger(form.maxConcurrentRequests),
  }
}

export function teamLimitValidationMessage(team, payload) {
  if (!String(team ?? '').trim()) return '请填写团队名称'
  if (payload.requestsPerMinute == null && payload.maxConcurrentRequests == null) {
    return '每分钟请求数和最大并发数至少设置一项'
  }
  return ''
}

export function teamRequestSummary(item) {
  const usage = item.currentMinuteRequests + ' 次/分钟'
  return item.requestsPerMinute == null ? usage + ' · 不限制' : usage + ' · 上限 ' + item.requestsPerMinute
}

export function teamConcurrencySummary(item) {
  const usage = item.activeConcurrentRequests + ' 并发'
  return item.maxConcurrentRequests == null ? usage + ' · 不限制' : usage + ' · 上限 ' + item.maxConcurrentRequests
}

function optionalInteger(value) {
  const text = String(value ?? '').trim()
  return text ? Number(text) : null
}
