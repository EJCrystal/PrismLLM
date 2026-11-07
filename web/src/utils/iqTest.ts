import type { IqVerdict } from '../types/api'

const PREVIEW_CSP = [
  "default-src 'none'",
  "img-src data: blob:",
  "style-src 'unsafe-inline'",
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
 * 注入只允许内联资源的 CSP，避免模型生成的 HTML 访问外网或执行脚本。
 * 预览 iframe 仍然使用不带 `allow-scripts` 的 sandbox，CSP 只是第二道防线。
 */
export function withIqPreviewCsp(html: string): string {
  const meta = `<meta http-equiv="Content-Security-Policy" content="${PREVIEW_CSP}">`
  const sanitized = stripPreviewNavigation(
    html
      .replace(/<meta[^>]+http-equiv=["']?refresh["']?[^>]*>/gi, '')
      .replace(/<base[^>]*>/gi, ''),
  )
  // 始终把可信 CSP 放进文档最前面的 head，再拼接模型内容。
  // 这样即使内容里的注释、title/style 文本伪造了 <head>，CSP 也不会被注入到那里。
  return `<!DOCTYPE html><html><head>${meta}</head><body>${sanitized}</body></html>`
}

/**
 * 去掉链接和表单的导航目标；脚本已被 sandbox 与 CSP 禁止，meta refresh、base 也已移除，
 * 这样预览既不能自动跳转，也不会因用户点击把页面内容带到外部地址。
 */
function stripPreviewNavigation(html: string): string {
  return html
    .replace(/<(a|area)\b[^>]*>/gi, (tag) =>
      tag.replace(/\s(?:href|xlink:href|target)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, ''),
    )
    .replace(/<form\b[^>]*>/gi, (tag) =>
      tag.replace(/\s(?:action|method|target)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, ''),
    )
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
