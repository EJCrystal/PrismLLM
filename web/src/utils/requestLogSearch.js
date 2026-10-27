const SEARCH_FIELDS = [
  'requestId',
  'traceId',
  'alias',
  'channelId',
  'channelName',
  'channelKeyName',
  'upstreamModel',
  'protocol',
  'statusCode',
  'errorCode',
  'errorMessage',
  'failureStage',
  'upstreamErrorCode',
  'reasoningEffort',
  'clientKeyName',
]

export function filterRequestLogs(logs, query) {
  const keyword = String(query ?? '').trim().toLowerCase()
  if (!keyword) return logs
  return logs.filter((log) => SEARCH_FIELDS
    .map((field) => log?.[field])
    .filter((value) => value != null && value !== '')
    .join(' ')
    .toLowerCase()
    .includes(keyword))
}
