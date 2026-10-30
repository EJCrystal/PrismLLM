import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  THEME_STORAGE_KEY,
  THEME_PRESETS,
  THEMES,
  DEFAULT_THEME,
  normalizeTheme,
  getAvailableThemes,
  getSavedTheme,
  nextTheme,
  applyTheme,
} from '../src/utils/theme'

function createMockStorage(initial = {}) {
  const store = new Map(Object.entries(initial))
  return {
    getItem(key) {
      return store.has(key) ? store.get(key) : null
    },
    setItem(key, value) {
      store.set(key, String(value))
    },
    removeItem(key) {
      store.delete(key)
    },
    clear() {
      store.clear()
    },
  }
}

function createMockDocEl() {
  const attrs = new Map()
  return {
    setAttribute(name, val) {
      attrs.set(name, String(val))
    },
    getAttribute(name) {
      return attrs.get(name) || null
    },
  }
}

describe('theme: 多主题注册表、护眼主题与可扩展性测试', () => {
  it('默认主题必须为浅色 (light)，且注册表中包含 light, dark 与 sepia (护眼)', () => {
    assert.equal(DEFAULT_THEME, 'light')
    assert.equal(THEMES.LIGHT, 'light')
    assert.equal(THEMES.DARK, 'dark')
    assert.equal(THEMES.SEPIA, 'sepia')

    const presets = getAvailableThemes()
    assert.equal(Array.isArray(presets), true)
    assert.equal(presets.some((p) => p.id === 'light'), true)
    assert.equal(presets.some((p) => p.id === 'dark'), true)
    assert.equal(presets.some((p) => p.id === 'sepia'), true)
  })

  describe('主题校验与规范化 (normalizeTheme)', () => {
    it('合法注册的主题（包含 sepia）直接通过，未注册/非法值安全回退到默认浅色 (light)', () => {
      assert.equal(normalizeTheme('light'), 'light')
      assert.equal(normalizeTheme('dark'), 'dark')
      assert.equal(normalizeTheme('sepia'), 'sepia')
      assert.equal(normalizeTheme('random-invalid'), 'light')
      assert.equal(normalizeTheme(''), 'light')
      assert.equal(normalizeTheme(null), 'light')
    })
  })

  describe('获取初始偏好 (getSavedTheme)', () => {
    it('首次访问时（存储为空），默认严格返回浅色 (light)', () => {
      const storage = createMockStorage()
      const theme = getSavedTheme(storage)
      assert.equal(theme, 'light')
    })

    it('存储异常/存在非法值时，严格回退到默认浅色 (light)', () => {
      const storage = createMockStorage({ [THEME_STORAGE_KEY]: 'unknown-theme' })
      assert.equal(getSavedTheme(storage), 'light')

      const invalidStorage = {
        getItem() {
          throw new Error('SecurityError')
        },
      }
      assert.equal(getSavedTheme(invalidStorage), 'light')
    })

    it('存储记录为 dark 时，正确返回深色 (dark)', () => {
      const storage = createMockStorage({ [THEME_STORAGE_KEY]: 'dark' })
      assert.equal(getSavedTheme(storage), 'dark')
    })

    it('存储记录为 sepia 时，正确返回护眼主题 (sepia)', () => {
      const storage = createMockStorage({ [THEME_STORAGE_KEY]: 'sepia' })
      assert.equal(getSavedTheme(storage), 'sepia')
    })

    it('存储记录为 light 时，正确返回浅色 (light)', () => {
      const storage = createMockStorage({ [THEME_STORAGE_KEY]: 'light' })
      assert.equal(getSavedTheme(storage), 'light')
    })
  })

  describe('三主题顺次轮转 (nextTheme: light -> dark -> sepia -> light)', () => {
    it('三主题闭环顺次切换', () => {
      assert.equal(nextTheme('light'), 'dark', '浅色下一个是深色')
      assert.equal(nextTheme('dark'), 'sepia', '深色下一个是护眼暖色')
      assert.equal(nextTheme('sepia'), 'light', '护眼暖色下一个是浅色')
    })

    it('当前主题不在注册表时，安全从第一个注册主题 (light) 开始', () => {
      assert.equal(nextTheme('non-existent'), 'light')
    })
  })

  describe('护眼主题应用与刷新保留 (applyTheme & persistence)', () => {
    it('切换为护眼主题 (sepia) 后：DOM 属性更新为 sepia，存储写入 sepia；模拟刷新后仍保留 sepia', () => {
      const storage = createMockStorage()
      const docEl = createMockDocEl()

      // 1. 用户点击切换为 sepia
      const applied = applyTheme('sepia', docEl, storage)
      assert.equal(applied, 'sepia')
      assert.equal(docEl.getAttribute('data-theme'), 'sepia', 'DOM html 根标签属性必须被设为 sepia')
      assert.equal(storage.getItem(THEME_STORAGE_KEY), 'sepia', 'localStorage 必须写入 sepia')

      // 2. 模拟页面刷新（重新初始化读取）
      const refreshedDocEl = createMockDocEl()
      const savedOnReload = getSavedTheme(storage)
      assert.equal(savedOnReload, 'sepia', '刷新后从 localStorage 读取的主题必须依然是 sepia')

      // 3. 页面重新挂载应用
      applyTheme(savedOnReload, refreshedDocEl, storage)
      assert.equal(refreshedDocEl.getAttribute('data-theme'), 'sepia')
    })

    it('从护眼主题切换回浅色后：DOM 属性更新为 light，存储写入 light；模拟刷新后保留 light', () => {
      const storage = createMockStorage({ [THEME_STORAGE_KEY]: 'sepia' })
      const docEl = createMockDocEl()
      docEl.setAttribute('data-theme', 'sepia')

      const current = getSavedTheme(storage)
      const next = nextTheme(current)
      assert.equal(next, 'light')

      const applied = applyTheme(next, docEl, storage)
      assert.equal(applied, 'light')
      assert.equal(docEl.getAttribute('data-theme'), 'light')
      assert.equal(storage.getItem(THEME_STORAGE_KEY), 'light')

      const reloadedTheme = getSavedTheme(storage)
      assert.equal(reloadedTheme, 'light', '刷新后保持浅色')
    })
  })
})
