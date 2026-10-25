export function requestModeLabel(stream) {
  if (stream === true) return '流式'
  if (stream === false) return '非流式'
  return '未知'
}
