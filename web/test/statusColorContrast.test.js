import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')

function themeTokens(selector) {
  const start = css.indexOf(selector + ' {')
  assert.notEqual(start, -1, 'missing theme block: ' + selector)
  const end = css.indexOf('}', start)
  const block = css.slice(start, end)
  const get = (name) => {
    const value = block.match(new RegExp('--' + name + ':\\s*(#[0-9a-f]{6})', 'i'))?.[1]
    assert.ok(value, 'missing color token --' + name + ' in ' + selector)
    return value
  }
  return { get }
}

function luminance(hex) {
  const channels = hex.slice(1).match(/../g).map((value) => parseInt(value, 16) / 255)
  const linear = channels.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrastRatio(first, second) {
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

test('status text colors meet WCAG AA against theme surfaces and keep separate fills', () => {
  for (const selector of [':root', '[data-theme=\"dark\"]', '[data-theme=\"sepia\"]']) {
    const tokens = themeTokens(selector)
    for (const state of ['success', 'warning', 'danger']) {
      const textColor = tokens.get(state)
      const fillColor = tokens.get(state + '-fill')
      assert.equal(fillColor, state === 'success' ? '#34d399' : state === 'warning' ? '#fbbf24' : '#f87171')
      for (const surface of ['bg', 'panel']) {
        assert.ok(contrastRatio(textColor, tokens.get(surface)) >= 4.5, state + ' text on ' + surface + ' must meet 4.5:1')
      }
    }
  }
})

test('sticky table headers use the same theme surface as regular headers', () => {
  for (const selector of [':root', '[data-theme="dark"]', '[data-theme="sepia"]']) {
    const tokens = themeTokens(selector)
    assert.ok(tokens.get('table-head-bg'))
  }
  const rules = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
  for (const selector of [
    '.data-table th',
    '.logs-table th.time-col',
    '.logs-table th.actions-col',
    '.channels-table th.channel-name-col',
    '.channels-table th.actions-col',
    '.client-keys-table th.actions-col',
  ]) {
    const backgrounds = rules.filter(([, selectors, body]) =>
      selectors.split(',').some((item) => item.trim() === selector) && /background:/.test(body))
    assert.ok(backgrounds.length, 'missing header background rule ' + selector)
    for (const [, , body] of backgrounds) {
      assert.match(body, /background:\s*var\(--table-head-bg\)/, selector + ' must share the themed header surface')
    }
  }
})
