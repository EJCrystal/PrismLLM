import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
const accessModeToggle = readFileSync(new URL('../src/components/AccessModeToggle.vue', import.meta.url), 'utf8')

/**
 * 弹窗面板必须对内部滚动免疫：视觉隐藏的原生控件是绝对定位，如果所在行不是
 * 包含块，它们的溢出会被算进 `.modal` 的滚动高度；真实点击让控件获得焦点时，
 * 浏览器把焦点滚入视野会连带滚动面板（overflow: hidden 仍可程序化滚动），
 * header/body 被顶出可视区、footer 浮到面板顶部。
 */

function ruleBody(source, selector) {
  const rules = [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
  const matches = rules.filter(([, selectors]) =>
    selectors.split(',').some((item) => item.trim() === selector))
  assert.ok(matches.length, 'missing rule for ' + selector)
  return matches.map(([, , body]) => body).join('\n')
}

test('弹窗面板使用不可滚动的 overflow: clip', () => {
  const body = ruleBody(css, '.modal')
  // 旧浏览器退回 hidden，新浏览器用 clip 彻底禁止程序化滚动。
  assert.match(body, /overflow:\s*hidden/, '.modal 需要 overflow: hidden 作为回退')
  assert.match(body, /overflow:\s*clip/, '.modal 必须使用 overflow: clip 禁止面板自身滚动')
})

test('白/黑名单分段是隐藏 radio 的包含块', () => {
  const body = ruleBody(accessModeToggle, '.mode-segment')
  assert.match(body, /position:\s*relative/, '.mode-segment 必须是 position: relative')
})

test('启用开关行是隐藏 checkbox 的包含块', () => {
  const body = ruleBody(css, '.switch-row')
  assert.match(body, /position:\s*relative/, '.switch-row 必须是 position: relative')
})
