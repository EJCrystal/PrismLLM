export const THEME_STORAGE_KEY = 'prism_llm_theme'
export const LEGACY_THEME_STORAGE_KEY = 'llm_gw_theme'
export const PALETTE_STORAGE_KEY = 'prism_llm_palette'

export type ThemeMode = 'light' | 'dark'
export type ThemePaletteId = 'gemini' | 'sky' | 'cyan' | 'orange' | 'red' | 'purple'

export interface ThemePreset {
  id: ThemeMode
  label: string
  icon: string
  toggleIcon: string
  tip: string
}

export interface ThemePalette {
  id: ThemePaletteId
  name: string
  nameDark: string
  lightAccent: string
  darkAccent: string
  description: string
}

export const THEME_PRESETS: ThemePreset[] = [
  { id: 'light', label: '浅色模式', icon: 'sun', toggleIcon: 'moon', tip: '切换为深色模式' },
  { id: 'dark', label: '深色模式', icon: 'moon', toggleIcon: 'sun', tip: '切换为浅色模式' },
]

export const THEME_PALETTES: ThemePalette[] = [
  { id: 'gemini', name: '四色极光', nameDark: '暗夜极光', lightAccent: '#1A73E8', darkAccent: '#60A5FA', description: '轻透四色极光流光' },
  { id: 'sky', name: '蔚蓝晴空', nameDark: '深海晴空', lightAccent: '#0284C7', darkAccent: '#38BDF8', description: '澄澈晴空天青' },
  { id: 'cyan', name: '碧水青波', nameDark: '幽谷碧波', lightAccent: '#0D9488', darkAccent: '#2DD4BF', description: '清润翡翠青波' },
  { id: 'orange', name: '落霞暖橙', nameDark: '熔岩暮色', lightAccent: '#EA580C', darkAccent: '#FB923C', description: '柔和落日余晖' },
  { id: 'red', name: '丹霞绯红', nameDark: '丝绒酒红', lightAccent: '#E11D48', darkAccent: '#FB7185', description: '通透玫瑰绯霞' },
  { id: 'purple', name: '紫罗幽兰', nameDark: '幻夜幽紫', lightAccent: '#8B5CF6', darkAccent: '#C084FC', description: '轻雾熏衣草紫' },
]

export const THEMES = {
  LIGHT: 'light' as ThemeMode,
  DARK: 'dark' as ThemeMode,
}

export const PALETTES = {
  GEMINI: 'gemini' as ThemePaletteId,
  SKY: 'sky' as ThemePaletteId,
  CYAN: 'cyan' as ThemePaletteId,
  ORANGE: 'orange' as ThemePaletteId,
  RED: 'red' as ThemePaletteId,
  PURPLE: 'purple' as ThemePaletteId,
}

export const DEFAULT_THEME: ThemeMode = THEMES.LIGHT
export const DEFAULT_PALETTE: ThemePaletteId = PALETTES.GEMINI

export function getAvailableThemes(): ThemePreset[] {
  return [...THEME_PRESETS]
}

export function getAvailablePalettes(): ThemePalette[] {
  return [...THEME_PALETTES]
}

export function normalizeTheme(themeId: string | null | undefined): ThemeMode {
  return themeId === THEMES.DARK ? THEMES.DARK : DEFAULT_THEME
}

export function normalizePalette(paletteId: string | null | undefined): ThemePaletteId {
  return THEME_PALETTES.some((item) => item.id === paletteId)
    ? (paletteId as ThemePaletteId)
    : DEFAULT_PALETTE
}

export function getPaletteDisplayName(paletteId: string | null | undefined, mode: ThemeMode = DEFAULT_THEME): string {
  const palette = THEME_PALETTES.find((item) => item.id === normalizePalette(paletteId)) || THEME_PALETTES[0]
  return mode === THEMES.DARK ? palette.nameDark : palette.name
}

function resolveStorage<T>(storage?: T): T | Storage | undefined {
  if (storage !== undefined) {
    return storage
  }
  return typeof localStorage !== 'undefined' ? localStorage : undefined
}

export function getSavedTheme(storage?: Pick<Storage, 'getItem'>): ThemeMode {
  try {
    const targetStorage = resolveStorage(storage)
    const saved = targetStorage?.getItem(THEME_STORAGE_KEY) || targetStorage?.getItem(LEGACY_THEME_STORAGE_KEY)
    return normalizeTheme(saved)
  } catch {
    return DEFAULT_THEME
  }
}

export function getSavedPalette(storage?: Pick<Storage, 'getItem'>): ThemePaletteId {
  try {
    const targetStorage = resolveStorage(storage)
    const savedPalette = targetStorage?.getItem(PALETTE_STORAGE_KEY)
    if (savedPalette && THEME_PALETTES.some((item) => item.id === savedPalette)) {
      return savedPalette as ThemePaletteId
    }
    // Migrate legacy sepia preference to the warm Sunset Orange palette.
    const savedTheme = targetStorage?.getItem(THEME_STORAGE_KEY) || targetStorage?.getItem(LEGACY_THEME_STORAGE_KEY)
    if (savedTheme === 'sepia') {
      return PALETTES.ORANGE
    }
    return DEFAULT_PALETTE
  } catch {
    return DEFAULT_PALETTE
  }
}

export function nextTheme(current: string): ThemeMode {
  return current === THEMES.LIGHT ? THEMES.DARK : THEMES.LIGHT
}

export function applyTheme(
  targetTheme: string | null | undefined,
  docEl: Pick<HTMLElement, 'setAttribute'> | undefined = typeof document !== 'undefined' ? document.documentElement : undefined,
  storage?: Pick<Storage, 'setItem'>,
): ThemeMode {
  const target = normalizeTheme(targetTheme)
  if (docEl) {
    docEl.setAttribute('data-theme', target)
  }
  try {
    resolveStorage(storage)?.setItem(THEME_STORAGE_KEY, target)
  } catch {
    // 忽略无痕/禁用存储异常
  }
  return target
}

export function applyPalette(
  targetPalette: string | null | undefined,
  docEl: Pick<HTMLElement, 'setAttribute'> | undefined = typeof document !== 'undefined' ? document.documentElement : undefined,
  storage?: Pick<Storage, 'setItem'>,
): ThemePaletteId {
  const target = normalizePalette(targetPalette)
  if (docEl) {
    docEl.setAttribute('data-palette', target)
  }
  try {
    resolveStorage(storage)?.setItem(PALETTE_STORAGE_KEY, target)
  } catch {
    // 忽略无痕/禁用存储异常
  }
  return target
}
