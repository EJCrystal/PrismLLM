/**
 * 冷却展示工具：把后端返回的冷却截止时间与触发原因转成管理台可读文案。
 */

export interface CooldownSummaryLike {
  coolingCandidates?: number
  totalCandidates?: number
  cooldownUntil?: number | null
  cooldownReason?: string | null
}

const COOLDOWN_REASON_LABELS: Record<string, string> = {
  transient: '瞬时错误',
  rate_limited: '上游限流',
  auth_failure: '鉴权失败',
  ttft_slow: '首 token 过慢',
}

export function cooldownReasonLabel(reason?: string | null): string {
  if (!reason) return ''
  return COOLDOWN_REASON_LABELS[reason] ?? reason
}

export function formatCooldownRemaining(until: number | null | undefined, now: number): string {
  if (until == null) return ''
  const remaining = until - now
  if (remaining <= 0) return '即将恢复'
  const totalSeconds = Math.ceil(remaining / 1000)
  if (totalSeconds < 60) return `${totalSeconds} 秒`
  const totalMinutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (totalMinutes < 60) return seconds ? `${totalMinutes} 分 ${seconds} 秒` : `${totalMinutes} 分`
  const totalHours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  if (totalHours < 24) return minutes ? `${totalHours} 小时 ${minutes} 分` : `${totalHours} 小时`
  const days = Math.floor(totalHours / 24)
  const hours = totalHours % 24
  return hours ? `${days} 天 ${hours} 小时` : `${days} 天`
}

export function cooldownSummaryText(row: CooldownSummaryLike | null | undefined, now: number): string {
  const cooling = row?.coolingCandidates ?? 0
  if (!row || cooling <= 0) return ''
  const parts = [`${cooling}/${row.totalCandidates ?? 0} 候选冷却`]
  const remaining = formatCooldownRemaining(row.cooldownUntil, now)
  if (remaining) parts.push(remaining)
  const reason = cooldownReasonLabel(row.cooldownReason)
  if (reason) parts.push(reason)
  return parts.join(' · ')
}
