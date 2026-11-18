import { computed, ref } from 'vue'
import {
  THEME_PALETTES,
  THEME_PRESETS,
  THEMES,
  applyPalette,
  applyTheme,
  getPaletteDisplayName,
  getSavedPalette,
  getSavedTheme,
  nextTheme,
  type ThemeMode,
  type ThemePaletteId,
} from '../utils/theme'

const currentTheme = ref<ThemeMode>(getSavedTheme())
const currentPalette = ref<ThemePaletteId>(getSavedPalette())

if (typeof document !== 'undefined') {
  applyTheme(currentTheme.value)
  applyPalette(currentPalette.value)
}

export function useTheme() {
  const isDark = computed(() => currentTheme.value === THEMES.DARK)
  const currentPreset = computed(
    () => THEME_PRESETS.find((preset) => preset.id === currentTheme.value) || THEME_PRESETS[0],
  )
  const activePalette = computed(
    () => THEME_PALETTES.find((item) => item.id === currentPalette.value) || THEME_PALETTES[0],
  )
  const currentPaletteName = computed(() => getPaletteDisplayName(currentPalette.value, currentTheme.value))

  function setTheme(themeId: string | null | undefined): void {
    currentTheme.value = applyTheme(themeId)
  }

  function setPalette(paletteId: string | null | undefined): void {
    currentPalette.value = applyPalette(paletteId)
  }

  function toggle(): void {
    setTheme(nextTheme(currentTheme.value))
  }

  return {
    theme: currentTheme,
    palette: currentPalette,
    palettes: THEME_PALETTES,
    activePalette,
    currentPaletteName,
    isDark,
    currentPreset,
    presets: THEME_PRESETS,
    setTheme,
    setPalette,
    toggleTheme: toggle,
  }
}
