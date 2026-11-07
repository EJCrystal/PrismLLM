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
const START_TAG_PATTERN = /<([a-zA-Z][\w:-]*)((?:"[^"]*"|'[^']*'|[^'">])*)>/g
const FORM_ATTRIBUTE_PATTERN =
  /\s(?:action|method|target|formaction)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi

export function withIqPreviewCsp(html: string): string {
  const meta = `<meta http-equiv="Content-Security-Policy" content="${PREVIEW_CSP}">`
  const sanitized = stripPreviewNavigation(html)
  // 始终把可信 CSP 放进文档最前面的 head，再拼接模型内容。
  // 这样即使内容里的注释、title/style 文本伪造了 <head>，CSP 也不会被注入到那里。
  return `<!DOCTYPE html><html><head>${meta}</head><body>${sanitized}</body></html>`
}

/**
 * 去掉链接、表单和自动刷新等导航入口。
 *
 * 标签和属性都按引号边界解析，属性值里的 `>` 不会让后面的 href 逃逸清洗。
 * 锚点整体移除，避免 SVG SMIL 再通过 `<set attributeName="href">` 动态补回外链。
 * 脚本已被 sandbox 与 CSP 禁止，这样预览既不能自动跳转，
 * 也不会因用户点击把页面内容带到外部地址。
 */
function stripPreviewNavigation(html: string): string {
  return html
    .replace(START_TAG_PATTERN, (tag, rawName: string, attrs: string) => {
      const name = rawName.toLowerCase()
      // 模型内容不需要 meta；整体移除比识别 refresh 更可靠，也避免实体编码绕过。
      if (name === 'meta' || name === 'base' || name === 'a' || name === 'area') return ''
      if (name === 'form' || name === 'button' || name === 'input') {
        return `<${rawName}${attrs.replace(FORM_ATTRIBUTE_PATTERN, '')}>`
      }
      return tag
    })
    .replace(/<\/(?:a|area)\s*>/gi, '')
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
