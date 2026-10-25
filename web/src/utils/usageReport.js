export function usageCostEntries(costs) {
  return Object.entries(costs || {})
    .filter(([, value]) => value != null)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([currency, value]) => ({ currency, value: String(value) }))
}

export function reportRange(days, now) {
  const safeDays = Math.min(366, Math.max(1, Number(days) || 1))
  return { from: now - safeDays * 86_400_000, to: now }
}
