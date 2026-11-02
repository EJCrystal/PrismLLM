import type { HealthMetrics, HealthState, HealthWindow } from '../types/api'

export const HEALTH_WINDOWS: { id: HealthWindow; label: string }[] = [
  { id: '24h', label: '24 小时' }, { id: '7d', label: '7 天' }, { id: '15d', label: '15 天' }, { id: '30d', label: '30 天' },
]
export const HEALTH_LABELS: Record<HealthState, string> = { HEALTHY: '健康', WARNING: '需关注', CRITICAL: '异常', UNKNOWN: '未知', DISABLED: '已停用' }
export function healthSymbol(state: HealthState): string {
  return state === 'HEALTHY' ? '✓' : state === 'WARNING' || state === 'CRITICAL' ? '!' : state === 'DISABLED' ? '−' : '?'
}
export function healthPercent(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) return '—'
  if (rate > 0.999 && rate < 1) return '>99.9%'
  return `${(rate * 100).toFixed(1)}%`
}
/** Trend colours describe observed error bands, not a guarantee of current availability. */
export function healthBucketState(metrics: HealthMetrics, minimumSamples = 20): HealthState {
  if (metrics.attempts < minimumSamples || metrics.successRate == null) return 'UNKNOWN'
  const rate = metrics.failures / metrics.attempts
  return rate >= 0.2 ? 'CRITICAL' : rate >= 0.05 ? 'WARNING' : 'HEALTHY'
}
