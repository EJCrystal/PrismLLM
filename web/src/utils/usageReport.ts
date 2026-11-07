import type { PriceChannel } from './modelPrice'
import type { UsageGroup, UsageReport, UsageTrend } from '../types/api'

export function usageCostAmount(value: unknown): string {
  return String(value ?? '')
    .trim()
    .replace(/\s*\(?USD\)?\s*$/i, '')
    .trim()
}

export interface UsageCostEntry {
  currency: string
  value: string
}

export function usageCostEntries(costs: Record<string, string | number | null | undefined> | null | undefined): UsageCostEntry[] {
  return Object.entries(costs || {})
    .filter(([, value]) => value != null)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([currency, value]) => ({ currency, value: usageCostAmount(value) }))
}

export function usageCostForCurrency(costs: Record<string, string | number | null | undefined> | null | undefined, currency: string): number {
  const value = usageCostEntries(costs).find((entry) => entry.currency === currency)?.value
  return Number(value) || 0
}

export function usageDeltaTone(current: number, previous: number, inverse = false): string {
  const delta = previous === 0 ? null : ((current - previous) / previous) * 100
  if (delta == null || delta === 0) return 'neutral'
  const positive = delta > 0
  return (positive !== inverse) ? 'success' : 'warning'
}

export const USAGE_REPORT_MAX_SPAN_MS = 30 * 86_400_000

export const USAGE_REPORT_PRESETS = [
  { id: 'today', label: '今天' },
  { id: 'yesterday', label: '昨天' },
  { id: 'last24h', label: '最近 24 小时' },
  { id: 'last7d', label: '最近 7 天' },
  { id: 'last30d', label: '最近 30 天' },
]

export function startOfLocalDay(timestamp: number): number {
  const date = new Date(timestamp)
  date.setHours(0, 0, 0, 0)
  return date.getTime()
}

/**
 * 快捷时间范围以浏览器本地时区切分自然日；“最近 N 小时/天”则是滚动窗口。
 */
export interface TimeRange {
  from: number
  to: number
}

export function usageReportPresetRange(preset: string | null | undefined, now = Date.now()): TimeRange {
  const todayStart = startOfLocalDay(now)
  switch (preset) {
    case 'today':
      return { from: todayStart, to: now }
    case 'yesterday': {
      const d = new Date(todayStart)
      d.setDate(d.getDate() - 1)
      // 某些时区会在午夜跳时，setDate 会保留跳时后的小时；这里强制回到本地零点。
      d.setHours(0, 0, 0, 0)
      return { from: d.getTime(), to: todayStart - 1 }
    }
    case 'last24h':
      return { from: now - 86_400_000, to: now }
    case 'last7d':
      return { from: now - 7 * 86_400_000, to: now }
    case 'last30d':
      return { from: now - USAGE_REPORT_MAX_SPAN_MS, to: now }
    default:
      return { from: todayStart, to: now }
  }
}

