import type {
  SystemConfigItem,
  SystemConfigSource,
  UpstreamProtocolOverrides,
  UpstreamRequestOverrides,
} from '../types/api'
import { formatDuration, formatNumber } from './format'

export function formatBytes(value: number | string): string {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${Math.round(bytes)} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let amount = bytes / 1024
  let index = 0
  while (amount >= 1024 && index < units.length - 1) {
    amount /= 1024
    index += 1
  }
  return `${amount >= 10 ? Math.round(amount) : amount.toFixed(1)} ${units[index]}`
}

export function formatConfigValue(item: Pick<SystemConfigItem, 'value' | 'kind'>): string {
  const raw = item.value?.trim() ?? ''
  if (item.kind === 'boolean') {
    if (raw === 'true') return '开启'
    if (raw === 'false') return '关闭'
    return raw || '—'
  }
  if (!raw) return '未配置'
  if (item.kind === 'duration') {
    const value = Number(raw)
    return Number.isFinite(value) ? formatDuration(value) : raw
  }
  if (item.kind === 'bytes') return formatBytes(raw)
  if (item.kind === 'number' || item.kind === 'count') return formatNumber(raw)
  if (item.kind === 'list') {
    return raw.split(',').map((part) => part.trim()).filter(Boolean).join('、')
  }
  return raw
}

export function configSourceLabel(source: SystemConfigSource): string {
  if (source === 'environment') return '环境变量'
  if (source === 'derived') return '运行时'
  return '默认值'
}

export function configSourceTone(source: SystemConfigSource): 'info' | 'neutral' {
  return source === 'environment' ? 'info' : 'neutral'
}

function countProtocolOverrides(overrides: UpstreamProtocolOverrides): number {
  return (
    (overrides.headerProfile ? 1 : 0) +
    (overrides.userAgent ? 1 : 0) +
    Object.keys(overrides.headers ?? {}).length +
    Object.keys(overrides.body ?? {}).length
  )
}

export function countUpstreamOverrides(overrides: UpstreamRequestOverrides): number {
  const protocols = Object.values(overrides.protocols ?? {}).filter(
    (value): value is UpstreamProtocolOverrides => Boolean(value),
  )
  return (
    (overrides.headerProfile ? 1 : 0) +
    (overrides.userAgent ? 1 : 0) +
    Object.keys(overrides.headers ?? {}).length +
    Object.keys(overrides.body ?? {}).length +
    (overrides.reasoningContentPolicy ? 1 : 0) +
    (overrides.developerRolePolicy ? 1 : 0) +
    protocols.reduce((total, protocol) => total + countProtocolOverrides(protocol), 0)
  )
}
