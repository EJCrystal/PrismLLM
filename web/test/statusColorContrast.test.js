import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')

function extractVars(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = css.match(new RegExp(`${escaped}\\s*\\{([\\s\\S]*?)\\n\\}`))
  assert.ok(match, `Missing theme block ${selector}`)
  const vars = {}
  for (const [, name, value] of match[1].matchAll(/--([a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
    vars[name] = value.trim()
  }
  return vars
}

function luminance(hex) {
  const channels = hex.replace('#', '').match(/.{2}/g).map((part) => {
    const c = parseInt(part, 16) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
}

function contrastRatio(a, b) {
  const l1 = luminance(a)
  const l2 = luminance(b)
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
}

const PALETTES = ['sky', 'cyan', 'orange', 'red', 'purple']

describe('全部六套配色在浅深双模式下保持 WCAG AA 状态色与文本对比度', () => {
  const lightBase = extractVars(':root')
  const darkBase = extractVars('[data-theme="dark"]')

  const states = [
    { selector: ':root', vars: lightBase },
    ...PALETTES.map((id) => ({
      selector: `[data-palette="${id}"]`,
      vars: { ...lightBase, ...extractVars(`[data-palette="${id}"]`) },
    })),
    { selector: '[data-theme="dark"]', vars: darkBase },
    ...PALETTES.map((id) => ({
      selector: `[data-theme="dark"][data-palette="${id}"]`,
      vars: { ...darkBase, ...extractVars(`[data-theme="dark"][data-palette="${id}"]`) },
    })),
  ]

  for (const state of states) {
    it(`${state.selector} 状态文本、正文与主按钮满足 WCAG AA >= 4.5:1`, () => {
      const { vars } = state
      for (const surface of [vars.bg, vars.panel, vars['surface-head']]) {
        for (const token of ['ink', 'muted', 'success', 'warning', 'danger']) {
          const ratio = contrastRatio(vars[token], surface)
          assert.ok(ratio >= 4.5, `${state.selector} --${token} (${vars[token]}) on ${surface} = ${ratio.toFixed(2)} < 4.5`)
        }
      }
      const buttonRatio = contrastRatio('#FFFFFF', vars['brand-fill'])
      assert.ok(buttonRatio >= 4.5, `${state.selector} white text on --brand-fill (${vars['brand-fill']}) = ${buttonRatio.toFixed(2)} < 4.5`)
    })
  }

  for (const selector of [':root', '[data-theme="dark"]']) {
    it(`${selector} 保留图表与进度条固定语义色值`, () => {
      const vars = extractVars(selector)
      assert.equal(vars['success-fill'], '#34d399')
      assert.equal(vars['warning-fill'], '#fbbf24')
      assert.equal(vars['danger-fill'], '#f87171')
    })
  }

  for (const tableRule of [
    '.logs-table th.time-col',
    '.logs-table th.actions-col',
    '.channels-table th.channel-name-col',
    '.channels-table th.actions-col',
    '.client-keys-table th.actions-col',
  ]) {
    it(`${tableRule} 使用实色主题表头底色 var(--surface-head) 避免滚动透字`, () => {
      const escaped = tableRule.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const match = css.match(new RegExp(`${escaped}\\s*\\{[^}]*background:\\s*([^;]+);`))
      assert.ok(match, `Missing background on ${tableRule}`)
      assert.equal(match[1].trim(), 'var(--surface-head)')
    })
  }
})
