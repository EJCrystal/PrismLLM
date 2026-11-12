import type { IqBenchmarkDefinition, IqVerdict } from '../types/api'
import { formatDuration } from './format'

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

/**
 * 后台完成的检测能否直接替换详情区。
 *
 * `pinned` 表示用户手动点开了某条历史记录；`editing` 表示人工判定表单里有尚未保存的
 * 修改。两者任一成立时都不能替换，否则会抢走用户正在查看的内容或清空未保存的输入。
 */
export function shouldAdoptIqRunResult(guard: { pinned: boolean; editing: boolean }): boolean {
  return !guard.pinned && !guard.editing
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
  // DOMParser 返回惰性文档：解析阶段不执行脚本，也不加载图片/iframe 等资源，
  // 只有清洗后的结果才会写进 sandbox iframe。
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
 * `template` 整体移除：`querySelectorAll` 不会遍历 `template.content`，声明式
 * Shadow DOM 里的链接会绕过下面的清洗。
 */
function stripPreviewNavigation(document: Document): void {
  document.querySelectorAll('script, meta, base, link, area, iframe, object, embed, template')
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

/** 智商检测的耗时展示复用全局时长规则，避免同一套单位在多个工具里分叉。 */
export function formatIqLatency(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  return formatDuration(ms)
}

/**
 * 题目超时提示，例如「图形题通常需要 30–120 秒，超时 180 秒」。
 *
 * 超时数值来自服务端下发的题目定义，不在前端写死：IQ_TEST_TIMEOUT_MS 或题目默认值调整后，
 * 界面提示会自动跟随，避免提示与真实拦截时刻不一致。
 */
export function iqTimeoutHint(
  definition: Pick<IqBenchmarkDefinition, 'durationHint' | 'effectiveTimeoutMs'> | null | undefined,
): string {
  if (!definition) return ''
  if (!Number.isFinite(definition.effectiveTimeoutMs)) return `${definition.durationHint}。`
  const seconds = Math.max(1, Math.round(definition.effectiveTimeoutMs / 1000))
  return `${definition.durationHint}，超时 ${seconds} 秒。`
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
