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
  if (typeof DOMParser === 'undefined') {
    // 非浏览器环境没有 HTML 解析器；宁可返回空预览，也不把未清洗内容交给 iframe。
    return `<!DOCTYPE html><html><head>${meta}</head><body></body></html>`
  }
  const document = new DOMParser().parseFromString(html, 'text/html')
  stripPreviewNavigation(document)
  const csp = document.createElement('meta')
  csp.setAttribute('http-equiv', 'Content-Security-Policy')
  csp.setAttribute('content', PREVIEW_CSP)
  document.head.prepend(csp)
  return `<!DOCTYPE html>${document.documentElement.outerHTML}`
}

/**
 * 去掉链接、表单和自动刷新等导航入口。
 *
 * 使用浏览器的 HTML 解析器，未加引号的属性、注释和错误嵌套都按真实 DOM 处理，
 * 避免正则与浏览器解析边界不一致。锚点整体移除，防止 SVG SMIL 再通过
 * `<set attributeName="href">` 动态补回外链；脚本、meta、base 也一并移除。
 */
function stripPreviewNavigation(document: Document): void {
  document.querySelectorAll('script, meta, base, link, area, iframe, object, embed')
    .forEach((element) => element.remove())
  document.querySelectorAll('a').forEach((element) => {
    element.replaceWith(...Array.from(element.childNodes))
  })
  document.querySelectorAll('form, button, input').forEach((element) => {
    for (const attribute of ['action', 'method', 'target', 'formaction', 'ping']) {
      element.removeAttribute(attribute)
    }
  })
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
