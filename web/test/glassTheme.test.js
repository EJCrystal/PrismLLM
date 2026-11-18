import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
const THEME_BASE_SELECTORS = [':root', '[data-theme="dark"]']
const PALETTE_IDS = ['sky', 'cyan', 'orange', 'red', 'purple']

function extractBlock(source, selector) {
  const match = new RegExp(`(?:^|\\n)[ \\t]*${escapeRegExp(selector)}\\s*\\{`).exec(source)
  assert.ok(match, `缺少主题块 ${selector}`)
  const open = match.index + match[0].length - 1
  let depth = 0
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++
    else if (source[i] === '}') {
      depth--
      if (depth === 0) return source.slice(open + 1, i)
    }
  }
  assert.fail(`主题块 ${selector} 未闭合`)
}

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

describe('Antigravity 轻透毛玻璃主题样式与无障碍回退', () => {
  it('默认浅色与深色主题均定义了完整的轻透毛玻璃、流光网格与极光变量', () => {
    const required = [
      '--glass-bg',
      '--glass-bg-strong',
      '--glass-bg-soft',
      '--glass-field',
      '--glass-field-strong',
      '--glass-border',
      '--glass-line',
      '--glass-shadow',
      '--glass-inset',
      '--popover-surface',
      '--ag-mesh',
      '--aurora-a',
      '--nav-surface',
    ]
    for (const selector of THEME_BASE_SELECTORS) {
      const block = extractBlock(css, selector)
      for (const token of required) {
        assert.match(
          block,
          new RegExp(`${escapeRegExp(token)}\\s*:`),
          `${selector} 缺少变量 ${token}`,
        )
      }
    }
    const rootBlock = extractBlock(css, ':root')
    assert.match(rootBlock, /--glass-blur\s*:/)
    assert.match(rootBlock, /--glass-surface\s*:/)
    assert.match(rootBlock, /--glass-surface-strong\s*:/)
  })

  it('六套 Antigravity 配色在浅色与深色模式下均覆盖极光网格与导航面', () => {
    const paletteSelectors = [
      ...PALETTE_IDS.map((id) => `[data-palette="${id}"]`),
      ...PALETTE_IDS.map((id) => `[data-theme="dark"][data-palette="${id}"]`),
    ]
    for (const selector of paletteSelectors) {
      const block = extractBlock(css, selector)
      for (const token of ['--ag-mesh', '--aurora-a', '--brand', '--brand-fill', '--nav-surface', '--popover-surface']) {
        assert.match(
          block,
          new RegExp(`${escapeRegExp(token)}\\s*:`),
          `${selector} 缺少变量 ${token}`,
        )
      }
    }
  })

  it('核心容器（面板、统计卡、弹窗、登录框、轻提示、侧栏、主题胶囊）使用毛玻璃材质', () => {
    for (const selector of [
      '.panel',
      '.stat-card',
      '.modal',
      '.login-card',
      '.toast',
      '.sidebar',
      '.theme-capsule-btn',
      '.theme-menu',
      '.theme-toggle-btn',
    ]) {
      const block = extractBlock(css, selector)
      assert.match(block, /backdrop-filter:\s*blur\(/, `${selector} 缺少 backdrop-filter 模糊`)
    }
    for (const selector of ['.panel', '.stat-card', '.modal', '.login-card', '.toast']) {
      const block = extractBlock(css, selector)
      assert.match(block, /var\(--glass-surface/, `${selector} 未使用玻璃渐变底色`)
    }
  })

  it('文件末尾为系统 prefers-reduced-transparency: reduce 提供实色降级并覆盖主题控件与侧栏', () => {
    const firstStart = css.indexOf('@media (prefers-reduced-transparency: reduce)')
    assert.notEqual(firstStart, -1, '缺少 prefers-reduced-transparency 回退')
    const tail = css.slice(firstStart)
    assert.equal(css.indexOf('@media (max-width:', firstStart), -1, 'prefers-reduced-transparency 后不应再出现断点规则')
    assert.match(tail, /backdrop-filter:\s*none\s*!important/, '降级未显式禁用 backdrop-filter')
    assert.match(tail, /\.sidebar\s*\{[^}]*background:\s*var\(--nav\)/, '侧栏未在透明降级中回退到实色')
    assert.match(tail, /\.theme-menu/, '主题菜单未在透明降级中回退到实色')
  })

  it('主视图侧栏弹窗与抽屉样式也在系统降低透明度时回退为实色', () => {
    const start = css.indexOf('@media (prefers-reduced-transparency: reduce)')
    const tail = css.slice(start)
    assert.match(tail, /\.panel/)
    assert.match(tail, /\.modal/)
    for (const file of ['ModelRoutesView.vue', 'ChannelsView.vue']) {
      const source = readFileSync(new URL(`../src/components/${file}`, import.meta.url), 'utf8')
      const pos = source.indexOf('@media (prefers-reduced-transparency: reduce)')
      assert.notEqual(pos, -1, `${file} 缺少 prefers-reduced-transparency 回退`)
      assert.match(source.slice(pos), /background:\s*var\(--panel\)/, `${file} 降级未使用实色 --panel`)
    }
  })
})
