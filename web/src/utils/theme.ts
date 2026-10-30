export const THEME_STORAGE_KEY = 'llm_gw_theme'

/**
 * 内置与支持的主题预设列表。
 * 后续如需添加自定义主题，在此注册并在 CSS 中添加 [data-theme="id"] 即可全局生效。
 */
export interface ThemePreset {
  id: string
  label: string
  icon: string
  toggleIcon: string
  tip: string
}

export const THEME_PRESETS: ThemePreset[] = [
  { id: 'light', label: '浅色主题', icon: 'sun', toggleIcon: 'moon', tip: '切换为深色主题' },
  { id: 'dark', label: '深色主题', icon: 'moon', toggleIcon: 'coffee', tip: '切换为护眼模式' },
  { id: 'sepia', label: '护眼主题', icon: 'coffee', toggleIcon: 'sun', tip: '切换为浅色主题' },
]

export const THEMES = {
  LIGHT: 'light',
  DARK: 'dark',
  SEPIA: 'sepia',
}

export const DEFAULT_THEME = 'light'

/**
 * 获取所有支持的主题列表
 */
export function getAvailableThemes() {
  return THEME_PRESETS
}

/**
 * 校验主题是否合法，未识别时严格回退到默认主题
 *
 * @param {string|null} themeId
 * @returns {'light'|'dark'|string}
 */
export function normalizeTheme(themeId: string | null | undefined): string {
  if (!themeId) return DEFAULT_THEME
  const matched = THEME_PRESETS.find((preset) => preset.id === themeId)
  return matched ? matched.id : DEFAULT_THEME
}

/**
 * 从存储中解析偏好主题，未设置或异常时默认且严格回退到浅色。
 *
 * @param {Storage|null} storage
 * @returns {'light'|'dark'|string}
 */
export function getSavedTheme(storage: Storage | null = null): string {
  try {
    const s = storage || (typeof localStorage !== 'undefined' ? localStorage : null)
    const saved = s?.getItem(THEME_STORAGE_KEY)
    return normalizeTheme(saved)
  } catch {
    return DEFAULT_THEME
  }
}

/**
 * 切换下一个主题（在所有已注册主题间顺次轮转，方便未来扩展多主题）
 *
 * @param {string} current
 * @returns {string}
 */
export function nextTheme(current: string): string {
  const currentIndex = THEME_PRESETS.findIndex((p) => p.id === current)
  if (currentIndex === -1) return THEME_PRESETS[0].id
  const nextIndex = (currentIndex + 1) % THEME_PRESETS.length
  return THEME_PRESETS[nextIndex].id
}

/**
 * 应用主题至 DOM 根节点并持久化至存储。
 *
 * @param {string} targetTheme
 * @param {HTMLElement|null} docEl
 * @param {Storage|null} storage
 * @returns {string}
 */
export function applyTheme(
  targetTheme: string,
  docEl: HTMLElement | null = null,
  storage: Storage | null = null,
): string {
  const theme = normalizeTheme(targetTheme)
  const el = docEl || (typeof document !== 'undefined' ? document.documentElement : null)
  if (el?.setAttribute) {
    el.setAttribute('data-theme', theme)
  }
  try {
    const s = storage || (typeof localStorage !== 'undefined' ? localStorage : null)
    s?.setItem(THEME_STORAGE_KEY, theme)
  } catch {
    // ignore
  }
  return theme
}
