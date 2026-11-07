import type { HealthMetrics, HealthState, HealthWindow } from '../types/api'
import { usageCacheHitRate } from './usageReport'

export const HEALTH_WINDOWS: { id: HealthWindow; label: string }[] = [
  { id: '24h', label: '24 小时' }, { id: '7d', label: '7 天' }, { id: '15d', label: '15 天' }, { id: '30d', label: '30 天' },
]
export const HEALTH_LABELS: Record<HealthState, string> = {
  HEALTHY: '健康',
  WARNING: '需关注',
  CRITICAL: '异常',
  UNKNOWN: '未知',
  DISABLED: '已停用',
  NO_CREDENTIALS: '无可用凭据',
}
export function healthSymbol(state: HealthState): string {
  return state === 'HEALTHY' ? '✓' : state === 'WARNING' || state === 'CRITICAL' ? '!' : state === 'DISABLED' || state === 'NO_CREDENTIALS' ? '−' : '?'
}
export function healthPercent(rate: number | null | undefined): string {
  if (rate == null || !Number.isFinite(rate)) return '—'
  // 展示层兜底：百分比不超过 100%，避免异常上游数据把命中率显示成 120%。
  const clamped = Math.min(1, Math.max(0, rate))
  if (clamped > 0.999 && clamped < 1) return '>99.9%'
  return `${(clamped * 100).toFixed(1)}%`
}
/**
 * 渠道详情里的缓存命中率只作展示，不参与健康度判定。
 * 口径与用量报表一致：缓存读 token ÷ 输入 token，输入已包含缓存读与缓存写。
 */
export function healthCacheHitText(
  usage: { inputTokens: number; cachedTokens: number } | null | undefined,
  loading = false,
): string {
  if (usage) return healthPercent(usageCacheHitRate(usage.inputTokens, usage.cachedTokens))
  return loading ? '汇总中…' : '—'
}

export function channelHealthSuccessRateText(
  row: { metrics: { successRate: number | null } } | null | undefined,
  loading: boolean,
  error: string,
): string {
  if (row?.metrics.successRate != null) return healthPercent(row.metrics.successRate)
  if (loading) return '汇总中…'
  if (error) return '读取失败'
  return healthPercent(null)
}
/** Trend colours describe observed error bands, not a guarantee of current availability. */
export function healthBucketState(metrics: HealthMetrics, minimumSamples = 20, warningRate = 0.05, criticalRate = 0.2): HealthState {
  if (metrics.attempts < minimumSamples || metrics.successRate == null) return 'UNKNOWN'
  const rate = metrics.failures / metrics.attempts
  return rate >= criticalRate ? 'CRITICAL' : rate >= warningRate ? 'WARNING' : 'HEALTHY'
}
