import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  THEME_STORAGE_KEY,
  LEGACY_THEME_STORAGE_KEY,
  PALETTE_STORAGE_KEY,
  THEMES,
  PALETTES,
  DEFAULT_THEME,
  DEFAULT_PALETTE,
  THEME_PRESETS,
  THEME_PALETTES,
  getAvailableThemes,
  getAvailablePalettes,
  normalizeTheme,
  normalizePalette,
  getPaletteDisplayName,
  getSavedTheme,
  getSavedPalette,
  nextTheme,
  applyTheme,
  applyPalette,
} from '../src/utils/theme.ts'

function createMockStorage(initial = {}) {
  const store = { ...initial }
  return {
    getItem(key) {
      return Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null
    },
    setItem(key, value) {
      store[key] = String(value)
    },
    dump() {
      return { ...store }
    },
  }
}

function createMockDocElement() {
  const attrs = {}
  return {
    setAttribute(name, value) {
      attrs[name] = String(value)
    },
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null
    },
  }
}

describe('PrismLLM 主题系统（Antigravity 六配色 × 浅深双模式）', () => {
  it('默认模式为浅色 (light)，默认配色为四色极光 (gemini)', () => {
    assert.equal(DEFAULT_THEME, 'light')
    assert.equal(DEFAULT_PALETTE, 'gemini')
    assert.equal(THEMES.LIGHT, 'light')
    assert.equal(THEMES.DARK, 'dark')
    assert.deepEqual(THEME_PRESETS.map((p) => p.id), ['light', 'dark'])
    assert.deepEqual(
      THEME_PALETTES.map((p) => p.id),
      ['gemini', 'sky', 'cyan', 'orange', 'red', 'purple'],
    )
    assert.equal(getAvailableThemes().length, 2)
    assert.equal(getAvailablePalettes().length, 6)
  })

  it('各配色在浅色与深色模式下展示对应命名', () => {
    assert.equal(getPaletteDisplayName('gemini', 'light'), '四色极光')
    assert.equal(getPaletteDisplayName('gemini', 'dark'), '暗夜极光')
    assert.equal(getPaletteDisplayName('sky', 'light'), '蔚蓝晴空')
    assert.equal(getPaletteDisplayName('sky', 'dark'), '深海晴空')
    assert.equal(getPaletteDisplayName('purple', 'light'), '紫罗幽兰')
    assert.equal(getPaletteDisplayName('purple', 'dark'), '幻夜幽紫')
  })

  describe('normalizeTheme / normalizePalette 容错校验', () => {
    it('合法模式与配色直接通过，非法值安全回退', () => {
      assert.equal(normalizeTheme('light'), 'light')
      assert.equal(normalizeTheme('dark'), 'dark')
      assert.equal(normalizeTheme('sepia'), 'light')
      assert.equal(normalizeTheme('invalid'), 'light')
      assert.equal(normalizeTheme(null), 'light')

      assert.equal(normalizePalette('sky'), 'sky')
      assert.equal(normalizePalette('purple'), 'purple')
      assert.equal(normalizePalette('unknown'), 'gemini')
      assert.equal(normalizePalette(undefined), 'gemini')
    })
  })

  describe('getSavedTheme / getSavedPalette 本地持久化与兼容迁移', () => {
    it('空存储时返回默认浅色与四色极光，旧版 key 与 sepia 平滑兼容', () => {
      assert.equal(getSavedTheme(createMockStorage()), 'light')
      assert.equal(getSavedPalette(createMockStorage()), 'gemini')

      const legacyDark = createMockStorage({ [LEGACY_THEME_STORAGE_KEY]: 'dark' })
      assert.equal(getSavedTheme(legacyDark), 'dark')

      const legacySepia = createMockStorage({ [THEME_STORAGE_KEY]: 'sepia' })
      assert.equal(getSavedTheme(legacySepia), 'light')
      assert.equal(getSavedPalette(legacySepia), 'orange')
    })

    it('存储已写入模式与配色时正确恢复', () => {
      const storage = createMockStorage({
        [THEME_STORAGE_KEY]: 'dark',
        [PALETTE_STORAGE_KEY]: 'cyan',
      })
      assert.equal(getSavedTheme(storage), 'dark')
      assert.equal(getSavedPalette(storage), 'cyan')
    })
  })

  describe('浅深模式切换与端到端持久化闭环', () => {
    it('nextTheme 在浅色与深色之间双向切换', () => {
      assert.equal(nextTheme('light'), 'dark')
      assert.equal(nextTheme('dark'), 'light')
      assert.equal(nextTheme('other'), 'light')
    })

    it('applyTheme / applyPalette 更新 DOM 属性与 localStorage，刷新后完整还原', () => {
      const docEl = createMockDocElement()
      const storage = createMockStorage()

      assert.equal(applyTheme('dark', docEl, storage), 'dark')
      assert.equal(applyPalette(PALETTES.PURPLE, docEl, storage), 'purple')
      assert.equal(docEl.getAttribute('data-theme'), 'dark')
      assert.equal(docEl.getAttribute('data-palette'), 'purple')
      assert.equal(storage.getItem(THEME_STORAGE_KEY), 'dark')
      assert.equal(storage.getItem(PALETTE_STORAGE_KEY), 'purple')

      const restoredMode = getSavedTheme(storage)
      const restoredPalette = getSavedPalette(storage)
      const refreshedDoc = createMockDocElement()
      applyTheme(restoredMode, refreshedDoc, storage)
      applyPalette(restoredPalette, refreshedDoc, storage)
      assert.equal(refreshedDoc.getAttribute('data-theme'), 'dark')
      assert.equal(refreshedDoc.getAttribute('data-palette'), 'purple')
    })
  })
})
