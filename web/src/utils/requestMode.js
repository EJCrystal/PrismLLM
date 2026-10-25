export function requestModeLabel(stream) {
  if (stream === true) return '流式'
  if (stream === false) return '同步'
  return '未知'
}

export function requestModeClass(stream) {
  if (stream === true) return 'streaming'
  if (stream === false) return 'sync'
  return 'unknown'
}
