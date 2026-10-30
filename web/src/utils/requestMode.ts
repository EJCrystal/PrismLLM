export function requestModeLabel(stream: boolean | null | undefined): string {
  if (stream === true) return '流式'
  return '同步'
}

export function requestModeClass(stream: boolean | null | undefined): string {
  if (stream === true) return 'streaming'
  return 'sync'
}