export function toDatetimeLocalValue(timestamp: number, includeSeconds = false): string {
  const date = new Date(timestamp)
  const pad = (value: number) => String(value).padStart(2, '0')
  const seconds = includeSeconds
    ? `:${pad(date.getSeconds())}${date.getMilliseconds() ? `.${String(date.getMilliseconds()).padStart(3, '0')}` : ''}`
    : ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}${seconds}`
}

export function fromDatetimeLocalValue(value: string | null | undefined): number {
  if (!value) return NaN
  const timestamp = new Date(value).getTime()
  return Number.isFinite(timestamp) ? timestamp : NaN
}

/**
 * 校验自定义时间范围。返回错误文案；通过校验时返回空字符串。
 */
export function validateUsageReportRange(from: number, to: number, maxSpanMs = USAGE_REPORT_MAX_SPAN_MS): string {
  if (!Number.isFinite(from) || !Number.isFinite(to)) return '请选择完整的开始时间和结束时间。'
  if (from > to) return '开始时间不能晚于结束时间。'
  if (to - from > maxSpanMs) return '查询跨度不能超过 30 天。'
  return ''
}

export function usageRate(requests: number | string | null | undefined, errors: number | string | null | undefined): number {
  const total = Number(requests) || 0
  if (!total) return 0
  return Math.max(0, Math.min(1, 1 - (Number(errors) || 0) / total))
}

export function usagePercent(requests: number | string | null | undefined, errors: number | string | null | undefined): string {
  return `${(usageRate(requests, errors) * 100).toFixed(1)}%`
}

/**
 * 缓存命中率 = 缓存读 token / 输入 token。输入为 0 时无法计算，返回 null。
 * 输入 token 已包含缓存读与缓存写，命中率只统计缓存读。
 */
export function usageCacheHitRate(
  inputTokens: number | string | null | undefined,
  cachedTokens: number | string | null | undefined,
): number | null {
  const input = Number(inputTokens) || 0
  if (input <= 0) return null
  const cached = Math.max(0, Number(cachedTokens) || 0)
  return Math.min(1, cached / input)
}

export function usageGranularityLabel(granularity: string | null | undefined): string {
  return ({ hour: '按小时', day: '按天', week: '按周' } as Record<string, string>)[granularity ?? ''] || '按天'
}

/** 后端给未归因分组用的哨兵 ID，展示层统一翻译成人话。 */
const usageSentinelLabels: Record<string, string> = {
  __unassigned_key__: '未关联调用方密钥',
  __unrouted__: '未完成路由',
  __unresolved_model__: '未解析模型',
  __unassigned_channel_key__: '未使用渠道密钥',
  __unknown_protocol__: '未知协议',
}

export function usageGroupLabel(row: { id: string; name?: string | null }): string {
  // 用 hasOwn 判定哨兵：直接下标会把 toString / constructor 这类原型属性当成标签返回函数。
  const sentinel = Object.hasOwn(usageSentinelLabels, row.id) ? usageSentinelLabels[row.id] : ''
  return sentinel || row.name || row.id
}

export function reportTimezoneOffset(now = new Date()) {
  return -now.getTimezoneOffset()
}

/**
 * Fill empty time buckets so line charts keep a stable time axis. The backend only
 * returns buckets that contain requests; sparse ranges otherwise collapse visually.
 */
export function fillTrendBuckets(
  trend: UsageTrend[] | null | undefined,
  from: number,
  to: number,
  granularity: string,
  timezoneOffsetMinutes = 0,
): UsageTrend[] {
  const rows = Array.isArray(trend) ? trend : []
  if (!Number.isFinite(from) || !Number.isFinite(to)) return rows
  const width = ({ hour: 3_600_000, day: 86_400_000, week: 7 * 86_400_000 } as Record<string, number>)[granularity] || 86_400_000
  const byStart = new Map(rows.map((row) => [Number(row.bucketStart), row]))
  const offset = Number(timezoneOffsetMinutes) * 60_000 || 0
  const first = Math.floor((from + offset) / width) * width - offset
  const last = Math.floor((to + offset) / width) * width - offset
  const chartLast = granularity === 'week'
    ? Math.max(first, last - width)
    : last
  const result: UsageTrend[] = []
  for (let start = first, guard = 0; start <= chartLast && guard < 512; start += width, guard += 1) {
    result.push(byStart.get(start) || {
      bucketStart: start,
      requests: 0,
      errors: 0,
      inputTokens: 0,
      outputTokens: 0,
      cachedTokens: 0,
      cachedWriteTokens: 0,
      reasoningTokens: 0,
      totalTokens: 0,
      costs: {},
    })
  }
  return result
}

export function usageModelOptions(
  report: Partial<Pick<UsageReport, 'byModel'>> | null | undefined,
  channels: Array<Partial<Pick<PriceChannel, 'models'>> | null> | null = [],
): string[] {
  const configured = (channels || []).flatMap((channel) => channel?.models || [])
  const observed = (report?.byModel || []).map((row) => row.id)
  return [...new Set([...configured, ...observed]
    .map((model) => String(model ?? '').trim())
    .filter((model) => model && !model.startsWith('__')))]
    .sort((a, b) => a.localeCompare(b))
}

export function buildTrendPoints<T>(
  rows: T[] | null | undefined,
  value: (row: T) => unknown,
  width = 640,
  height = 150,
  padding = 8,
  maxValue: number | null = null,
): string {
  const values = (rows || []).map((row) => Math.max(0, Number(value(row)) || 0))
  if (!values.length) return ''
  const maximum = Math.max(Number(maxValue) || 0, ...values)
  const max = maximum > 0 ? maximum : 1
  const innerWidth = width - padding * 2
  const innerHeight = height - padding * 2
  return values.map((amount, index) => {
    const x = padding + (values.length === 1 ? innerWidth / 2 : (index / (values.length - 1)) * innerWidth)
    const y = padding + innerHeight - (amount / max) * innerHeight
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

export function usageTrendMax<T>(
  rows: T[] | null | undefined,
  ...accessors: Array<(row: T) => unknown>
): number {
  const values = (rows || []).flatMap((row) => accessors.map((accessor) => Number(accessor(row)) || 0))
  return Math.max(0, ...values)
}

export function csvCell(value: unknown, { text = false }: { text?: boolean } = {}): string {
  let textValue = value == null ? '' : String(value)
  if (text && /^[=+\-@\t\r]/.test(textValue)) textValue = `'${textValue}`
  return /[",\n\r]/.test(textValue) ? `"${textValue.replaceAll('"', '""')}"` : textValue
}

export function usageReportCsv(
  _report: UsageReport,
  dimensionRows: UsageGroup[] | null | undefined,
  _dimensionLabel: string,
): string {
  const header = ['维度', '请求数', '错误数', '成功率', '输入 token', '输出 token', '缓存读 token', '缓存写 token', '推理 token', '总 token', '故障转移', '平均首 token (ms)', '平均总耗时 (ms)', '费用']
  const lines: Array<Array<string | number>> = [header]
  for (const row of dimensionRows || []) {
    const costs = usageCostEntries(row.costs).map((entry) => `${entry.currency} ${entry.value}`).join(' / ')
    lines.push([
      row.name || row.id,
      row.requests,
      row.errors,
      usagePercent(row.requests, row.errors),
      row.inputTokens,
      row.outputTokens,
      row.cachedTokens,
      row.cachedWriteTokens,
      row.reasoningTokens,
      row.totalTokens,
      row.failovers,
      row.avgFirstTokenMs ?? '',
      row.avgTotalMs ?? '',
      costs,
    ])
  }
  return `${lines.map((line) => line.map((value, index) => csvCell(value, { text: index === 0 || index === 13 })).join(',')).join('\n')}\n`
}
