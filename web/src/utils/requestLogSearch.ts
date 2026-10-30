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
]

export interface SearchableLog {
  [field: string]: unknown
}

export function filterRequestLogs<T extends SearchableLog>(logs: T[], query: string | null | undefined): T[] {
  const keyword = String(query ?? '').trim().toLowerCase()
  if (!keyword) return logs
  return logs.filter((log) => SEARCH_FIELDS
    .map((field) => log?.[field])
    .filter((value) => value != null && value !== '')
    .join(' ')
    .toLowerCase()
    .includes(keyword))
}
