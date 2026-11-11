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
  const block = css.slice(start)
  for (const selector of ['.panel', '.modal', '.search-box']) {
    assert.ok(block.includes(selector), '回退块必须覆盖 ' + selector)
  }
  assert.match(block, /backdrop-filter:\s*none/)
  assert.match(block, /background:\s*var\(--panel\)/)
})

test('降低透明度回退能真正赢得层叠', () => {
  const start = css.indexOf('@media (prefers-reduced-transparency: reduce)')
  const block = css.slice(start)
  // 主题限定选择器 (0,2,0) 与随后的同级规则都会覆盖单类选择器，必须提升优先级。
  assert.match(block, /!important/, '回退规则必须带 !important 才能压过主题选择器')
  // 回退块必须位于文件末尾，否则会被后面的 .table-view-popover 等规则覆盖。
  const rest = css.slice(start + block.length)
  assert.doesNotMatch(rest, /^\s*[^@\s]/m, '降低透明度回退之后不应再有普通样式规则')
})

test('输入框与次要按钮在降低透明度时连背景和模糊一起回退', () => {
  const start = css.indexOf('@media (prefers-reduced-transparency: reduce)')
  const block = css.slice(start)
  // 只恢复边框、不撤掉半透明底与模糊，等于沿用玻璃外观。
  for (const selector of ['.field input', '.field textarea', '.select-input', '.button.ghost', '.button.mini']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const rule = new RegExp('([^{}]*' + escaped + '[^{}]*)\\{([^{}]*)\\}', 'g')
    const bodies = [...block.matchAll(rule)].map((m) => m[2])
    const withBg = bodies.some((b) => /background(-color)?:\s*var\(--panel\)\s*!important/.test(b))
    assert.ok(withBg, selector + ' 必须在回退块里设置实色背景')
  }
  assert.match(block, /backdrop-filter:\s*none\s*!important/)
})

test('侧栏回退使用深色导航底色而不是面板色', () => {
  const start = css.indexOf('@media (prefers-reduced-transparency: reduce)')
  const block = css.slice(start)
  const sidebar = block.slice(block.indexOf('.sidebar {'))
  const body = sidebar.slice(0, sidebar.indexOf('}'))
  assert.match(body, /background:\s*var\(--nav\)/, '侧栏必须回退到 --nav，浅色主题下白底白字不可读')
  assert.doesNotMatch(body, /var\(--panel\)/)
})

test('弹层与组件级玻璃表面自带回退', () => {
  const files = {
    'SearchSelect.vue': ['.search-select-panel'],
    'FingerprintTestView.vue': ['.fingerprint-hero'],
    'ModelConnectivityView.vue': ['.probe-hero'],
    'KeyDisableRuleFields.vue': ['.key-auto-disable-rule textarea'],
    'ChannelKeyEditor.vue': ['.channel-key-editor'],
    'ChannelKeyRateLimitModal.vue': ['.channel-key-rate-limit-preview'],
    'ChannelsView.vue': ['.channel-key-detail', '.protocol-row'],
  }
  for (const [file, selectors] of Object.entries(files)) {
    const source = readFileSync(new URL('../src/components/' + file, import.meta.url), 'utf8')
    const start = source.indexOf('@media (prefers-reduced-transparency: reduce)')
    assert.notEqual(start, -1, file + ' 缺少降低透明度回退')
    const block = source.slice(start)
    for (const selector of selectors) {
      assert.ok(block.includes(selector), file + ' 的回退块必须覆盖 ' + selector)
    }
  }
})
