import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')

const THEME_SELECTORS = [':root', '[data-theme="dark"]', '[data-theme="sepia"]']

function themeBlock(selector) {
  const start = css.indexOf(selector + ' {')
  assert.notEqual(start, -1, 'missing theme block: ' + selector)
  return css.slice(start, css.indexOf('}', start))
}

function ruleBody(selector) {
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
  const matches = rules.filter(([, selectors]) =>
    selectors.split(',').some((item) => item.trim() === selector))
  assert.ok(matches.length, 'missing rule for ' + selector)
  return matches.map(([, , body]) => body).join('\n')
}

test('每个主题都定义完整的液态玻璃令牌', () => {
  for (const selector of THEME_SELECTORS) {
    const block = themeBlock(selector)
    for (const token of [
      '--glass-bg',
      '--glass-bg-strong',
      '--glass-bg-soft',
      '--glass-field',
      '--glass-field-strong',
      '--glass-border',
      '--glass-line',
      '--glass-shadow',
      '--glass-inset',
      '--aurora-a',
    ]) {
      assert.match(block, new RegExp(token + ':'), 'missing ' + token + ' in ' + selector)
    }
  }
  // 模糊半径与复合表面由 :root 定义，主题只覆盖底层配色令牌。
  const root = themeBlock(':root')
  assert.match(root, /--glass-blur:/)
  assert.match(root, /--glass-surface:/)
  assert.match(root, /--glass-surface-strong:/)
})

test('主要容器使用玻璃表面并开启背景模糊', () => {
  for (const selector of ['.panel', '.stat-card', '.modal', '.login-card', '.toast']) {
    const body = ruleBody(selector)
    assert.match(body, /background:\s*var\(--glass-surface/, selector + ' 必须使用玻璃表面')
    assert.match(body, /backdrop-filter:\s*blur\(/, selector + ' 必须启用 backdrop-filter')
  }
})

test('内部控件使用半透明字段令牌而不是实色背景', () => {
  for (const selector of ['.search-box', '.switch-row', '.key-prefix', '.secret-value', '.channel-canonical-row']) {
    const body = ruleBody(selector)
    assert.match(body, /background(?:-color)?:\s*var\(--glass-(?:field|surface)/, selector + ' 必须使用玻璃字段令牌')
    assert.match(body, /border(?:-color)?:\s*1px solid var\(--glass-line\)/, selector + ' 必须使用玻璃描边')
  }
})

test('系统要求降低透明度时回退到实色并移除模糊', () => {
  const start = css.indexOf('@media (prefers-reduced-transparency: reduce)')
  assert.notEqual(start, -1, '缺少 prefers-reduced-transparency 回退')
  const block = css.slice(start, css.indexOf('\n}\n', start))
  for (const selector of ['.panel', '.modal', '.search-box', '.sidebar']) {
    assert.ok(block.includes(selector), '回退块必须覆盖 ' + selector)
  }
  assert.match(block, /backdrop-filter:\s*none/)
  assert.match(block, /background:\s*var\(--panel\)/)
})
