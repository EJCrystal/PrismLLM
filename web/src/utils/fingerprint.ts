import type { FingerprintCandidate, FingerprintDecision } from '../types/api'

/** 参考库时间可能带 6 位小数秒；截断到毫秒后再解析，兼容只接受 3 位小数秒的浏览器。 */
export function parseFingerprintBankTime(value: string | null | undefined): number {
  const text = String(value ?? '').trim().replace(/(\.\d{3})\d+/, '$1')
  const timestamp = Date.parse(text)
  return Number.isFinite(timestamp) ? timestamp : 0
}

export function fingerprintDecisionLabel(decision: FingerprintDecision): string {
  switch (decision) {
    case 'scored':
      return '已完成排名'
    case 'partial':
      return '部分样本排名'
    case 'not_model':
      return '不像语言模型回答'
    default:
      return '需要三条完整回答'
  }
}

export function fingerprintDecisionTone(decision: FingerprintDecision): 'success' | 'warning' | 'danger' {
  if (decision === 'scored') return 'success'
  if (decision === 'not_model') return 'danger'
  return 'warning'
}

/** 概率是参考库内的闭集相对值；极小的值不显示成误导性的 0%。 */
export function formatFingerprintProbability(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value < 0) return '—'
  const percent = value * 100
  if (percent === 0) return '0%'
  if (percent < 0.01) return '<0.01%'
  const digits = percent >= 99.99 ? 4 : percent >= 1 ? 2 : 3
  const text = percent.toFixed(digits).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
  return `${text}%`
}

export function formatFingerprintScore(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—'
  return value.toFixed(3)
}

/** 候选条宽度按本次显示的分数区间归一化；概率过低时仍能看出相对顺序。 */
export function fingerprintBarWidths(results: FingerprintCandidate[]): number[] {
  if (!results.length) return []
  const scores = results.map((result) => result.score)
  const maximum = Math.max(...scores)
  const minimum = Math.min(...scores)
  return scores.map((score) => {
    if (!Number.isFinite(score)) return 4
    if (!Number.isFinite(maximum) || !Number.isFinite(minimum) || maximum === minimum) return 50
    return Math.round(6 + (94 * (score - minimum)) / (maximum - minimum))
  })
}

export function fingerprintSampleStatus(accepted: boolean, parsedNumbers: number, minimumNumbers: number): string {
  if (accepted) return `有效 ${parsedNumbers} 个整数`
  return `仅 ${parsedNumbers} 个整数，至少需要 ${minimumNumbers} 个`
}