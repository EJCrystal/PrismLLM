import { computed, ref } from 'vue'
import {
  THEME_PRESETS,
  DEFAULT_THEME,
  getSavedTheme,
  nextTheme,
  applyTheme,
  normalizeTheme,
} from '../utils/theme.js'

// 全局单例响应式状态，确保全站跨组件实时同步
const currentTheme = ref(getSavedTheme())

// 初始应用到 DOM
if (typeof document !== 'undefined') {
  applyTheme(currentTheme.value)
}

export function useTheme() {
  const isDark = computed(() => currentTheme.value === 'dark')
  const currentPreset = computed(
    () => THEME_PRESETS.find((p) => p.id === currentTheme.value) || THEME_PRESETS[0],
  )

  function setTheme(themeId) {
    const next = normalizeTheme(themeId)
    currentTheme.value = applyTheme(next)
  }

  function toggle() {
    currentTheme.value = applyTheme(nextTheme(currentTheme.value))
  }

  return {
    theme: currentTheme,
    isDark,
    currentPreset,
    presets: THEME_PRESETS,
    setTheme,
    toggleTheme: toggle,
  }
}
