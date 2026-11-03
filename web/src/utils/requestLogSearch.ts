const SEARCH_FIELDS = [
  'requestId',
  'traceId',
  'alias',
  'channelId',
  'channelName',
  'channelKeyName',
  'upstreamModel',
  'protocol',
  'upstreamProtocol',
  'statusCode',
  'errorCode',
  'errorMessage',
  'failureStage',
  'upstreamErrorCode',
  'reasoningEffort',
  'clientKeyName',
] as const

export type SearchableLog = Partial<Record<typeof SEARCH_FIELDS[number], unknown>>

export interface RequestLogFilters {
  channelId?: string
  model?: string
  status?: string
}

export const UNROUTED_CHANNEL = '__unrouted__'

/** Options always come from all loaded rows, so one active filter does not hide other choices. */
export function requestLogFilterOptions(logs: SearchableLog[]) {
  const channels = new Map<string, string>()
  const models = new Set<string>()
  const statuses = new Set<number>()
  let hasUnrouted = false
  for (const log of logs) {
    const channelId = String(log.channelId ?? '')
    if (!channelId) hasUnrouted = true
    else if (!channels.has(channelId)) channels.set(channelId, String(log.channelName || channelId))
    const model = String(log.alias ?? '').trim()
    if (model) models.add(model)
    const status = Number(log.statusCode)
    if (Number.isInteger(status) && status >= 100 && status < 600) statuses.add(status)
  }
  const nameCounts = new Map<string, number>()
  for (const name of channels.values()) nameCounts.set(name, (nameCounts.get(name) || 0) + 1)
  const channelOptions = [...channels].map(([value, label]) => ({
    value,
    label: (nameCounts.get(label) || 0) > 1 ? `${label} · ${value}` : label,
  })).sort((a, b) => a.label.localeCompare(b.label))
  if (hasUnrouted) channelOptions.push({ value: UNROUTED_CHANNEL, label: '未路由到渠道' })
  return {
    channels: channelOptions,
    models: [...models].sort((a, b) => a.localeCompare(b)).map(value => ({ value, label: value })),
    statuses: [...statuses].sort((a, b) => a - b).map(value => ({ value: String(value), label: `HTTP ${value}` })),
  }
}

export function filterRequestLogs<T extends SearchableLog>(logs: T[], query: string | null | undefined, filters: RequestLogFilters = {}): T[] {
  const keyword = String(query ?? '').trim().toLowerCase()
  if (!keyword && !filters.channelId && !filters.model && !filters.status) return logs
  return logs.filter((log) => {
    if (filters.channelId === UNROUTED_CHANNEL) {
      if (log.channelId) return false
    } else if (filters.channelId && log.channelId !== filters.channelId) return false
    if (filters.model && String(log.alias ?? '').trim() !== filters.model) return false
    const status = Number(log.statusCode)
    if (filters.status === 'success') {
      if (status < 200 || status >= 300 || !Number.isFinite(status)) return false
    } else if (filters.status === 'error') {
      if (status < 400 || status >= 600 || !Number.isFinite(status)) return false
    } else if (filters.status && status !== Number(filters.status)) return false
    return !keyword || SEARCH_FIELDS
      .map((field) => log?.[field])
      .filter((value) => value != null && value !== '')
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
}
