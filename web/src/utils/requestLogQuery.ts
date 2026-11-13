export const requestLogTimePresets = [
  { value: 'last15m', label: '最近 15 分钟' },
  { value: 'last1h', label: '最近 1 小时' },
  { value: 'last24h', label: '最近 24 小时' },
  { value: 'custom', label: '自定义时间' },
  { value: 'all', label: '全部保留日志' },
]

export function requestLogPresetRange(preset: string, now = Date.now()): { from?: number; to?: number } {
  const durations: Record<string, number> = { last15m: 900_000, last1h: 3_600_000, last24h: 86_400_000 }
  const duration = durations[preset]
  return duration ? { from: now - duration, to: now } : {}
}

export function requestLogCustomRange(fromValue: string, toValue: string): { from: number; to: number } {
  const from = fromValue ? new Date(fromValue).getTime() : NaN
  const end = toValue ? new Date(toValue).getTime() : NaN
  if (!Number.isFinite(from) || !Number.isFinite(end)) throw new Error('请选择有效的开始和结束时间。')
  if (from > end) throw new Error('开始时间不能晚于结束时间。')
  return { from, to: end + 60_000 }
}

export function shouldOpenRequestLog(event: MouseEvent | KeyboardEvent): boolean {
  const target = event.target
  if (target instanceof Element && target.closest('button, a, input, select, textarea, summary, [contenteditable="true"]')) return false
  if (typeof window !== 'undefined' && window.getSelection()?.toString()) return false
  return !('key' in event) || ((event.key === 'Enter' || event.key === ' ') && event.target === event.currentTarget)
}
