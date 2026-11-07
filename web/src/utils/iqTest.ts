import type { IqVerdict } from '../types/api'

const PREVIEW_CSP = [
  "default-src 'none'",
  "img-src data: blob:",
  "style-src 'unsafe-inline'",
  "script-src 'unsafe-inline'",
  "font-src data:",
  "media-src data: blob:",
  "connect-src 'none'",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join('; ')

/** 用不会冲突的键把渠道与模型组合成一个下拉选项。 */
export function iqTargetKey(channelId: string, model: string): string {
  return `${channelId}\u0000${model}`
}

export function iqVerdictTone(verdict: IqVerdict): 'success' | 'warning' | 'danger' {
  if (verdict === 'passed') return 'success'
  if (verdict === 'failed') return 'danger'
  return 'warning'
}

/**
 * 注入只允许内联资源与内联脚本的 CSP，避免模型生成的 HTML 访问外网。
 * 预览 iframe 仍然使用 sandbox，CSP 只是第二道防线。
 */
export function withIqPreviewCsp(html: string): string {
  const meta = `<meta http-equiv="Content-Security-Policy" content="${PREVIEW_CSP}">`
  const sanitized = html
    .replace(/<meta[^>]+http-equiv=["']?refresh["']?[^>]*>/gi, '')
    .replace(/<base[^>]*>/gi, '')
  if (/<head[^>]*>/i.test(sanitized)) {
    return sanitized.replace(/<head[^>]*>/i, (head) => `${head}${meta}`)
  }
  if (/<html[^>]*>/i.test(sanitized)) {
    return sanitized.replace(/<html[^>]*>/i, (tag) => `${tag}<head>${meta}</head>`)
  }
  return `<!DOCTYPE html><html><head>${meta}</head><body>${sanitized}</body></html>`
}

export function formatIqLatency(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(1)} s`
}

export function formatIqTime(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '—'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
