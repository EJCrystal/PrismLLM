export function formatUsageCosts(costs) {
  const entries = Object.entries(costs || {})
    .filter(([, value]) => value != null)
    .sort(([a], [b]) => a.localeCompare(b))
  return entries.length ? entries.map(([currency, value]) => `${currency} ${value}`).join(' · ') : '—'
}

export function reportRange(days, now) {
  const safeDays = Math.min(366, Math.max(1, Number(days) || 1))
  return { from: now - safeDays * 86_400_000, to: now }
}
