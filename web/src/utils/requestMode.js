export function requestModeLabel(stream) {
  if (stream === true) return '流式'
  return '同步'
}

export function requestModeClass(stream) {
  if (stream === true) return 'streaming'
  return 'sync'
}
